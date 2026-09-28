package com.tactical.platform.wifi

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pManager
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceInfo
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceRequest
import android.os.Build
import com.tactical.domain.identity.RadioLinkState
import com.tactical.domain.result.TacticalResult
import com.tactical.platform.api.wifi.WifiDirectConnectionInfo
import com.tactical.platform.api.wifi.WifiDirectManager
import com.tactical.platform.api.wifi.WifiDirectPeer
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * Single owner of the Android Wi-Fi Direct P2P lifecycle.
 *
 * Discovery and group state are shared hot flows. Consumers do not register
 * their own WifiP2pManager receivers, avoiding duplicate lifecycle ownership.
 */
class AndroidWifiDirectManager(
    private val context: Context,
    private val wifiP2pManager: WifiP2pManager,
    private val wifichannel: WifiP2pManager.Channel
) : WifiDirectManager {

    private val wifiManager by lazy {
        context.getSystemService(Context.WIFI_SERVICE) as WifiManager
    }

    private val _peers = MutableStateFlow<List<WifiDirectPeer>>(emptyList())
    private val _connectionInfo = MutableStateFlow(WifiDirectConnectionInfo())
    private val _state = MutableStateFlow(RadioLinkState.UNAVAILABLE)

    private val started = AtomicBoolean(false)
    private var receiverRegistered = false
    private var serviceDiscoveryStarted = false
    private var serviceRequest: WifiP2pDnsSdServiceRequest? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                    val enabled = intent.getIntExtra(
                        WifiP2pManager.EXTRA_WIFI_STATE,
                        WifiP2pManager.WIFI_P2P_STATE_DISABLED
                    ) == WifiP2pManager.WIFI_P2P_STATE_ENABLED

                    if (enabled && wifiManager.isWifiEnabled) {
                        if (started.get()) {
                            _state.value = RadioLinkState.AVAILABLE
                            if (serviceDiscoveryStarted) {
                                startServiceDiscoveryInternal()
                            }
                        }
                    } else {
                        _connectionInfo.value = WifiDirectConnectionInfo()
                        _peers.value = emptyList()
                        _state.value = RadioLinkState.UNAVAILABLE
                    }
                }

                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                    refreshConnectionInfo()
                }

                WifiP2pManager.WIFI_P2P_DISCOVERY_CHANGED_ACTION -> {
                    val discovering = intent.getIntExtra(
                        WifiP2pManager.EXTRA_DISCOVERY_STATE,
                        WifiP2pManager.WIFI_P2P_DISCOVERY_STOPPED
                    ) == WifiP2pManager.WIFI_P2P_DISCOVERY_STARTED

                    if (discovering &&
                        _state.value != RadioLinkState.CONNECTING &&
                        !_connectionInfo.value.groupFormed
                    ) {
                        _state.value = RadioLinkState.AVAILABLE
                    }
                }
            }
        }
    }

    override suspend fun start(): TacticalResult<Unit> {
        if (!hasWifiDirectPermission()) {
            _state.value = RadioLinkState.FAILED
            return TacticalResult.Failure("Missing Wi-Fi Direct permission")
        }

        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT)) {
            _state.value = RadioLinkState.UNAVAILABLE
            return TacticalResult.Failure("Device does not support Wi-Fi Direct")
        }

        if (!wifiManager.isWifiEnabled) {
            _state.value = RadioLinkState.UNAVAILABLE
            return TacticalResult.Failure("Wi-Fi is off")
        }

        if (!started.compareAndSet(false, true)) {
            return TacticalResult.Success(Unit)
        }

        return try {
            registerReceiverOnce()
            _state.value = RadioLinkState.AVAILABLE
            refreshConnectionInfo()
            TacticalResult.Success(Unit)
        } catch (e: SecurityException) {
            started.set(false)
            _state.value = RadioLinkState.FAILED
            TacticalResult.Failure("Wi-Fi Direct permission denied")
        } catch (e: Exception) {
            started.set(false)
            _state.value = RadioLinkState.FAILED
            TacticalResult.Failure(
                "Wi-Fi Direct startup failed: ${e.message ?: e.javaClass.simpleName}"
            )
        }
    }

    override suspend fun stop() {
        if (!started.compareAndSet(true, false)) return

        serviceDiscoveryStarted = false

        serviceRequest?.let { request ->
            runCatching {
                wifiP2pManager.removeServiceRequest(
                    wifichannel,
                    request,
                    null
                )
            }
        }
        serviceRequest = null

        if (hasWifiDirectPermission()) {
            runCatching { wifiP2pManager.clearLocalServices(wifichannel, null) }
        }

        if (receiverRegistered) {
            runCatching { context.unregisterReceiver(receiver) }
            receiverRegistered = false
        }

        _peers.value = emptyList()
        _connectionInfo.value = WifiDirectConnectionInfo()
        _state.value = if (wifiManager.isWifiEnabled) {
            RadioLinkState.AVAILABLE
        } else {
            RadioLinkState.UNAVAILABLE
        }
    }

    override suspend fun advertisePresence(
        deviceId: String,
        callsign: String
    ): TacticalResult<Unit> {
        val startedResult = start()
        if (startedResult is TacticalResult.Failure) return startedResult

        val record = mapOf(
            "app" to "itantra",
            "id" to deviceId,
            "callsign" to callsign.take(32)
        )

        val serviceInfo = WifiP2pDnsSdServiceInfo.newInstance(
            "_itantra",
            "_presence._tcp",
            record
        )

        return suspendCancellableCoroutine { continuation ->
            try {
                wifiP2pManager.clearLocalServices(
                    wifichannel,
                    object : WifiP2pManager.ActionListener {
                        override fun onSuccess() =
                            addLocalService(serviceInfo, continuation)

                        override fun onFailure(reason: Int) =
                            addLocalService(serviceInfo, continuation)
                    }
                )
            } catch (e: SecurityException) {
                if (continuation.isActive) {
                    continuation.resume(
                        TacticalResult.Failure("Wi-Fi Direct permission denied")
                    )
                }
            }
        }
    }

    private fun addLocalService(
        serviceInfo: WifiP2pDnsSdServiceInfo,
        continuation: CancellableContinuation<TacticalResult<Unit>>
    ) {
        try {
            wifiP2pManager.addLocalService(
                wifichannel,
                serviceInfo,
                object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        if (continuation.isActive) {
                            continuation.resume(TacticalResult.Success(Unit))
                        }
                    }

                    override fun onFailure(reason: Int) {
                        if (continuation.isActive) {
                            continuation.resume(
                                TacticalResult.Failure(
                                    "iTantra Wi-Fi service registration failed: $reason"
                                )
                            )
                        }
                    }
                }
            )
        } catch (e: SecurityException) {
            if (continuation.isActive) {
                continuation.resume(
                    TacticalResult.Failure("Wi-Fi Direct permission denied")
                )
            }
        }
    }

    override suspend fun discoverPeers(): Flow<List<WifiDirectPeer>> {
        val startedResult = start()
        if (startedResult is TacticalResult.Failure) {
            return _peers.asStateFlow()
        }

        startServiceDiscoveryInternal()
        return _peers.asStateFlow()
    }

    private fun startServiceDiscoveryInternal() {
        if (!started.get() || serviceDiscoveryStarted || !hasWifiDirectPermission()) {
            return
        }

        try {
            wifiP2pManager.setDnsSdResponseListeners(
                wifichannel,
                WifiP2pManager.DnsSdServiceResponseListener { instanceName, _, device ->
                    android.util.Log.d(
                        TAG,
                        "iTantra Wi-Fi service: ${instanceName} @ ${device.deviceAddress}"
                    )
                },
                WifiP2pManager.DnsSdTxtRecordListener { _, record, device ->
                    if (record["app"] != "itantra") return@DnsSdTxtRecordListener

                    val id = record["id"] ?: return@DnsSdTxtRecordListener
                    val callsign = record["callsign"] ?: device.deviceName

                    if (runCatching { UUID.fromString(id) }.isFailure) {
                        return@DnsSdTxtRecordListener
                    }

                    val peer = WifiDirectPeer(
                        deviceAddress = device.deviceAddress,
                        deviceName = device.deviceName,
                        appDeviceId = id,
                        callsign = callsign,
                        linkState = RadioLinkState.AVAILABLE,
                        lastSeenEpochMs = Instant.now().toEpochMilli()
                    )

                    _peers.value = _peers.value
                        .filterNot { it.deviceAddress.equals(device.deviceAddress, ignoreCase = true) }
                        .plus(peer)
                        .sortedBy { it.callsign ?: it.deviceName }
                }
            )

            val request = WifiP2pDnsSdServiceRequest.newInstance()
            serviceRequest = request

            wifiP2pManager.addServiceRequest(
                wifichannel,
                request,
                object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        try {
                            wifiP2pManager.discoverServices(
                                wifichannel,
                                object : WifiP2pManager.ActionListener {
                                    override fun onSuccess() {
                                        serviceDiscoveryStarted = true
                                        _state.value = RadioLinkState.AVAILABLE
                                        android.util.Log.d(
                                            TAG,
                                            "iTantra Wi-Fi service discovery started"
                                        )
                                    }

                                    override fun onFailure(reason: Int) {
                                        serviceDiscoveryStarted = false
                                        _state.value = RadioLinkState.FAILED
                                        android.util.Log.w(
                                            TAG,
                                            "Wi-Fi service discovery failed: $reason"
                                        )
                                    }
                                }
                            )
                        } catch (e: SecurityException) {
                            serviceDiscoveryStarted = false
                            _state.value = RadioLinkState.FAILED
                            android.util.Log.w(
                                TAG,
                                "Wi-Fi service discovery permission denied",
                                e
                            )
                        }
                    }

                    override fun onFailure(reason: Int) {
                        serviceDiscoveryStarted = false
                        serviceRequest = null
                        _state.value = RadioLinkState.FAILED
                        android.util.Log.w(
                            TAG,
                            "Wi-Fi service request failed: $reason"
                        )
                    }
                }
            )
        } catch (e: SecurityException) {
            serviceDiscoveryStarted = false
            _state.value = RadioLinkState.FAILED
            android.util.Log.w(
                TAG,
                "Wi-Fi service discovery permission denied",
                e
            )
        } catch (e: Exception) {
            serviceDiscoveryStarted = false
            _state.value = RadioLinkState.FAILED
            android.util.Log.w(
                TAG,
                "Wi-Fi service discovery setup failed",
                e
            )
        }
    }

    private fun refreshConnectionInfo() {
        if (!started.get() || !hasWifiDirectPermission()) return

        try {
            wifiP2pManager.requestConnectionInfo(wifichannel) { info ->
                if (info == null || !info.groupFormed) {
                    _connectionInfo.value = WifiDirectConnectionInfo()
                    if (_state.value != RadioLinkState.CONNECTING) {
                        _state.value = if (wifiManager.isWifiEnabled) {
                            RadioLinkState.AVAILABLE
                        } else {
                            RadioLinkState.UNAVAILABLE
                        }
                    }
                    return@requestConnectionInfo
                }

                _connectionInfo.value = WifiDirectConnectionInfo(
                    groupFormed = true,
                    isGroupOwner = info.isGroupOwner,
                    groupOwnerAddress = info.groupOwnerAddress?.hostAddress
                )
                _state.value = RadioLinkState.CONNECTED
            }
        } catch (_: SecurityException) {
            _state.value = RadioLinkState.FAILED
        }
    }

    override fun connectionInfo(): StateFlow<WifiDirectConnectionInfo> =
        _connectionInfo.asStateFlow()

    override fun state(): StateFlow<RadioLinkState> =
        _state.asStateFlow()

    override suspend fun disconnect(): TacticalResult<Unit> {
        if (!hasWifiDirectPermission()) {
            return TacticalResult.Failure("Missing Wi-Fi Direct permission")
        }

        return suspendCancellableCoroutine { continuation ->
            try {
                wifiP2pManager.removeGroup(
                    wifichannel,
                    object : WifiP2pManager.ActionListener {
                        override fun onSuccess() {
                            _connectionInfo.value = WifiDirectConnectionInfo()
                            _state.value = if (wifiManager.isWifiEnabled) {
                                RadioLinkState.AVAILABLE
                            } else {
                                RadioLinkState.UNAVAILABLE
                            }
                            if (continuation.isActive) {
                                continuation.resume(TacticalResult.Success(Unit))
                            }
                        }

                        override fun onFailure(reason: Int) {
                            if (continuation.isActive) {
                                continuation.resume(
                                    TacticalResult.Failure(
                                        "Wi-Fi Direct disconnect failed: $reason"
                                    )
                                )
                            }
                        }
                    }
                )
            } catch (e: SecurityException) {
                if (continuation.isActive) {
                    continuation.resume(
                        TacticalResult.Failure("Wi-Fi Direct permission denied")
                    )
                }
            }
        }
    }

    override suspend fun connect(deviceAddress: String): TacticalResult<Unit> {
        if (!hasWifiDirectPermission()) {
            return TacticalResult.Failure("Missing Wi-Fi Direct permission")
        }

        val cleanedAddress = deviceAddress.trim()
        if (cleanedAddress.isBlank()) {
            return TacticalResult.Failure("Wi-Fi Direct device address is blank")
        }

        val startResult = start()
        if (startResult is TacticalResult.Failure) return startResult

        _state.value = RadioLinkState.CONNECTING
        _peers.value = _peers.value.map {
            if (it.deviceAddress.equals(cleanedAddress, ignoreCase = true)) {
                it.copy(linkState = RadioLinkState.CONNECTING)
            } else {
                it
            }
        }

        return suspendCancellableCoroutine { continuation ->
            val config = WifiP2pConfig().apply {
                deviceAddress = cleanedAddress
            }

            try {
                wifiP2pManager.connect(
                    wifichannel,
                    config,
                    object : WifiP2pManager.ActionListener {
                        override fun onSuccess() {
                            if (continuation.isActive) {
                                // connect() accepted the negotiation request.
                                // CONNECTED is emitted later by the group event.
                                continuation.resume(TacticalResult.Success(Unit))
                            }
                        }

                        override fun onFailure(reason: Int) {
                            _state.value = RadioLinkState.FAILED
                            _peers.value = _peers.value.map {
                                if (it.deviceAddress.equals(cleanedAddress, ignoreCase = true)) {
                                    it.copy(linkState = RadioLinkState.FAILED)
                                } else {
                                    it
                                }
                            }
                            if (continuation.isActive) {
                                continuation.resume(
                                    TacticalResult.Failure(
                                        "connect(${cleanedAddress}) failed, reason=${reason}"
                                    )
                                )
                            }
                        }
                    }
                )
            } catch (e: SecurityException) {
                _state.value = RadioLinkState.FAILED
                if (continuation.isActive) {
                    continuation.resume(
                        TacticalResult.Failure("Wi-Fi Direct permission denied")
                    )
                }
            }
        }
    }

    private fun registerReceiverOnce() {
        if (receiverRegistered) return

        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_DISCOVERY_CHANGED_ACTION)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(
                receiver,
                filter,
                Context.RECEIVER_NOT_EXPORTED
            )
        } else {
            @Suppress("DEPRECATION")
            context.registerReceiver(receiver, filter)
        }

        receiverRegistered = true
    }

    private fun hasWifiDirectPermission(): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.NEARBY_WIFI_DEVICES
        } else {
            Manifest.permission.ACCESS_FINE_LOCATION
        }

        return context.checkSelfPermission(permission) ==
            PackageManager.PERMISSION_GRANTED
    }

    companion object {
        private const val TAG = "AndroidWifiDirect"
    }
}
