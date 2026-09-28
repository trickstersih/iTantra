package com.tactical.platform.wifi

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.location.LocationManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pManager
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceInfo
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceRequest
import android.os.Build
import com.tactical.domain.identity.RadioLinkState
import com.tactical.domain.result.TacticalResult
import com.tactical.platform.api.wifi.WifiDirectConnectionInfo
import com.tactical.platform.api.wifi.WifiDirectManager
import com.tactical.platform.api.wifi.WifiDirectPeer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * Single owner of the Android Wi-Fi Direct P2P lifecycle.
 *
 * Discovery and group state are shared hot flows. Consumers do not register
 * their own WifiP2pManager receivers.
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
    private var peerDiscoveryStarted = false
    private var discoveryRequested = false
    private var serviceRequest: WifiP2pDnsSdServiceRequest? = null
    private var discoveryLoopJob: Job? = null
    private var presenceRetryJob: Job? = null
    private var advertisedDeviceId: String? = null
    private var advertisedCallsign: String? = null
    private var presenceRegistrationInProgress = false
    private var presenceRegistered = false
    private val managerScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                    val enabled = intent.getIntExtra(
                        WifiP2pManager.EXTRA_WIFI_STATE,
                        WifiP2pManager.WIFI_P2P_STATE_DISABLED
                    ) == WifiP2pManager.WIFI_P2P_STATE_ENABLED

                    if (enabled && wifiManager.isWifiEnabled) {
                        _state.value = RadioLinkState.AVAILABLE
                        advertisedDeviceId?.let { id ->
                            advertisedCallsign?.let { callsign ->
                                registerPresenceServiceAsync(id, callsign)
                            }
                        }
                        if (discoveryRequested) {
                            startDiscoveryLoop()
                        }
                    } else {
                        resetP2pState()
                    }
                }

                WifiManager.WIFI_STATE_CHANGED_ACTION -> {
                    val wifiState = intent.getIntExtra(
                        WifiManager.EXTRA_WIFI_STATE,
                        WifiManager.WIFI_STATE_UNKNOWN
                    )

                    if (wifiState == WifiManager.WIFI_STATE_ENABLED) {
                        if (started.get()) {
                            _state.value = RadioLinkState.AVAILABLE
                            advertisedDeviceId?.let { id ->
                                advertisedCallsign?.let { callsign ->
                                    registerPresenceServiceAsync(id, callsign)
                                }
                            }
                            if (discoveryRequested) {
                                startDiscoveryLoop()
                            }
                        }
                    } else if (
                        wifiState == WifiManager.WIFI_STATE_DISABLED ||
                        wifiState == WifiManager.WIFI_STATE_DISABLING
                    ) {
                        resetP2pState()
                    }
                }

                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                    refreshConnectionInfo()
                }

                LocationManager.MODE_CHANGED_ACTION -> {
                    if (isLocationModeEnabled()) {
                        if (started.get() &&
                            wifiManager.isWifiEnabled &&
                            discoveryRequested
                        ) {
                            startDiscoveryLoop()
                        }
                    } else {
                        removeServiceRequest()
                        if (!_connectionInfo.value.groupFormed) {
                            _state.value = RadioLinkState.FAILED
                        }
                    }
                }


                WifiP2pManager.WIFI_P2P_DISCOVERY_CHANGED_ACTION -> {
                    val discovering = intent.getIntExtra(
                        WifiP2pManager.EXTRA_DISCOVERY_STATE,
                        WifiP2pManager.WIFI_P2P_DISCOVERY_STOPPED
                    ) == WifiP2pManager.WIFI_P2P_DISCOVERY_STARTED

                    android.util.Log.d(
                        TAG,
                        "Wi-Fi P2P discovery state=" +
                            if (discovering) "STARTED" else "STOPPED"
                    )

                    if (!discovering) {
                        peerDiscoveryStarted = false
                    }
                }
            }
        }
    }

    override suspend fun start(): TacticalResult<Unit> {
        android.util.Log.d(
            TAG,
            "Wi-Fi Direct start: wifiOn=" + wifiManager.isWifiEnabled +
                " permission=" + hasWifiDirectPermission() +
                " location=" + isLocationModeEnabled() +
                " feature=" +
                context.packageManager.hasSystemFeature(
                    PackageManager.FEATURE_WIFI_DIRECT
                )
        )

        if (!hasWifiDirectPermission()) {
            _state.value = RadioLinkState.FAILED
            android.util.Log.w(TAG, "Wi-Fi Direct disabled: missing permission")
            return TacticalResult.Failure("Missing Wi-Fi Direct permission")
        }

        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT)) {
            _state.value = RadioLinkState.UNAVAILABLE
            return TacticalResult.Failure("Device does not support Wi-Fi Direct")
        }

        if (!started.compareAndSet(false, true)) {
            return TacticalResult.Success(Unit)
        }

        return try {
            registerReceiverOnce()

            if (!wifiManager.isWifiEnabled) {
                _state.value = RadioLinkState.UNAVAILABLE
                TacticalResult.Failure("Wi-Fi is off")
            } else {
                _state.value = RadioLinkState.AVAILABLE
                refreshConnectionInfo()
                TacticalResult.Success(Unit)
            }
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
        peerDiscoveryStarted = false
        discoveryRequested = false
        discoveryLoopJob?.cancel()
        discoveryLoopJob = null
        presenceRetryJob?.cancel()
        presenceRetryJob = null
        presenceRegistered = false

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
        _state.value = RadioLinkState.UNAVAILABLE
    }

    override suspend fun advertisePresence(
        deviceId: String,
        callsign: String
    ): TacticalResult<Unit> {
        val startResult = start()
        if (startResult is TacticalResult.Failure &&
            startResult.error !in setOf("Wi-Fi is off")
        ) {
            return startResult
        }

        advertisedDeviceId = deviceId
        advertisedCallsign = callsign
        android.util.Log.d(
            TAG,
            "Advertising iTantra Wi-Fi presence for id=" + deviceId
        )

        if (!wifiManager.isWifiEnabled) {
            return TacticalResult.Failure("Wi-Fi is off")
        }

        return registerPresenceServiceAwait(deviceId, callsign)
    }

    private fun registerPresenceServiceAsync(
        deviceId: String,
        callsign: String
    ) {
        if (presenceRegistered || presenceRegistrationInProgress) return

        managerScope.launch {
            val result = registerPresenceServiceAwait(deviceId, callsign)
            if (result is TacticalResult.Failure) {
                android.util.Log.w(
                    TAG,
                    "Wi-Fi Direct presence registration failed; retrying"
                )
                if (presenceRetryJob?.isActive != true) {
                    val id = advertisedDeviceId
                    val advertisedName = advertisedCallsign
                    if (id != null && advertisedName != null) {
                        presenceRetryJob = managerScope.launch {
                            delay(PRESENCE_RETRY_MS)
                            if (started.get() &&
                                wifiManager.isWifiEnabled &&
                                hasWifiDirectPermission() &&
                                !presenceRegistered
                            ) {
                                registerPresenceServiceAsync(id, advertisedName)
                            }
                            presenceRetryJob = null
                        }
                    }
                }
            } else {
                presenceRetryJob?.cancel()
                presenceRetryJob = null
            }
        }
    }

    private suspend fun registerPresenceServiceAwait(
        deviceId: String,
        callsign: String
    ): TacticalResult<Unit> {
        if (!started.get() ||
            !wifiManager.isWifiEnabled ||
            !hasWifiDirectPermission()
        ) {
            return TacticalResult.Failure("Wi-Fi Direct unavailable")
        }

        synchronized(this) {
            if (presenceRegistered) {
                return TacticalResult.Success(Unit)
            }
            if (presenceRegistrationInProgress) {
                // Another lifecycle callback is already registering the same
                // service. Let the next discovery retry observe the result.
                return TacticalResult.Success(Unit)
            }
            presenceRegistrationInProgress = true
        }

        return try {
            suspendCancellableCoroutine { continuation ->
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

                fun finish(result: TacticalResult<Unit>) {
                    synchronized(this) {
                        presenceRegistrationInProgress = false
                        if (result is TacticalResult.Success) {
                            presenceRegistered = true
                        }
                    }
                    if (continuation.isActive) {
                        continuation.resume(result)
                    }
                }

                try {
                    // Do not clear all local P2P services before every
                    // advertisement. On some Android/OEM Wi-Fi stacks the
                    // clear+add sequence races the P2P discovery engine and
                    // leaves both operations reporting BUSY. A local service
                    // is safe to add directly; stop() clears it during the
                    // manager lifecycle shutdown.
                    wifiP2pManager.addLocalService(
                        wifichannel,
                        serviceInfo,
                        object : WifiP2pManager.ActionListener {
                            override fun onSuccess() {
                                android.util.Log.d(
                                    TAG,
                                    "iTantra Wi-Fi presence advertised"
                                )
                                finish(TacticalResult.Success(Unit))
                            }

                            override fun onFailure(reason: Int) {
                                android.util.Log.w(
                                    TAG,
                                    "iTantra Wi-Fi presence registration failed: " +
                                        reason
                                )
                                finish(
                                    TacticalResult.Failure(
                                        "iTantra Wi-Fi service registration failed: " +
                                            reason
                                    )
                                )
                            }
                        }
                    )
                } catch (_: SecurityException) {
                    finish(
                        TacticalResult.Failure(
                            "Wi-Fi Direct permission denied"
                        )
                    )
                } catch (e: Exception) {
                    finish(
                        TacticalResult.Failure(
                            "iTantra Wi-Fi service registration failed: " +
                                (e.message ?: e.javaClass.simpleName)
                        )
                    )
                }
            }
        } catch (e: Exception) {
            synchronized(this) {
                presenceRegistrationInProgress = false
            }
            TacticalResult.Failure(
                "iTantra Wi-Fi service registration failed: " +
                    (e.message ?: e.javaClass.simpleName)
            )
        }
    }

    override suspend fun discoverPeers(): Flow<List<WifiDirectPeer>> {
        val startResult = start()

        if (startResult is TacticalResult.Failure &&
            startResult.error !in setOf("Wi-Fi is off")
        ) {
            return _peers.asStateFlow()
        }

        if (wifiManager.isWifiEnabled) {
            discoveryRequested = true
            startDiscoveryLoop()
        }

        return _peers.asStateFlow()
    }

    private fun startServiceDiscoveryInternal() {
        if (!started.get() ||
            serviceDiscoveryStarted ||
            serviceRequest != null ||
            !wifiManager.isWifiEnabled ||
            !hasWifiDirectPermission()
        ) {
            return
        }

        if (!isLocationModeEnabled()) {
            _state.value = RadioLinkState.FAILED
            android.util.Log.w(
                TAG,
                "Wi-Fi Direct discovery is unavailable while Location Mode is off"
            )
            return
        }

        try {
            wifiP2pManager.setDnsSdResponseListeners(
                wifichannel,
                WifiP2pManager.DnsSdServiceResponseListener { instanceName, _, device ->
                    android.util.Log.d(
                        TAG,
                        "iTantra Wi-Fi service: " +
                            instanceName + " @ " + device.deviceAddress
                    )
                },
                WifiP2pManager.DnsSdTxtRecordListener { _, record, device ->
                    android.util.Log.d(
                        TAG,
                        "Wi-Fi TXT from " + device.deviceAddress +
                            ": " + record
                    )
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
                        .filterNot {
                            it.deviceAddress.equals(
                                device.deviceAddress,
                                ignoreCase = true
                            )
                        }
                        .plus(peer)
                        .sortedBy { it.callsign ?: it.deviceName }
                }
            )

            val request = WifiP2pDnsSdServiceRequest.newInstance(
                "_itantra",
                "_presence._tcp"
            )
            serviceRequest = request
            android.util.Log.d(
                TAG,
                "Registering iTantra DNS-SD service request"
            )

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
                                        removeServiceRequest()
                                        _state.value = RadioLinkState.FAILED
                                        android.util.Log.w(
                                            TAG,
                                            "Wi-Fi service discovery failed: " + reason
                                        )
                                        serviceDiscoveryStarted = false
                                    }
                                }
                            )
                        } catch (e: SecurityException) {
                            removeServiceRequest()
                            _state.value = RadioLinkState.FAILED
                            android.util.Log.w(
                                TAG,
                                "Wi-Fi service discovery permission denied",
                                e
                            )
                        }
                    }

                    override fun onFailure(reason: Int) {
                        removeServiceRequest()
                        _state.value = RadioLinkState.FAILED
                        android.util.Log.w(
                            TAG,
                            "Wi-Fi service request failed: " + reason
                        )
                        serviceDiscoveryStarted = false
                    }
                }
            )
        } catch (e: SecurityException) {
            removeServiceRequest()
            _state.value = RadioLinkState.FAILED
            android.util.Log.w(
                TAG,
                "Wi-Fi service discovery permission denied",
                e
            )
        } catch (e: Exception) {
            removeServiceRequest()
            _state.value = RadioLinkState.FAILED
            android.util.Log.w(
                TAG,
                "Wi-Fi service discovery setup failed",
                e
            )
        }
    }

    private fun startDiscoveryLoop() {
        if (discoveryLoopJob?.isActive == true) return
        if (!started.get() ||
            !discoveryRequested ||
            !wifiManager.isWifiEnabled ||
            !hasWifiDirectPermission() ||
            !isLocationModeEnabled() ||
            _connectionInfo.value.groupFormed
        ) {
            return
        }

        discoveryLoopJob = managerScope.launch {
            while (
                started.get() &&
                discoveryRequested &&
                wifiManager.isWifiEnabled &&
                hasWifiDirectPermission() &&
                isLocationModeEnabled() &&
                !_connectionInfo.value.groupFormed
            ) {
                runPeerDiscoveryPhase()
                if (!discoveryRequested) break

                delay(DISCOVERY_PHASE_GAP_MS)

                runServiceDiscoveryPhase()
                delay(DISCOVERY_PHASE_GAP_MS)
            }

            discoveryLoopJob = null
        }
    }

    private suspend fun runPeerDiscoveryPhase() {
        if (!started.get() ||
            !discoveryRequested ||
            !wifiManager.isWifiEnabled ||
            !hasWifiDirectPermission() ||
            !isLocationModeEnabled()
        ) {
            return
        }

        if (!peerDiscoveryStarted) {
            try {
                suspendCancellableCoroutine<Boolean> { continuation ->
                    wifiP2pManager.discoverPeers(
                        wifichannel,
                        object : WifiP2pManager.ActionListener {
                            override fun onSuccess() {
                                peerDiscoveryStarted = true
                                android.util.Log.d(
                                    TAG,
                                    "Wi-Fi P2P peer discovery started"
                                )
                                if (continuation.isActive) {
                                    continuation.resume(true)
                                }
                            }

                            override fun onFailure(reason: Int) {
                                peerDiscoveryStarted = false
                                android.util.Log.w(
                                    TAG,
                                    "Wi-Fi P2P peer discovery failed: " +
                                        reason
                                )
                                if (continuation.isActive) {
                                    continuation.resume(false)
                                }
                            }
                        }
                    )
                }
            } catch (e: SecurityException) {
                peerDiscoveryStarted = false
                android.util.Log.w(
                    TAG,
                    "Wi-Fi P2P peer discovery permission denied",
                    e
                )
                return
            } catch (e: Exception) {
                peerDiscoveryStarted = false
                android.util.Log.w(
                    TAG,
                    "Wi-Fi P2P peer discovery setup failed",
                    e
                )
                return
            }
        }

        delay(PEER_DISCOVERY_PHASE_MS)
        requestPeerList()

        if (peerDiscoveryStarted && !_connectionInfo.value.groupFormed) {
            try {
                suspendCancellableCoroutine<Boolean> { continuation ->
                    wifiP2pManager.stopPeerDiscovery(
                        wifichannel,
                        object : WifiP2pManager.ActionListener {
                            override fun onSuccess() {
                                peerDiscoveryStarted = false
                                android.util.Log.d(
                                    TAG,
                                    "Wi-Fi P2P peer discovery stopped"
                                )
                                if (continuation.isActive) {
                                    continuation.resume(true)
                                }
                            }

                            override fun onFailure(reason: Int) {
                                android.util.Log.w(
                                    TAG,
                                    "Wi-Fi P2P peer discovery stop failed: " +
                                        reason
                                )
                                if (continuation.isActive) {
                                    continuation.resume(false)
                                }
                            }
                        }
                    )
                }
            } catch (e: Exception) {
                android.util.Log.w(
                    TAG,
                    "Wi-Fi P2P peer discovery stop threw",
                    e
                )
            }
        }
    }

    private fun requestPeerList() {
        if (!started.get() || !hasWifiDirectPermission()) return

        try {
            wifiP2pManager.requestPeers(
                wifichannel,
                WifiP2pManager.PeerListListener { devices ->
                    android.util.Log.d(
                        TAG,
                        "Wi-Fi P2P peers=" +
                            devices.deviceList.joinToString { device ->
                                device.deviceAddress +
                                    " (" + device.deviceName + ")"
                            }
                    )

                    mergePhysicalPeerHints(devices.deviceList)
                }
            )
        } catch (e: SecurityException) {
            android.util.Log.w(
                TAG,
                "Wi-Fi P2P peer-list request permission denied",
                e
            )
        } catch (e: Exception) {
            android.util.Log.w(
                TAG,
                "Wi-Fi P2P peer-list request failed",
                e
            )
        }
    }

    private fun mergePhysicalPeerHints(
        devices: Collection<WifiP2pDevice>
    ) {
        if (devices.isEmpty()) return

        val discoveredAddresses = devices.map {
            it.deviceAddress.lowercase()
        }.toSet()

        _peers.value = _peers.value.map { peer ->
            if (peer.deviceAddress.lowercase() in discoveredAddresses) {
                peer.copy(
                    deviceName = devices.first {
                        it.deviceAddress.equals(
                            peer.deviceAddress,
                            ignoreCase = true
                        )
                    }.deviceName,
                    lastSeenEpochMs = System.currentTimeMillis()
                )
            } else {
                peer
            }
        }
    }

    private suspend fun runServiceDiscoveryPhase() {
        if (!started.get() ||
            !discoveryRequested ||
            !wifiManager.isWifiEnabled ||
            !hasWifiDirectPermission() ||
            !isLocationModeEnabled() ||
            _connectionInfo.value.groupFormed
        ) {
            return
        }

        startServiceDiscoveryInternal()
        if (!serviceDiscoveryStarted) return

        delay(SERVICE_DISCOVERY_PHASE_MS)

        if (!_connectionInfo.value.groupFormed) {
            removeServiceRequest()
            android.util.Log.d(
                TAG,
                "Wi-Fi DNS-SD service discovery phase ended"
            )
        }
    }

    private fun removeServiceRequest() {
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
    }

    private fun resetP2pState() {
        removeServiceRequest()
        peerDiscoveryStarted = false
        discoveryLoopJob?.cancel()
        discoveryLoopJob = null
        presenceRetryJob?.cancel()
        presenceRetryJob = null
        presenceRegistered = false
        _connectionInfo.value = WifiDirectConnectionInfo()
        _peers.value = emptyList()
        _state.value = RadioLinkState.UNAVAILABLE
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
                                        "Wi-Fi Direct disconnect failed: " + reason
                                    )
                                )
                            }
                        }
                    }
                )
            } catch (_: SecurityException) {
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
        if (startResult is TacticalResult.Failure) {
            return startResult
        }

        _state.value = RadioLinkState.CONNECTING
        _peers.value = _peers.value.map {
            if (it.deviceAddress.equals(cleanedAddress, ignoreCase = true)) {
                it.copy(linkState = RadioLinkState.CONNECTING)
            } else {
                it
            }
        }

        val accepted = suspendCancellableCoroutine<Boolean> { continuation ->
            val config = WifiP2pConfig().apply {
                this.deviceAddress = cleanedAddress
            }

            try {
                wifiP2pManager.connect(
                    wifichannel,
                    config,
                    object : WifiP2pManager.ActionListener {
                        override fun onSuccess() {
                            if (continuation.isActive) continuation.resume(true)
                        }

                        override fun onFailure(reason: Int) {
                            if (continuation.isActive) continuation.resume(false)
                        }
                    }
                )
            } catch (_: SecurityException) {
                if (continuation.isActive) continuation.resume(false)
            }
        }

        if (!accepted) {
            _state.value = RadioLinkState.FAILED
            markPeerState(cleanedAddress, RadioLinkState.FAILED)
            return TacticalResult.Failure(
                "connect(" + cleanedAddress + ") was rejected by Android"
            )
        }

        val established =
            _connectionInfo.value.groupFormed ||
                withTimeoutOrNull(CONNECT_TIMEOUT_MS) {
                    _connectionInfo.first { it.groupFormed }
                    true
                } == true

        if (!established) {
            _state.value = RadioLinkState.FAILED
            markPeerState(cleanedAddress, RadioLinkState.FAILED)
            return TacticalResult.Failure("Wi-Fi Direct connection timed out")
        }

        markPeerState(cleanedAddress, RadioLinkState.CONNECTED)
        return TacticalResult.Success(Unit)
    }

    private fun markPeerState(
        deviceAddress: String,
        state: RadioLinkState
    ) {
        _peers.value = _peers.value.map {
            if (it.deviceAddress.equals(deviceAddress, ignoreCase = true)) {
                it.copy(linkState = state)
            } else {
                it
            }
        }
    }

    private fun registerReceiverOnce() {
        if (receiverRegistered) return

        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_DISCOVERY_CHANGED_ACTION)
            addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
            addAction(LocationManager.MODE_CHANGED_ACTION)
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

    private fun isLocationModeEnabled(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            context.getSystemService(LocationManager::class.java)?.isLocationEnabled == true
        } else {
            runCatching {
                android.provider.Settings.Secure.getInt(
                    context.contentResolver,
                    android.provider.Settings.Secure.LOCATION_MODE
                ) != android.provider.Settings.Secure.LOCATION_MODE_OFF
            }.getOrDefault(false)
        }
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
        private const val CONNECT_TIMEOUT_MS = 15_000L
        private const val SERVICE_DISCOVERY_RETRY_MS = 5_000L
        private const val PRESENCE_RETRY_MS = 5_000L
        private const val PEER_DISCOVERY_PHASE_MS = 4_000L
        private const val SERVICE_DISCOVERY_PHASE_MS = 6_000L
        private const val DISCOVERY_PHASE_GAP_MS = 750L
    }
}
