package com.tactical.app.service

import com.tactical.app.di.DeviceIdentityStore
import com.tactical.app.di.LocalAppDataStore
import com.tactical.domain.identity.DeviceId
import com.tactical.domain.identity.RadioType
import com.tactical.domain.packet.SquadControlAction
import com.tactical.domain.packet.SquadControlPacket
import com.tactical.domain.packet.WifiGroupRemovalNoticePacket
import com.tactical.domain.result.TacticalResult
import com.tactical.platform.api.ble.BleConnectionManager
import com.tactical.platform.api.ble.SquadRequest
import com.tactical.platform.api.squad.SquadMembershipStore
import com.tactical.platform.api.radio.RadioTransport
import com.tactical.platform.api.wifi.WifiDirectManager
import com.tactical.engine.mesh.service.MeshService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import java.util.concurrent.ConcurrentHashMap

/**
 * Mesh control plane for application-level squad requests.
 *
 * Direct squad requests continue to use AndroidBleConnectionManager's existing
 * fixed-size GATT control frames. This coordinator is only used when the UI
 * discovers a peer through one or more mesh hops.
 */
@Singleton
class MeshSquadControlCoordinator @Inject constructor(
    private val meshService: MeshService,
    private val bleConnectionManager: BleConnectionManager,
    private val identityStore: DeviceIdentityStore,
    private val squadMembershipStore: SquadMembershipStore,
    private val radioTransport: RadioTransport,
    private val wifiDirectManager: WifiDirectManager,
    private val localAppDataStore: LocalAppDataStore
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private data class PendingMeshRequest(
        val requestId: String,
        val deviceId: String,
        val callsign: String
    )

    data class WifiGroupRemovalNotice(
        val removedDeviceId: String,
        val removedCallsign: String,
        val removedByDeviceId: String,
        val removedByCallsign: String,
        val blockedPeerIds: Set<String>
    )

    private val pendingByRequestId = ConcurrentHashMap<String, PendingMeshRequest>()
    private val outgoingByRequestId = ConcurrentHashMap<String, String>()
    private val outgoingRequestIdsByDeviceId = ConcurrentHashMap<String, MutableSet<String>>()
    private val _pendingRequests = MutableStateFlow<List<SquadRequest>>(emptyList())
    private val _membershipChanged = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
    private val _removalNotices = MutableSharedFlow<String>(extraBufferCapacity = 8)
    private val _wifiRemovalNotices =
        MutableSharedFlow<WifiGroupRemovalNotice>(extraBufferCapacity = 8)

    val pendingRequests: Flow<List<SquadRequest>> = _pendingRequests.asStateFlow()
    val membershipChanged: Flow<Unit> = _membershipChanged
    val removalNotices: Flow<String> = _removalNotices
    val wifiRemovalNotices: Flow<WifiGroupRemovalNotice> = _wifiRemovalNotices

    init {
        scope.launch {
            meshService.receive().collect { packet ->
                when (packet) {
                    is SquadControlPacket -> {
                        if (packet.target.value != identityStore.deviceIdValue) {
                            return@collect
                        }

                        when (packet.action) {
                            SquadControlAction.REQUEST -> handleRequest(packet)
                            SquadControlAction.RESPONSE -> handleResponse(packet)
                            SquadControlAction.REMOVE -> handleRemove(packet)
                        }
                    }

                    is WifiGroupRemovalNoticePacket -> {
                        if (packet.target.value == identityStore.deviceIdValue) {
                            handleWifiGroupRemovalNotice(packet)
                        }
                    }

                    else -> Unit
                }
            }
        }
    }

    private fun clearOutgoingRequest(requestId: String, deviceId: String? = outgoingByRequestId.remove(requestId)) {
        val target = deviceId ?: return
        outgoingRequestIdsByDeviceId[target]?.let { ids ->
            ids.remove(requestId)
            if (ids.isEmpty()) {
                outgoingRequestIdsByDeviceId.remove(target, ids)
            }
        }
    }

    private fun hasOutgoingRequestTo(deviceId: String): Boolean =
        outgoingRequestIdsByDeviceId[deviceId]?.isNotEmpty() == true

    suspend fun requestAddToSquad(
        deviceId: String
    ): TacticalResult<Unit> {
        if (deviceId.isBlank() || deviceId == identityStore.deviceIdValue) {
            return TacticalResult.Failure("Invalid squad target")
        }

        if (squadMembershipStore.contains(deviceId)) {
            return TacticalResult.Success(Unit)
        }

        wifiDirectManager.allowAutoReconnectTo(deviceId)
        radioTransport.allowPeer(deviceId)

        if (outgoingRequestIdsByDeviceId[deviceId]?.isNotEmpty() == true) {
            return TacticalResult.Success(Unit)
        }

        val requestId = UUID.randomUUID().toString()
        outgoingByRequestId[requestId] = deviceId
        outgoingRequestIdsByDeviceId
            .computeIfAbsent(deviceId) { ConcurrentHashMap.newKeySet() }
            .add(requestId)

        val packet = SquadControlPacket(
            sender = DeviceId(identityStore.deviceIdValue),
            target = DeviceId(deviceId),
            requestId = requestId,
            action = SquadControlAction.REQUEST,
            callsign = identityStore.callsign,
            accepted = null,
            timestamp = System.currentTimeMillis()
        )

        val result = meshService.send(packet)
        if (result is TacticalResult.Failure) {
            clearOutgoingRequest(requestId, deviceId)
        }
        return result
    }

    fun hasPending(deviceId: String): Boolean =
        pendingByRequestId.values.any { it.deviceId == deviceId }

    /**
     * Remove a squad member and propagate the membership change over the mesh.
     *
     * This uses the mesh control plane because the member may be reachable only
     * through Wi-Fi Direct or through a relay.
     */
    /**
     * Removes only the Wi-Fi relationship from a multi-member Wi-Fi group.
     *
     * This is intentionally different from removeFromSquad(): squad membership
     * and BLE connectivity remain untouched. The head closes only its Wi-Fi
     * socket to the selected member and tells the two affected member devices
     * to invalidate their member-to-member relay relationship.
     *
     * The caller must already have established that this is a 3+ device group.
     */
    suspend fun removeFromWifiGroup(
        deviceId: String
    ): TacticalResult<Unit> {
        if (deviceId.isBlank() || deviceId == identityStore.deviceIdValue) {
            return TacticalResult.Failure("Invalid Wi-Fi group target")
        }

        val groupInfo = wifiDirectManager.connectionInfo().value
        if (!groupInfo.groupFormed || !groupInfo.isGroupOwner) {
            return TacticalResult.Failure("Only the Wi-Fi group head can remove a Wi-Fi link")
        }

        val wifiPeerIds = radioTransport
            .connectedPeerIdsByTransport()[RadioType.WIFI_DIRECT]
            .orEmpty()

        if (deviceId !in wifiPeerIds) {
            return TacticalResult.Failure("Peer is not currently connected over Wi-Fi Direct")
        }

        // Persist suppression at the head immediately so the background
        // reconnect loop cannot recreate the link after the socket closes.
        wifiDirectManager.suppressAutoReconnectTo(deviceId)

        // Notify the selected member and every other Wi-Fi peer. These notices
        // affect only the member-to-member Wi-Fi route and never touch BLE or
        // squad membership.
        sendWifiGroupRemovalNoticesIfHead(deviceId)

        kotlinx.coroutines.delay(300L)

        // Close only the head -> selected-member Wi-Fi socket. Because this is
        // a 3+ member group, WifiDirectRadioTransport deliberately keeps the
        // underlying P2P group alive. The existing two-device group teardown
        // remains unchanged in disconnectPeer().
        radioTransport.disconnectPeer(deviceId)

        android.util.Log.d(
            "MeshSquadControlCoordinator",
            "Wi-Fi-only link removal applied for " +
                deviceId +
                "; squad membership preserved"
        )

        return TacticalResult.Success(Unit)
    }

    suspend fun removeFromSquad(
        deviceId: String
    ): TacticalResult<Unit> {
        if (deviceId.isBlank() || deviceId == identityStore.deviceIdValue) {
            return TacticalResult.Failure("Invalid squad target")
        }

        if (!squadMembershipStore.contains(deviceId)) {
            return TacticalResult.Success(Unit)
        }

        val packet = SquadControlPacket(
            sender = DeviceId(identityStore.deviceIdValue),
            target = DeviceId(deviceId),
            requestId = UUID.randomUUID().toString(),
            action = SquadControlAction.REMOVE,
            callsign = identityStore.callsign,
            accepted = null,
            timestamp = System.currentTimeMillis()
        )

        val result = meshService.send(packet)
        if (result is TacticalResult.Failure) {
            return result
        }

        // Only the Wi-Fi Direct group owner owns the physical links to all
        // group members. When the head removes one member, tell every other
        // squad member in that same Wi-Fi group so they can invalidate any
        // logical relay route that depended on the removed member. The notice
        // itself is forced onto Wi-Fi and never uses BLE.
        sendWifiGroupRemovalNoticesIfHead(
            removedDeviceId = deviceId
        )

        // Give the transport a short grace period after the synchronous write
        // so the remote mesh receiver has time to consume the REMOVE before
        // the local TCP/GATT session is deliberately closed.
        kotlinx.coroutines.delay(300L)

        squadMembershipStore.remove(deviceId)
        radioTransport.disconnectPeer(deviceId)
        _membershipChanged.tryEmit(Unit)
        return TacticalResult.Success(Unit)
    }

    suspend fun respondToRequest(
        deviceId: String,
        approve: Boolean
    ): TacticalResult<Unit> {
        val request = pendingByRequestId.values
            .firstOrNull { it.deviceId == deviceId }
            ?: return TacticalResult.Failure("Mesh squad request is no longer pending")

        val response = SquadControlPacket(
            sender = DeviceId(identityStore.deviceIdValue),
            target = DeviceId(request.deviceId),
            requestId = request.requestId,
            action = SquadControlAction.RESPONSE,
            callsign = identityStore.callsign,
            accepted = approve,
            timestamp = System.currentTimeMillis()
        )

        val result = meshService.send(response)
        if (result is TacticalResult.Failure) {
            return result
        }

        pendingByRequestId.remove(request.requestId)
        publishPending()

        if (approve) {
            wifiDirectManager.allowAutoReconnectTo(request.deviceId)
            radioTransport.allowPeer(request.deviceId)

            val membership = bleConnectionManager.addMeshSquadMember(
                deviceId = request.deviceId,
                callsign = request.callsign
            )
            if (membership is TacticalResult.Failure) {
                return membership
            }
            scope.launch {
                runCatching {
                    bleConnectionManager.reconnectSquadMember(request.deviceId)
                }
            }
            _membershipChanged.tryEmit(Unit)
        }

        return TacticalResult.Success(Unit)
    }

    private fun handleRequest(packet: SquadControlPacket) {
        if (packet.sender.value == identityStore.deviceIdValue) return
        if (packet.target.value != identityStore.deviceIdValue) return

        // Simultaneous A -> B and B -> A requests are mutual intent. Once one
        // request reaches the other side, collapse the two requests into one
        // approval instead of presenting another squad-request flow.
        if (hasOutgoingRequestTo(packet.sender.value)) {
            outgoingRequestIdsByDeviceId[packet.sender.value]
                ?.toList()
                ?.forEach { requestId -> clearOutgoingRequest(requestId, packet.sender.value) }

            scope.launch {
                val response = SquadControlPacket(
                    sender = DeviceId(identityStore.deviceIdValue),
                    target = DeviceId(packet.sender.value),
                    requestId = packet.requestId,
                    action = SquadControlAction.RESPONSE,
                    callsign = identityStore.callsign,
                    accepted = true,
                    timestamp = System.currentTimeMillis()
                )

                val result = meshService.send(response)
                if (result is TacticalResult.Success) {
                    val membership = bleConnectionManager.addMeshSquadMember(
                        deviceId = packet.sender.value,
                        callsign = packet.callsign
                    )
                    if (membership is TacticalResult.Success) {
                        _membershipChanged.tryEmit(Unit)
                        android.util.Log.d(
                            "MeshSquadControlCoordinator",
                            "Resolved simultaneous squad requests automatically for " +
                                packet.sender.value
                        )
                    }
                }
            }
            return
        }

        // An explicit Add-to-Squad request is a fresh application-level
        // authorization event. Do not silently accept it just because the
        // receiver still has stale membership from a previous connection;
        // that would bypass the approval UI after a removal/re-add cycle.
        pendingByRequestId.putIfAbsent(
            packet.requestId,
            PendingMeshRequest(
                requestId = packet.requestId,
                deviceId = packet.sender.value,
                callsign = packet.callsign
            )
        )
        publishPending()
    }


    private suspend fun sendWifiGroupRemovalNoticesIfHead(
        removedDeviceId: String
    ) {
        val groupInfo = wifiDirectManager.connectionInfo().value
        if (!groupInfo.groupFormed || !groupInfo.isGroupOwner) {
            return
        }

        val localDeviceId = identityStore.deviceIdValue
        val squadIds = squadMembershipStore.squadDeviceIds()
        val wifiPeers = radioTransport
            .connectedPeerIdsByTransport()[RadioType.WIFI_DIRECT]
            .orEmpty()
            .filter {
                it != localDeviceId &&
                    it in squadIds
            }

        if (wifiPeers.isEmpty()) {
            return
        }

        val removedCallsign =
            localAppDataStore.callsignForPeer(removedDeviceId)
                ?.takeIf { it.isNotBlank() }
                ?: removedDeviceId.take(8)

        // Tell every remaining Wi-Fi peer that the removed member must no
        // longer be considered a Wi-Fi route through this head.
        wifiPeers
            .filter { it != removedDeviceId }
            .forEach { targetId ->
                val packet = WifiGroupRemovalNoticePacket(
                    sender = DeviceId(localDeviceId),
                    target = DeviceId(targetId),
                    removedDevice = DeviceId(removedDeviceId),
                    removedCallsign = removedCallsign,
                    blockedPeerIds = setOf(removedDeviceId),
                    timestamp = System.currentTimeMillis()
                )

                val result = meshService.sendOnTransport(
                    packet = packet,
                    transport = RadioType.WIFI_DIRECT
                )

                if (result is TacticalResult.Failure) {
                    android.util.Log.d(
                        "MeshSquadControlCoordinator",
                        "Wi-Fi removal notice to " + targetId + " failed: " + result.error
                    )
                }
            }

        // Also send an internal-only copy to the removed member. It already
        // receives the normal REMOVE control packet; this copy tells it exactly
        // which remaining Wi-Fi squad peers it must not auto-reconnect to.
        if (removedDeviceId in wifiPeers) {
            val blockedPeerIds = wifiPeers
                .filter { it != removedDeviceId }
                .toSet()

            if (blockedPeerIds.isNotEmpty()) {
                val packet = WifiGroupRemovalNoticePacket(
                    sender = DeviceId(localDeviceId),
                    target = DeviceId(removedDeviceId),
                    removedDevice = DeviceId(removedDeviceId),
                    removedCallsign = removedCallsign,
                    blockedPeerIds = blockedPeerIds,
                    timestamp = System.currentTimeMillis()
                )

                val result = meshService.sendOnTransport(
                    packet = packet,
                    transport = RadioType.WIFI_DIRECT
                )

                if (result is TacticalResult.Failure) {
                    android.util.Log.d(
                        "MeshSquadControlCoordinator",
                        "Wi-Fi internal suppression notice to removed peer " +
                            removedDeviceId +
                            " failed: " +
                            result.error
                    )
                }
            }
        }
    }

    private fun handleWifiGroupRemovalNotice(
        packet: WifiGroupRemovalNoticePacket
    ) {
        // Enforce this at the singleton service layer so the background
        // reconnect loop is blocked even when the Activity/ViewModel is gone.
        packet.blockedPeerIds.forEach { peerId ->
            wifiDirectManager.suppressAutoReconnectTo(peerId)
        }

        _wifiRemovalNotices.tryEmit(
            WifiGroupRemovalNotice(
                removedDeviceId = packet.removedDevice.value,
                removedCallsign = packet.removedCallsign,
                removedByDeviceId = packet.sender.value,
                removedByCallsign =
                    localAppDataStore.callsignForPeer(packet.sender.value)
                        ?.takeIf { it.isNotBlank() }
                        ?: packet.sender.value.take(8),
                blockedPeerIds = packet.blockedPeerIds
            )
        )

        android.util.Log.d(
            "MeshSquadControlCoordinator",
            "Received Wi-Fi group removal notice: removed=" +
                packet.removedDevice.value +
                " blocked=" +
                packet.blockedPeerIds.joinToString(",") +
                " by=" +
                packet.sender.value
        )
    }

    private suspend fun handleRemove(packet: SquadControlPacket) {
        // Removal is authoritative for the addressed relationship. Process it
        // even if local membership has already drifted, so a stale UI/store
        // cannot keep the peer in the squad after a valid REMOVE packet.
        squadMembershipStore.remove(packet.sender.value)

        // The REMOVE packet itself may have arrived over the current Wi-Fi
        // socket. Close that link after applying the membership change and
        // suppress this peer's automatic Wi-Fi socket reconnect.
        radioTransport.disconnectPeer(packet.sender.value)

        pendingByRequestId
            .filterValues { it.deviceId == packet.sender.value }
            .keys
            .forEach { requestId -> pendingByRequestId.remove(requestId) }

        outgoingByRequestId
            .entries
            .filter { it.value == packet.sender.value }
            .forEach { entry -> clearOutgoingRequest(entry.key, entry.value) }

        publishPending()
        _membershipChanged.tryEmit(Unit)
        _removalNotices.tryEmit(packet.callsign)

        android.util.Log.d(
            "MeshSquadControlCoordinator",
            "Remote squad removal applied for " + packet.sender.value
        )
    }

    private fun handleResponse(packet: SquadControlPacket) {
        val expectedTarget = outgoingByRequestId[packet.requestId] ?: return
        if (expectedTarget != packet.sender.value) return
        clearOutgoingRequest(packet.requestId, expectedTarget)

        if (packet.accepted == true) {
            wifiDirectManager.allowAutoReconnectTo(packet.sender.value)
            radioTransport.allowPeer(packet.sender.value)
            scope.launch {
                val result = bleConnectionManager.addMeshSquadMember(
                    deviceId = packet.sender.value,
                    callsign = packet.callsign
                )
                if (result is TacticalResult.Success) {
                    runCatching {
                        bleConnectionManager.reconnectSquadMember(packet.sender.value)
                    }
                    _membershipChanged.tryEmit(Unit)
                }
            }
        }
    }

    private fun publishPending() {
        _pendingRequests.value = pendingByRequestId.values
            .sortedBy { it.callsign.lowercase() }
            .map {
                SquadRequest(
                    deviceId = it.deviceId,
                    callsign = it.callsign
                )
            }
    }
}
