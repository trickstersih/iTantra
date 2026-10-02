package com.tactical.app.service

import com.tactical.app.di.DeviceIdentityStore
import com.tactical.domain.identity.DeviceId
import com.tactical.domain.packet.SquadControlAction
import com.tactical.domain.packet.SquadControlPacket
import com.tactical.domain.result.TacticalResult
import com.tactical.platform.api.ble.BleConnectionManager
import com.tactical.platform.api.ble.SquadRequest
import com.tactical.platform.api.squad.SquadMembershipStore
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
    private val squadMembershipStore: SquadMembershipStore
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private data class PendingMeshRequest(
        val requestId: String,
        val deviceId: String,
        val callsign: String
    )

    private val pendingByRequestId = ConcurrentHashMap<String, PendingMeshRequest>()
    private val outgoingByRequestId = ConcurrentHashMap<String, String>()
    private val outgoingRequestIdsByDeviceId = ConcurrentHashMap<String, MutableSet<String>>()
    private val _pendingRequests = MutableStateFlow<List<SquadRequest>>(emptyList())
    private val _membershipChanged = MutableSharedFlow<Unit>(extraBufferCapacity = 8)

    val pendingRequests: Flow<List<SquadRequest>> = _pendingRequests.asStateFlow()
    val membershipChanged: Flow<Unit> = _membershipChanged

    init {
        scope.launch {
            meshService.receive().collect { packet ->
                val control = packet as? SquadControlPacket ?: return@collect

                if (control.target.value != identityStore.deviceIdValue) return@collect

                when (control.action) {
                    SquadControlAction.REQUEST -> handleRequest(control)
                    SquadControlAction.RESPONSE -> handleResponse(control)
                    SquadControlAction.REMOVE -> handleRemove(control)
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

        squadMembershipStore.remove(deviceId)
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

        if (squadMembershipStore.contains(packet.sender.value)) {
            // Already a member; do not generate a duplicate approval dialog.
            scope.launch {
                meshService.send(
                    SquadControlPacket(
                        sender = DeviceId(identityStore.deviceIdValue),
                        target = DeviceId(packet.sender.value),
                        requestId = packet.requestId,
                        action = SquadControlAction.RESPONSE,
                        callsign = identityStore.callsign,
                        accepted = true,
                        timestamp = System.currentTimeMillis()
                    )
                )
            }
            return
        }

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


    private fun handleRemove(packet: SquadControlPacket) {
        // Only an existing squad member may revoke the local membership.
        if (!squadMembershipStore.contains(packet.sender.value)) return

        squadMembershipStore.remove(packet.sender.value)

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
