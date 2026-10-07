package com.tactical.platform.wifi

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.NetworkInfo
import android.net.wifi.WifiManager
import android.location.LocationManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceInfo
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceRequest
import android.os.Build
import com.tactical.domain.identity.RadioLinkState
import com.tactical.domain.result.TacticalResult
import com.tactical.platform.api.wifi.WifiDirectConnectionInfo
import com.tactical.platform.api.wifi.WifiDirectManager
import com.tactical.platform.api.wifi.WifiDirectPeer
import com.tactical.platform.api.squad.SquadMembershipStore
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
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
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
    private val wifichannel: WifiP2pManager.Channel,
    private val squadMembershipStore: SquadMembershipStore,
    @Suppress("UNUSED_PARAMETER") private val localDeviceId: String
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
    private val serviceDiscoveryStarting = AtomicBoolean(false)
    private var serviceRequest: WifiP2pDnsSdServiceRequest? = null
    private var discoveryRetryJob: Job? = null
    private var peerRefreshJob: Job? = null
    private var presenceRetryJob: Job? = null
    private var autoReconnectJob: Job? = null
    private var reconnectAttemptJob: Job? = null
    private var advertisedDeviceId: String? = null
    private var advertisedCallsign: String? = null
    private var presenceRegistrationInProgress = false
    private var presenceRegistered = false
    private val managerScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val connectMutex = kotlinx.coroutines.sync.Mutex()
    private val suppressedAutoReconnectPeerIds =
        java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private var activePresenceServiceInfo: WifiP2pDnsSdServiceInfo? = null
    private var connectTargetDeviceAddress: String? = null
    private var wifiP2pListenerRegistered = false
    private var connectionAttemptInProgress = false

    // Automatic Wi-Fi upgrade needs to give Android's P2P state machine time
    // to settle after a group teardown/re-authorization. Failed attempts are
    // also backed off so we do not hammer WifiP2pManager and starve discovery.
    @Volatile
    private var autoReconnectRetryAfterEpochMs = 0L
    @Volatile
    private var autoReconnectFailureCount = 0
    @Volatile
    private var autoReconnectFreshPeerAfterEpochMs = 0L
    @Volatile
    private var fastReconnectUntilEpochMs = 0L

    /** True while Android is negotiating a P2P connection, including on the passive peer. */
    private var frameworkConnectionInProgress = false
    /** Monotonic terminal-failure signal for the currently waiting connect() call. */
    private val connectFailureSequence = AtomicLong(0L)
    /** Credentials advertised by an existing iTantra group owner. */
    private val groupCredentialsByDeviceAddress = mutableMapOf<String, GroupCredentials>()

    /**
     * Last known iTantra application ID for each Wi-Fi Direct device address.
     * This survives a Wi-Fi reconnect so the Squad UI can identify the group
     * head before a fresh DNS-SD record arrives.
     */
    private val appDeviceIdByWifiDeviceAddress = mutableMapOf<String, String>()

    /**
     * Persist the Wi-Fi P2P-address -> iTantra UUID mapping. Android can keep
     * an existing P2P group alive while the app process is recreated, so an
     * in-memory-only mapping is not sufficient for restoring the group-head
     * identity immediately after app restart.
     */
    private val wifiIdentityPreferences by lazy {
        context.getSharedPreferences(
            "wifi_direct_identity_cache",
            Context.MODE_PRIVATE
        )
    }

    init {
        wifiIdentityPreferences.all.forEach { (key, value) ->
            if (key.startsWith(WIFI_IDENTITY_PREFIX) && value is String) {
                val address = key.removePrefix(WIFI_IDENTITY_PREFIX)
                if (address.isNotBlank() && value.isNotBlank()) {
                    appDeviceIdByWifiDeviceAddress[address] = value
                }
            }
        }
    }

    /** Local group's current SSID/passphrase, known once group info is available. */
    private var localGroupCredentials: GroupCredentials? = null

    /**
     * Android 15+ exposes the actual P2P state machine through WifiP2pListener.
     * Keep the legacy broadcasts for older releases, but use these callbacks
     * as the authoritative signal on modern devices. This is especially
     * important because connect()'s ActionListener only confirms that the
     * request was handed to the framework.
     */
    private val wifiP2pListener = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
        object : WifiP2pManager.WifiP2pListener {
            override fun onGroupCreating() {
                android.util.Log.d(TAG, "Wi-Fi P2P group creation started")
                if (_connectionInfo.value.groupFormed) return
                frameworkConnectionInProgress = true
                _state.value = RadioLinkState.CONNECTING
            }

            override fun onGroupCreated(
                p2pInfo: WifiP2pInfo,
                p2pGroup: WifiP2pGroup
            ) {
                android.util.Log.d(
                    TAG,
                    "Wi-Fi P2P group created via listener: " +
                        "isGO=" + p2pInfo.isGroupOwner +
                        " owner=" + (p2pGroup.owner?.deviceAddress ?: "?") +
                        " clients=" + p2pGroup.clientList.joinToString(",") {
                            it.deviceAddress
                        }
                )
                frameworkConnectionInProgress = false
                refreshConnectionInfo()
            }

            override fun onGroupCreationFailed(reason: Int) {
                android.util.Log.w(
                    TAG,
                    "Wi-Fi P2P group creation failed via listener: " + reason
                )
                frameworkConnectionInProgress = false
                connectFailureSequence.incrementAndGet()
                if (!_connectionInfo.value.groupFormed) {
                    connectTargetDeviceAddress = null
                    _state.value = RadioLinkState.FAILED
                }
            }

            override fun onGroupNegotiationRejectedByUser() {
                android.util.Log.w(
                    TAG,
                    "Wi-Fi P2P group negotiation rejected by user"
                )
                frameworkConnectionInProgress = false
                connectFailureSequence.incrementAndGet()
                if (!_connectionInfo.value.groupFormed) {
                    connectTargetDeviceAddress = null
                    _state.value = RadioLinkState.FAILED
                }
            }

            override fun onPeerClientJoined(
                p2pInfo: WifiP2pInfo,
                p2pGroup: WifiP2pGroup
            ) {
                android.util.Log.d(
                    TAG,
                    "Wi-Fi P2P peer client joined group: " +
                        (p2pGroup.clientList.lastOrNull()?.deviceAddress ?: "?")
                )
                refreshConnectionInfo()
            }

            override fun onPeerClientDisconnected(
                p2pInfo: WifiP2pInfo,
                p2pGroup: WifiP2pGroup
            ) {
                android.util.Log.d(
                    TAG,
                    "Wi-Fi P2P peer client left group"
                )
                refreshConnectionInfo()
            }

            override fun onListenStateChanged(state: Int) {
                android.util.Log.d(
                    TAG,
                    "Wi-Fi P2P listen state=" + state
                )
            }

            override fun onDiscoveryStateChanged(state: Int) {
                android.util.Log.d(
                    TAG,
                    "Wi-Fi P2P discovery state=" + state
                )
            }
        }
    } else {
        null
    }

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
                        resetAutoReconnectRecoveryState()
                        armFastReconnectRecovery("P2P enabled")
                        ensureAutoReconnectLoop()
                        kickPeerDiscovery("P2P enabled")
                        scheduleAutoReconnectAfterRadioRecovery("P2P enabled")
                        advertisedDeviceId?.let { id ->
                            advertisedCallsign?.let { callsign ->
                                registerPresenceServiceAsync(id, callsign)
                            }
                        }
                        startServiceDiscoveryInternal()
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
                            resetAutoReconnectRecoveryState()
                            armFastReconnectRecovery("Wi-Fi enabled")
                            ensureAutoReconnectLoop()
                            kickPeerDiscovery("Wi-Fi enabled")
                            scheduleAutoReconnectAfterRadioRecovery("Wi-Fi enabled")
                            advertisedDeviceId?.let { id ->
                                advertisedCallsign?.let { callsign ->
                                    registerPresenceServiceAsync(id, callsign)
                                }
                            }
                            startServiceDiscoveryInternal()
                        }
                    } else if (
                        wifiState == WifiManager.WIFI_STATE_DISABLED ||
                        wifiState == WifiManager.WIFI_STATE_DISABLING
                    ) {
                        resetP2pState()
                    }
                }

                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                    val networkInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(
                            WifiP2pManager.EXTRA_NETWORK_INFO,
                            NetworkInfo::class.java
                        )
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(WifiP2pManager.EXTRA_NETWORK_INFO)
                    }

                    android.util.Log.d(
                        TAG,
                        "Wi-Fi P2P connection broadcast received: " +
                            (networkInfo?.detailedState?.name ?: "unknown")
                    )

                    when (networkInfo?.detailedState) {
                        NetworkInfo.DetailedState.CONNECTING,
                        NetworkInfo.DetailedState.AUTHENTICATING,
                        NetworkInfo.DetailedState.OBTAINING_IPADDR -> {
                            frameworkConnectionInProgress = true
                        }

                        NetworkInfo.DetailedState.DISCONNECTED -> {
                            frameworkConnectionInProgress = false
                        }

                        else -> Unit
                    }

                    refreshConnectionInfo()

                    if (
                        networkInfo?.detailedState == NetworkInfo.DetailedState.DISCONNECTED &&
                        started.get() &&
                        wifiManager.isWifiEnabled &&
                        hasWifiDirectPermission() &&
                        !connectionAttemptInProgress &&
                        !_connectionInfo.value.groupFormed
                    ) {
                        resetAutoReconnectRecoveryState()
                        armFastReconnectRecovery("P2P disconnected")
                        kickPeerDiscovery("P2P disconnected")
                        scheduleAutoReconnectAfterRadioRecovery("P2P disconnected")
                        managerScope.launch {
                            delay(100L)
                            startServiceDiscoveryInternal()
                        }
                    }
                }

                WifiP2pManager.ACTION_WIFI_P2P_REQUEST_RESPONSE_CHANGED -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        val response = intent.getIntExtra(
                            WifiP2pManager.EXTRA_REQUEST_RESPONSE,
                            -1
                        )
                        android.util.Log.d(
                            TAG,
                            "Wi-Fi P2P request-response broadcast: " + response
                        )
                    }
                }

                LocationManager.MODE_CHANGED_ACTION -> {
                    if (isLocationModeEnabled()) {
                        if (started.get() && wifiManager.isWifiEnabled) {
                            startServiceDiscoveryInternal()
                        }
                    } else {
                        removeServiceRequest()
                        if (!_connectionInfo.value.groupFormed) {
                            _state.value = RadioLinkState.FAILED
                        }
                    }
                }

                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                    if (
                        started.get() &&
                        wifiManager.isWifiEnabled &&
                        hasWifiDirectPermission()
                    ) {
                        runCatching {
                            wifiP2pManager.requestPeers(wifichannel) { peerList ->
                                android.util.Log.d(
                                    TAG,
                                    "Wi-Fi P2P peer list changed: " +
                                        peerList.deviceList.size + " device(s)"
                                )
                            }
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
                        "Wi-Fi P2P discovery state broadcast: " +
                            if (discovering) "STARTED" else "STOPPED"
                    )

                    if (discovering &&
                        _state.value != RadioLinkState.CONNECTING &&
                        !frameworkConnectionInProgress &&
                        !_connectionInfo.value.groupFormed
                    ) {
                        _state.value = RadioLinkState.AVAILABLE
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
            registerModernP2pListener()
            ensureAutoReconnectLoop()

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
        discoveryRetryJob?.cancel()
        discoveryRetryJob = null
        peerRefreshJob?.cancel()
        peerRefreshJob = null
        presenceRetryJob?.cancel()
        presenceRetryJob = null
        autoReconnectJob?.cancel()
        autoReconnectJob = null
        reconnectAttemptJob?.cancel()
        reconnectAttemptJob = null
        connectTargetDeviceAddress = null
        connectionAttemptInProgress = false
        frameworkConnectionInProgress = false
        presenceRegistered = false
        activePresenceServiceInfo = null

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

        unregisterModernP2pListener()

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

        val identityChanged =
            advertisedDeviceId != deviceId ||
                advertisedCallsign != callsign
        advertisedDeviceId = deviceId
        advertisedCallsign = callsign
        if (identityChanged) {
            presenceRegistered = false
        }
        android.util.Log.d(
            TAG,
            "Advertising iTantra Wi-Fi presence for id=" + deviceId +
                " callsign=" + callsign
        )

        if (!wifiManager.isWifiEnabled) {
            return TacticalResult.Failure("Wi-Fi is off")
        }

        val result = registerPresenceServiceAwait(deviceId, callsign)
        if (result is TacticalResult.Failure) {
            schedulePresenceRetry()
        }
        return result
    }

    private fun registerPresenceServiceAsync(
        deviceId: String,
        callsign: String
    ) {
        if (presenceRegistered || presenceRegistrationInProgress) return

        managerScope.launch {
            val result = runCatching {
                registerPresenceServiceAwait(deviceId, callsign)
            }.getOrElse {
                android.util.Log.w(
                    TAG,
                    "Wi-Fi Direct presence registration retry failed",
                    it
                )
                TacticalResult.Failure(
                    "Wi-Fi Direct presence registration failed"
                )
            }

            if (result is TacticalResult.Failure) {
                schedulePresenceRetry()
            }
        }
    }

    private fun schedulePresenceRetry() {
        if (presenceRegistered || presenceRetryJob?.isActive == true) return
        if (
            !started.get() ||
            !wifiManager.isWifiEnabled ||
            !hasWifiDirectPermission()
        ) {
            return
        }

        val deviceId = advertisedDeviceId ?: return
        val callsign = advertisedCallsign ?: return

        presenceRetryJob = managerScope.launch {
            while (
                started.get() &&
                wifiManager.isWifiEnabled &&
                hasWifiDirectPermission() &&
                !presenceRegistered
            ) {
                delay(PRESENCE_RETRY_MS)

                if (
                    !started.get() ||
                    !wifiManager.isWifiEnabled ||
                    !hasWifiDirectPermission() ||
                    presenceRegistered
                ) {
                    break
                }

                val result = registerPresenceServiceAwait(deviceId, callsign)
                if (result is TacticalResult.Success) {
                    break
                }
            }
            presenceRetryJob = null
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
            // This is a suspend function body, so removing the previous
            // service is performed here, outside the non-suspending Android
            // callback used by addLocalService().
            removeActivePresenceService()

            val recordMap = mutableMapOf(
                "app" to "itantra",
                "id" to deviceId,
                "callsign" to callsign.take(32),
                "group_role" to when {
                    _connectionInfo.value.groupFormed &&
                        _connectionInfo.value.isGroupOwner -> "head"
                    _connectionInfo.value.groupFormed -> "member"
                    else -> "none"
                }
            )

            localGroupCredentials?.let { credentials ->
                recordMap["group_ssid"] = credentials.networkName
                recordMap["group_passphrase"] = credentials.passphrase
            }

            val serviceInfo = WifiP2pDnsSdServiceInfo.newInstance(
                "_itantra",
                "_presence._tcp",
                recordMap.toMap()
            )

            suspendCancellableCoroutine { continuation ->
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
                    wifiP2pManager.addLocalService(
                        wifichannel,
                        serviceInfo,
                        object : WifiP2pManager.ActionListener {
                            override fun onSuccess() {
                                activePresenceServiceInfo = serviceInfo
                                android.util.Log.d(
                                    TAG,
                                    "iTantra Wi-Fi presence advertised: callsign=" +
                                        callsign +
                                        " role=" +
                                        when {
                                            _connectionInfo.value.groupFormed &&
                                                _connectionInfo.value.isGroupOwner -> "head"
                                            _connectionInfo.value.groupFormed -> "member"
                                            else -> "none"
                                        }
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

    private suspend fun removeActivePresenceService() {
        val previousService = activePresenceServiceInfo ?: return
        activePresenceServiceInfo = null

        try {
            suspendCancellableCoroutine<Unit> { continuation ->
                try {
                    wifiP2pManager.removeLocalService(
                        wifichannel,
                        previousService,
                        object : WifiP2pManager.ActionListener {
                            override fun onSuccess() {
                                if (continuation.isActive) {
                                    continuation.resume(Unit)
                                }
                            }

                            override fun onFailure(reason: Int) {
                                android.util.Log.d(
                                    TAG,
                                    "Previous iTantra Wi-Fi presence removal returned " +
                                        reason
                                )
                                if (continuation.isActive) {
                                    continuation.resume(Unit)
                                }
                            }
                        }
                    )
                } catch (_: SecurityException) {
                    if (continuation.isActive) {
                        continuation.resume(Unit)
                    }
                } catch (_: Exception) {
                    if (continuation.isActive) {
                        continuation.resume(Unit)
                    }
                }
            }
        } catch (_: Exception) {
            // Replacement is best-effort. Continue with registration so a
            // transient OEM remove failure does not prevent advertising the
            // current role/callsign.
        }
    }

    private fun refreshPresenceWithGroupCredentials(
        deviceId: String,
        callsign: String
    ) {
        if (!started.get() ||
            !wifiManager.isWifiEnabled ||
            !hasWifiDirectPermission()
        ) {
            return
        }

        presenceRegistered = false
        managerScope.launch {
            delay(300L)
            val result = registerPresenceServiceAwait(deviceId, callsign)
            if (result is TacticalResult.Failure) {
                android.util.Log.w(
                    TAG,
                    "Wi-Fi Direct group credential advertisement refresh failed: " +
                        result.error
                )
                schedulePresenceRetry()
            }
        }
    }

    private fun addLocalServiceAwait(
        serviceInfo: WifiP2pDnsSdServiceInfo,
        finish: (TacticalResult<Unit>) -> Unit
    ) {
        try {
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

    override suspend fun discoverPeers(): Flow<List<WifiDirectPeer>> {
        val startResult = start()

        if (startResult is TacticalResult.Failure &&
            startResult.error !in setOf("Wi-Fi is off")
        ) {
            return _peers.asStateFlow()
        }

        if (wifiManager.isWifiEnabled) {
            startServiceDiscoveryInternal()
        }

        return _peers.asStateFlow()
    }

    private fun startServiceDiscoveryInternal() {
        if (!started.get() ||
            serviceDiscoveryStarted ||
            serviceRequest != null ||
            connectionAttemptInProgress ||
            frameworkConnectionInProgress ||
            !wifiManager.isWifiEnabled ||
            !hasWifiDirectPermission()
        ) {
            return
        }

        // startServiceDiscoveryInternal() can be triggered concurrently by
        // Activity startup, permission refresh, and P2P broadcasts. Android
        // rejects/duplicates overlapping DNS-SD setup, so serialize startup
        // until the asynchronous discoverServices() callback completes.
        if (!serviceDiscoveryStarting.compareAndSet(false, true)) {
            return
        }

        if (!isLocationModeEnabled()) {
            serviceDiscoveryStarting.set(false)
            _state.value = RadioLinkState.FAILED
            android.util.Log.w(
                TAG,
                "Wi-Fi Direct discovery is unavailable while Location Mode is off"
            )
            return
        }

        // Service discovery is separate from Android's normal P2P peer
        // discovery. Start both: peer discovery populates the nearby P2P
        // device list, while DNS-SD resolves the iTantra application UUID.
        if (!_connectionInfo.value.groupFormed) {
            try {
                wifiP2pManager.discoverPeers(
                    wifichannel,
                    object : WifiP2pManager.ActionListener {
                        override fun onSuccess() {
                            android.util.Log.d(
                                TAG,
                                "Wi-Fi P2P peer discovery started"
                            )
                        }

                        override fun onFailure(reason: Int) {
                            android.util.Log.d(
                                TAG,
                                "Wi-Fi P2P peer discovery failed: " + reason
                            )
                        }
                    }
                )
            } catch (e: SecurityException) {
                android.util.Log.w(
                    TAG,
                    "Wi-Fi P2P peer discovery permission denied",
                    e
                )
            }
        }

        try {
            wifiP2pManager.setDnsSdResponseListeners(
                wifichannel,
                WifiP2pManager.DnsSdServiceResponseListener { instanceName, registrationType, device ->
                    if (
                        instanceName == "_itantra" &&
                        registrationType == "_presence._tcp"
                    ) {
                        android.util.Log.d(
                            TAG,
                            "iTantra Wi-Fi service: " +
                                instanceName + " / " +
                                registrationType +
                                " @ " + device.deviceAddress
                        )
                    }
                },
                WifiP2pManager.DnsSdTxtRecordListener { _, record, device ->
                    if (record["app"] != "itantra") return@DnsSdTxtRecordListener

                    val id = record["id"] ?: return@DnsSdTxtRecordListener
                    val callsign = record["callsign"]
                        ?.trim()
                        ?.takeIf { it.isNotBlank() }

                    if (runCatching { UUID.fromString(id) }.isFailure) {
                        return@DnsSdTxtRecordListener
                    }

                    val groupRole = when (record["group_role"]) {
                        "head" -> true
                        "member" -> false
                        else -> null
                    }

                    val groupSsid = record["group_ssid"]
                    val groupPassphrase = record["group_passphrase"]
                    if (!groupSsid.isNullOrBlank() && !groupPassphrase.isNullOrBlank()) {
                        synchronized(groupCredentialsByDeviceAddress) {
                            groupCredentialsByDeviceAddress[
                                device.deviceAddress.lowercase()
                            ] = GroupCredentials(
                                networkName = groupSsid,
                                passphrase = groupPassphrase
                            )
                        }
                        android.util.Log.d(
                            TAG,
                            "Learned existing iTantra group credentials from " +
                                device.deviceAddress
                        )
                    }

                    rememberAppDeviceId(
                        wifiDeviceAddress = device.deviceAddress,
                        appDeviceId = id
                    )

                    val knownCallsign = _peers.value
                        .firstOrNull {
                            it.deviceAddress.equals(
                                device.deviceAddress,
                                ignoreCase = true
                            )
                        }
                        ?.callsign

                    val peer = WifiDirectPeer(
                        deviceAddress = device.deviceAddress,
                        deviceName = device.deviceName,
                        appDeviceId = id,
                        callsign = callsign ?: knownCallsign,
                        isGroupOwner = groupRole,
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
                    
                    // A group member may receive the owner's service record
                    // slightly after group info becomes available. Resolve the
                    // owner app ID again whenever discovery refreshes a peer.
                    val currentConnectionInfo = _connectionInfo.value
                    val ownerAddress = currentConnectionInfo.groupOwnerDeviceAddress
                    if (
                        currentConnectionInfo.groupFormed &&
                        currentConnectionInfo.groupOwnerAppDeviceId == null &&
                        !ownerAddress.isNullOrBlank() &&
                        ownerAddress.equals(device.deviceAddress, ignoreCase = true)
                    ) {
                        _connectionInfo.value = currentConnectionInfo.copy(
                            groupOwnerAppDeviceId = id
                        )
                    }
                }
            )

            // Search all Bonjour services, matching Android's reference
            // implementation. Filter to Itantra in the response callback.
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
                                        discoveryRetryJob?.cancel()
                                        discoveryRetryJob = null
                                        serviceDiscoveryStarted = true
                                        serviceDiscoveryStarting.set(false)
                                        _state.value = RadioLinkState.AVAILABLE
                                        startPeerRefreshLoop()
                                        android.util.Log.d(
                                            TAG,
                                            "iTantra Wi-Fi service discovery started"
                                        )
                                    }

                                    override fun onFailure(reason: Int) {
                                        removeServiceRequest()
                                        serviceDiscoveryStarting.set(false)
                                        _state.value = RadioLinkState.FAILED
                                        android.util.Log.w(
                                            TAG,
                                            "Wi-Fi service discovery failed: " + reason
                                        )
                                        scheduleServiceDiscoveryRetry()
                                    }
                                }
                            )
                        } catch (e: SecurityException) {
                            removeServiceRequest()
                            serviceDiscoveryStarting.set(false)
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
                        serviceDiscoveryStarting.set(false)
                        _state.value = RadioLinkState.FAILED
                        android.util.Log.w(
                            TAG,
                            "Wi-Fi service request failed: " + reason
                        )
                        scheduleServiceDiscoveryRetry()
                    }
                }
            )
        } catch (e: SecurityException) {
            removeServiceRequest()
            serviceDiscoveryStarting.set(false)
            _state.value = RadioLinkState.FAILED
            android.util.Log.w(
                TAG,
                "Wi-Fi service discovery permission denied",
                e
            )
        } catch (e: Exception) {
            removeServiceRequest()
            serviceDiscoveryStarting.set(false)
            _state.value = RadioLinkState.FAILED
            android.util.Log.w(
                TAG,
                "Wi-Fi service discovery setup failed",
                e
            )
        }
    }

    private fun startPeerRefreshLoop() {
        if (peerRefreshJob?.isActive == true) return

        peerRefreshJob = managerScope.launch {
            while (
                started.get() &&
                serviceDiscoveryStarted &&
                wifiManager.isWifiEnabled &&
                hasWifiDirectPermission()
            ) {
                try {
                    wifiP2pManager.requestPeers(wifichannel) { peerList ->
                        val now = System.currentTimeMillis()
                        val androidPeers = peerList.deviceList
                        if (androidPeers.isEmpty()) return@requestPeers

                        val byAddress = _peers.value.associateBy { it.deviceAddress.lowercase() }
                        val refreshed = _peers.value.toMutableList()

                        androidPeers.forEach { androidPeer ->
                            val address = androidPeer.deviceAddress
                            val key = address.lowercase()
                            val existing = byAddress[key]
                            if (existing != null) {
                                refreshed.removeAll {
                                    it.deviceAddress.equals(address, ignoreCase = true)
                                }
                                refreshed += existing.copy(lastSeenEpochMs = now)
                                return@forEach
                            }

                            // DNS-SD is occasionally asymmetric on OEM P2P
                            // stacks. If this MAC was learned before, materialize
                            // the stable iTantra identity from the persistent cache
                            // even when the fresh TXT callback has not arrived yet.
                            val cachedId = synchronized(appDeviceIdByWifiDeviceAddress) {
                                appDeviceIdByWifiDeviceAddress.entries
                                    .firstOrNull { it.key.equals(address, ignoreCase = true) }
                                    ?.value
                            }
                            if (!cachedId.isNullOrBlank() && cachedId != localDeviceId) {
                                refreshed.removeAll {
                                    it.deviceAddress.equals(address, ignoreCase = true)
                                }

                                // The Android P2P name is hardware/device metadata,
                                // not the user's iTantra callsign. Preserve a previously
                                // learned callsign, but never manufacture one from the
                                // phone model/name while waiting for DNS-SD TXT.
                                val knownCallsign = _peers.value
                                    .firstOrNull {
                                        it.appDeviceId.equals(
                                            cachedId,
                                            ignoreCase = true
                                        )
                                    }
                                    ?.callsign

                                refreshed += WifiDirectPeer(
                                    deviceAddress = address,
                                    deviceName = androidPeer.deviceName,
                                    appDeviceId = cachedId,
                                    callsign = knownCallsign,
                                    isGroupOwner = null,
                                    linkState = RadioLinkState.AVAILABLE,
                                    lastSeenEpochMs = now
                                )
                            }
                        }

                        _peers.value = refreshed
                    }
                } catch (_: SecurityException) {
                    break
                } catch (e: Exception) {
                    android.util.Log.d(
                        TAG,
                        "Wi-Fi P2P peer refresh failed: " +
                            (e.message ?: e.javaClass.simpleName)
                    )
                }

                delay(PEER_REFRESH_MS)
            }

            peerRefreshJob = null
        }
    }

    /**
     * Keeps Wi-Fi Direct squad links self-healing without requiring the
     * Activity to be open. Squad membership is persistent, while the P2P
     * device address is rediscovered dynamically after range or Wi-Fi changes.
     */
    private fun ensureAutoReconnectLoop() {
        if (autoReconnectJob?.isActive == true) return

        autoReconnectJob = managerScope.launch {
            while (started.get()) {
                try {
                    if (
                        wifiManager.isWifiEnabled &&
                        hasWifiDirectPermission() &&
                        isLocationModeEnabled() &&
                        !_connectionInfo.value.groupFormed
                    ) {
                        // Keep the normal peer/service discovery session alive.
                        // Do not call discoverPeers() on every reconnect tick:
                        // repeatedly restarting the P2P discovery operation can
                        // starve DNS-SD service callbacks on some OEM stacks.
                        if (
                            !serviceDiscoveryStarted &&
                            serviceRequest == null &&
                            !serviceDiscoveryStarting.get() &&
                            !connectionAttemptInProgress &&
                            !frameworkConnectionInProgress
                        ) {
                            startServiceDiscoveryInternal()
                        }

                        // Reconnect checks stay fast. The explicit radio/group
                        // recovery callbacks also schedule an immediate attempt,
                        // so a recovery never has to wait for the next maintenance
                        // cycle to notice that the link is gone.
                        scheduleAutoReconnectAttempt()
                    }
                } catch (t: Throwable) {
                    android.util.Log.w(
                        TAG,
                        "Wi-Fi Direct auto-reconnect loop recovered from an exception: " +
                            (t.message ?: t.javaClass.simpleName)
                    )
                }

                delay(AUTO_RECONNECT_INTERVAL_MS)
            }

            autoReconnectJob = null
        }
    }

    /**
     * Clear retry/backoff state after the underlying Wi-Fi radio or P2P group
     * has been torn down. A new group is a new recovery attempt; do not carry
     * an old failed-connect cooldown into it.
     */
    private fun resetAutoReconnectRecoveryState() {
        autoReconnectFailureCount = 0
        autoReconnectRetryAfterEpochMs = 0L
        autoReconnectFreshPeerAfterEpochMs = 0L
        fastReconnectUntilEpochMs = 0L
    }

    /**
     * Keep a short aggressive reconnect window after Wi-Fi/P2P recovery.
     * During this window we may reuse the persisted Wi-Fi address even before
     * Android repopulates the P2P peer list.
     */
    private fun armFastReconnectRecovery(reason: String) {
        fastReconnectUntilEpochMs =
            System.currentTimeMillis() + FAST_RECONNECT_WINDOW_MS
        android.util.Log.d(
            TAG,
            "Wi-Fi Direct fast-recovery window armed: " +
                reason +
                " for " +
                FAST_RECONNECT_WINDOW_MS +
                "ms"
        )
    }

    /**
     * Fast recovery path used directly by radio/group lifecycle callbacks.
     * Reuse the persisted MAC immediately when DNS-SD has not repopulated the
     * peer list yet, while still keeping the normal deterministic UUID owner.
     */
    private fun scheduleAutoReconnectAfterRadioRecovery(reason: String) {
        if (
            !started.get() ||
            !wifiManager.isWifiEnabled ||
            !hasWifiDirectPermission() ||
            !isLocationModeEnabled() ||
            _connectionInfo.value.groupFormed ||
            connectionAttemptInProgress
        ) {
            return
        }

        managerScope.launch {
            delay(100L)
            if (
                !started.get() ||
                !wifiManager.isWifiEnabled ||
                !hasWifiDirectPermission() ||
                !isLocationModeEnabled() ||
                _connectionInfo.value.groupFormed ||
                connectionAttemptInProgress
            ) {
                return@launch
            }

            kickPeerDiscovery("fast reconnect: " + reason)
            scheduleAutoReconnectAttempt()
        }
    }

    /**
     * Schedule a single fast reconnect attempt. Only one attempt may run at a
     * time; the normal maintenance loop schedules the next attempt after the
     * previous one finishes. This prevents overlapping WifiP2pManager.connect()
     * calls on OEM stacks.
     */
    private fun scheduleAutoReconnectAttempt() {
        if (reconnectAttemptJob?.isActive == true) return
        if (connectionAttemptInProgress) return

        val fastRecoveryActive =
            System.currentTimeMillis() < fastReconnectUntilEpochMs
        if (
            !fastRecoveryActive &&
            System.currentTimeMillis() < autoReconnectRetryAfterEpochMs
        ) {
            return
        }

        val squadIds = squadMembershipStore.squadDeviceIds()

        // Automatic Wi-Fi reconnect is strictly membership-gated. A nearby BLE
        // device that has not been approved (or was explicitly removed) must
        // never resurrect a P2P group in the background.
        val candidateIds = squadIds
            .filter { it != localDeviceId && it !in suppressedAutoReconnectPeerIds }
            .toSet()
        if (candidateIds.isEmpty()) return

        // Deterministic reconnect ownership: only one side initiates a given
        // pair's recovery. The other side remains discoverable and accepts it.
        // After the first failed automatic attempt, require a freshly
        // observed P2P peer before trying again. This prevents an old cached
        // MAC from being retried while Android is still tearing down the
        // previous group.
        val targetPeer = _peers.value
            .asSequence()
            .filter { peer ->
                val id = peer.appDeviceId
                id != null &&
                    id in candidateIds &&
                    localDeviceId.compareTo(id) < 0 &&
                    peer.linkState != RadioLinkState.CONNECTING &&
                    (
                        fastRecoveryActive ||
                            autoReconnectFailureCount == 0 ||
                            peer.lastSeenEpochMs >= autoReconnectFreshPeerAfterEpochMs
                        )
            }
            .maxByOrNull { it.lastSeenEpochMs }

        // Only the very first automatic attempt may use the persisted address.
        // Once Android rejects it, wait for a fresh discovery record instead of
        // hammering the stale address.
        val fallback = if (
            targetPeer == null &&
            (fastRecoveryActive || autoReconnectFailureCount == 0)
        ) {
            candidateIds.asSequence()
                .filter { id -> localDeviceId.compareTo(id) < 0 }
                .mapNotNull { id ->
                    synchronized(appDeviceIdByWifiDeviceAddress) {
                        appDeviceIdByWifiDeviceAddress.entries
                            .firstOrNull { it.value.equals(id, ignoreCase = true) }
                            ?.key
                    }?.let { address -> id to address }
                }
                .firstOrNull()
        } else {
            null
        }

        val targetAddress = targetPeer?.deviceAddress ?: fallback?.second ?: return
        val targetLabel =
            targetPeer?.callsign ?: targetPeer?.deviceName ?: fallback?.first ?: targetAddress

        reconnectAttemptJob = managerScope.launch {
            try {
                android.util.Log.d(
                    TAG,
                    "Wi-Fi Direct auto-reconnect candidate: " +
                        targetLabel + " / " + targetAddress
                )

                val result = runCatching {
                    connect(targetAddress)
                }.getOrElse { error ->
                    TacticalResult.Failure(
                        "Wi-Fi Direct auto-reconnect failed: " +
                            (error.message ?: error.javaClass.simpleName)
                    )
                }

                if (result is TacticalResult.Failure) {
                    if (fastRecoveryActive) {
                        autoReconnectRetryAfterEpochMs = 0L
                        autoReconnectFreshPeerAfterEpochMs = 0L
                        android.util.Log.d(
                            TAG,
                            "Wi-Fi Direct fast-reconnect attempt failed: " +
                                result.error +
                                " (fast recovery still active)"
                        )
                    } else {
                        autoReconnectFailureCount = minOf(
                            autoReconnectFailureCount + 1,
                            AUTO_RECONNECT_MAX_FAILURES
                        )
                        autoReconnectRetryAfterEpochMs =
                            System.currentTimeMillis() +
                                autoReconnectBackoffMs(autoReconnectFailureCount)
                        autoReconnectFreshPeerAfterEpochMs =
                            System.currentTimeMillis()

                        android.util.Log.d(
                            TAG,
                            "Wi-Fi Direct auto-reconnect failed: " +
                                result.error +
                                " retryBackoff=" +
                                autoReconnectBackoffMs(autoReconnectFailureCount) +
                                "ms"
                        )
                    }
                } else {
                    resetAutoReconnectRecoveryState()
                }
            } finally {
                reconnectAttemptJob = null
            }
        }
    }

    private fun kickPeerDiscovery(reason: String) {
        if (
            !started.get() ||
            _connectionInfo.value.groupFormed ||
            !wifiManager.isWifiEnabled ||
            !hasWifiDirectPermission() ||
            !isLocationModeEnabled()
        ) {
            return
        }

        try {
            wifiP2pManager.discoverPeers(
                wifichannel,
                object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        android.util.Log.d(
                            TAG,
                            "Wi-Fi P2P peer discovery kicked: " + reason
                        )
                    }

                    override fun onFailure(reasonCode: Int) {
                        android.util.Log.d(
                            TAG,
                            "Wi-Fi P2P peer discovery kick failed: " +
                                reasonCode + " (" + reason + ")"
                        )
                    }
                }
            )
        } catch (e: SecurityException) {
            android.util.Log.w(
                TAG,
                "Wi-Fi P2P peer discovery permission denied",
                e
            )
        } catch (e: Exception) {
            android.util.Log.d(
                TAG,
                "Wi-Fi P2P peer discovery kick exception: " +
                    (e.message ?: e.javaClass.simpleName)
            )
        }
    }

    private fun scheduleServiceDiscoveryRetry() {
        if (discoveryRetryJob?.isActive == true) return
        if (!started.get() || !wifiManager.isWifiEnabled || !hasWifiDirectPermission()) {
            return
        }

        discoveryRetryJob = managerScope.launch {
            while (started.get() &&
                wifiManager.isWifiEnabled &&
                hasWifiDirectPermission() &&
                !serviceDiscoveryStarted
            ) {
                delay(SERVICE_DISCOVERY_RETRY_MS)
                if (started.get() &&
                    wifiManager.isWifiEnabled &&
                    hasWifiDirectPermission() &&
                    !serviceDiscoveryStarted &&
                    !connectionAttemptInProgress &&
                    !frameworkConnectionInProgress
                ) {
                    startServiceDiscoveryInternal()
                }
            }
            discoveryRetryJob = null
        }
    }

    private fun removeServiceRequest() {
        serviceDiscoveryStarted = false
        serviceDiscoveryStarting.set(false)
        peerRefreshJob?.cancel()
        peerRefreshJob = null

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
        presenceRetryJob?.cancel()
        presenceRetryJob = null
        presenceRegistered = false
        activePresenceServiceInfo = null
        presenceRegistrationInProgress = false
        frameworkConnectionInProgress = false
        _connectionInfo.value = WifiDirectConnectionInfo()
        _peers.value = emptyList()
        _state.value = RadioLinkState.UNAVAILABLE
    }

    private fun refreshConnectionInfo() {
        if (!started.get() || !hasWifiDirectPermission()) return

        try {
            wifiP2pManager.requestConnectionInfo(wifichannel) { info ->
                if (info == null || !info.groupFormed) {
                    val wasGroupFormed = _connectionInfo.value.groupFormed
                    connectTargetDeviceAddress = null
                    _connectionInfo.value = WifiDirectConnectionInfo()
                    localGroupCredentials = null

                    if (_state.value != RadioLinkState.CONNECTING) {
                        _state.value = if (wifiManager.isWifiEnabled) {
                            RadioLinkState.AVAILABLE
                        } else {
                            RadioLinkState.UNAVAILABLE
                        }
                    }

                    // The TXT record also carries group_role. Refresh it to
                    // "none" as soon as the old group disappears so nearby
                    // devices do not keep treating this phone as a member.
                    advertisedDeviceId?.let { id ->
                        advertisedCallsign?.let { callsign ->
                            refreshPresenceWithGroupCredentials(id, callsign)
                        }
                    }

                    if (
                        wasGroupFormed &&
                        wifiManager.isWifiEnabled &&
                        !connectionAttemptInProgress &&
                        !frameworkConnectionInProgress
                    ) {
                        // A lost group starts a completely new recovery cycle.
                        // Clear any previous failed-attempt backoff so the known
                        // squad peer can be retried immediately after discovery
                        // or from its persisted Wi-Fi address.
                        resetAutoReconnectRecoveryState()
                        armFastReconnectRecovery("group lost")
                        kickPeerDiscovery("group lost")
                        scheduleAutoReconnectAfterRadioRecovery("group lost")

                        // Some OEM stacks drop the P2P discovery engine together
                        // with the group without sending a discovery-changed
                        // broadcast. Rebuild our discovery bookkeeping explicitly.
                        removeServiceRequest()
                        managerScope.launch {
                            delay(100L)
                            startServiceDiscoveryInternal()
                        }
                    }

                    return@requestConnectionInfo
                }

                frameworkConnectionInProgress = false

                val baseInfo = WifiDirectConnectionInfo(
                    groupFormed = true,
                    isGroupOwner = info.isGroupOwner,
                    groupOwnerAddress = info.groupOwnerAddress?.hostAddress
                )

                _connectionInfo.value = baseInfo

                // requestConnectionInfo() tells us the role/endpoint. Use
                // requestGroupInfo() for actual P2P device membership so an
                // existing group can accept additional peers safely.
                try {
                    wifiP2pManager.requestGroupInfo(wifichannel) { group ->
                        val members = if (group == null) {
                            emptySet()
                        } else {
                            buildSet {
                                group.owner?.deviceAddress?.let { add(it) }
                                group.clientList.forEach { client ->
                                    add(client.deviceAddress)
                                }
                            }
                        }

                        val ownerDeviceAddress =
                            group?.owner?.deviceAddress
                        val ownerAppDeviceId = ownerDeviceAddress?.let { address ->
                            synchronized(appDeviceIdByWifiDeviceAddress) {
                                appDeviceIdByWifiDeviceAddress[address.lowercase()]
                            } ?: _peers.value.firstOrNull {
                                it.deviceAddress.equals(address, ignoreCase = true)
                            }?.appDeviceId
                        }

                        if (
                            ownerDeviceAddress != null &&
                            ownerAppDeviceId != null
                        ) {
                            rememberAppDeviceId(
                                wifiDeviceAddress = ownerDeviceAddress,
                                appDeviceId = ownerAppDeviceId
                            )
                        }

                        _connectionInfo.value = baseInfo.copy(
                            groupOwnerDeviceAddress = ownerDeviceAddress,
                            groupOwnerAppDeviceId = ownerAppDeviceId,
                            groupMemberDeviceAddresses = members
                        )

                        if (group != null) {
                            val networkName = group.networkName
                            val passphrase = group.passphrase
                            if (!networkName.isNullOrBlank() && !passphrase.isNullOrBlank()) {
                                localGroupCredentials = GroupCredentials(
                                    networkName = networkName,
                                    passphrase = passphrase
                                )
                            }
                        }

                        // Re-advertise the current role even when Android has
                        // not supplied group credentials yet. This is what
                        // makes member-vs-head discovery deterministic for a
                        // newly formed group.
                        advertisedDeviceId?.let { id ->
                            advertisedCallsign?.let { callsign ->
                                refreshPresenceWithGroupCredentials(id, callsign)
                            }
                        }

                        members.forEach { address ->
                            markPeerState(address, RadioLinkState.CONNECTED)
                        }

                        _state.value = RadioLinkState.CONNECTED

                        android.util.Log.d(
                            TAG,
                            "Wi-Fi Direct group formed: isGO=" + info.isGroupOwner +
                                " members=" + members.joinToString(",")
                        )
                    }
                } catch (_: SecurityException) {
                    _state.value = RadioLinkState.CONNECTED
                } catch (e: Exception) {
                    android.util.Log.d(
                        TAG,
                        "Wi-Fi Direct group info refresh failed: " +
                            (e.message ?: e.javaClass.simpleName)
                    )
                    _state.value = RadioLinkState.CONNECTED
                }
            }
        } catch (_: SecurityException) {
            _state.value = RadioLinkState.FAILED
        }
    }

    override fun isWifiEnabled(): Boolean = wifiManager.isWifiEnabled

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
                            connectTargetDeviceAddress = null
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

    override suspend fun connectByAppDeviceId(
        deviceId: String
    ): TacticalResult<Unit> {
        var peer = _peers.value.firstOrNull {
            it.appDeviceId.equals(deviceId, ignoreCase = true)
        }

        // After a P2P group is torn down, Android can briefly publish an empty
        // peer list even though the same nearby device is still reachable.
        // We persist the app-id -> Wi-Fi P2P address mapping so a manual
        // Add-to-Squad can recover without losing the identity during the
        // discovery gap.
        val persistedAddress = synchronized(appDeviceIdByWifiDeviceAddress) {
            appDeviceIdByWifiDeviceAddress.entries
                .firstOrNull { it.value.equals(deviceId, ignoreCase = true) }
                ?.key
        }

        val targetAddress = peer?.deviceAddress ?: persistedAddress
            ?: return TacticalResult.Failure(
                "Wi-Fi Direct peer is no longer known"
            )

        if (peer == null) {
            android.util.Log.d(
                TAG,
                "Wi-Fi Direct reconnect waiting for rediscovery of " +
                    deviceId + " at " + targetAddress
            )

            // A manual re-add can happen in the short interval where Android
            // has torn down the old P2P group but has not repopulated its peer
            // cache yet. Calling connect() during that interval is rejected by
            // some OEM stacks (reason 0), even though discovery finds the peer
            // moments later. Reuse the cached MAC, restart discovery if needed,
            // and wait briefly for Android to publish the peer before connecting.
            startServiceDiscoveryInternal()
            kickPeerDiscovery("manual add waiting for peer rediscovery")

            peer = withTimeoutOrNull<WifiDirectPeer>(
                MANUAL_ADD_DISCOVERY_TIMEOUT_MS
            ) {
                var discovered: WifiDirectPeer? = null
                while (discovered == null) {
                    discovered = _peers.value.firstOrNull {
                        it.appDeviceId.equals(deviceId, ignoreCase = true) ||
                            it.deviceAddress.equals(targetAddress, ignoreCase = true)
                    }
                    if (discovered == null) {
                        delay(100L)
                    }
                }
                discovered
            }

            if (peer == null) {
                android.util.Log.w(
                    TAG,
                    "Wi-Fi Direct manual add could not rediscover " +
                        deviceId + " within " +
                        MANUAL_ADD_DISCOVERY_TIMEOUT_MS + "ms"
                )
                return TacticalResult.Failure(
                    "Wi-Fi Direct peer is not currently discoverable"
                )
            }
        }

        val resolvedAddress = peer.deviceAddress
        return connect(resolvedAddress)
    }

    override fun noteGroupOwnerAppDeviceId(deviceId: String) {
        if (deviceId.isBlank()) return

        val info = _connectionInfo.value
        if (info.groupFormed && !info.isGroupOwner) {
            _connectionInfo.value = info.copy(
                groupOwnerAppDeviceId = deviceId
            )
            android.util.Log.d(
                TAG,
                "Resolved Wi-Fi group-owner app identity from transport hello: " +
                    deviceId
            )
        }
    }

    override fun noteBlePeerConnected(deviceId: String) {
        if (deviceId.isBlank() || deviceId == localDeviceId) return

        // BLE proximity alone must not create a Wi-Fi Direct group. Wi-Fi is
        // an automatic transport upgrade for an already-authorized squad
        // relationship; an unapproved/removed peer stays BLE-only until the
        // squad decision is made.
        if (deviceId !in squadMembershipStore.squadDeviceIds()) {
            android.util.Log.d(
                TAG,
                "BLE peer is not in squad; deferring automatic Wi-Fi upgrade: " +
                    deviceId
            )
            return
        }

        android.util.Log.d(
            TAG,
            "BLE squad peer registered for automatic Wi-Fi upgrade: " + deviceId
        )
        if (
            started.get() &&
            wifiManager.isWifiEnabled &&
            !_connectionInfo.value.groupFormed
        ) {
            armFastReconnectRecovery("BLE squad peer")
            kickPeerDiscovery("BLE squad peer")
            scheduleAutoReconnectAttempt()
        }
    }

    override fun suppressAutoReconnectTo(deviceId: String) {
        if (deviceId.isBlank() || deviceId == localDeviceId) return
        suppressedAutoReconnectPeerIds.add(deviceId)
        android.util.Log.d(
            TAG,
            "Wi-Fi auto-reconnect suppressed for " + deviceId
        )
    }

    override fun allowAutoReconnectTo(deviceId: String) {
        val wasSuppressed = suppressedAutoReconnectPeerIds.remove(deviceId)

        // Re-authorization should not immediately reuse stale P2P state from
        // the just-removed group. Only re-arm the cooldown when this call
        // actually transitions the peer from suppressed -> allowed.
        if (wasSuppressed) {
            autoReconnectFailureCount = 0
            autoReconnectFreshPeerAfterEpochMs = 0L
            autoReconnectRetryAfterEpochMs =
                System.currentTimeMillis() + AUTO_RECONNECT_REARM_DELAY_MS
        }

        android.util.Log.d(
            TAG,
            "Wi-Fi auto-reconnect allowed for " + deviceId +
                if (wasSuppressed) {
                    " (rearmed after cooldown)"
                } else {
                    ""
                }
        )
    }

    override suspend fun connect(deviceAddress: String): TacticalResult<Unit> =
        connectMutex.withLock {
            connectInternal(deviceAddress)
        }

    private suspend fun connectInternal(deviceAddress: String): TacticalResult<Unit> {
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

        val wasAlreadyInGroup = _connectionInfo.value.groupFormed
        if (
            wasAlreadyInGroup &&
            _connectionInfo.value.groupMemberDeviceAddresses.any {
                it.equals(cleanedAddress, ignoreCase = true)
            }
        ) {
            // Any successful P2P connection proves the Wi-Fi upgrade
            // state machine has recovered. Clear automatic retry backoff so a
            // later disconnect starts from a clean state.
            autoReconnectFailureCount = 0
            autoReconnectRetryAfterEpochMs = 0L
            autoReconnectFreshPeerAfterEpochMs = 0L

            markPeerState(cleanedAddress, RadioLinkState.CONNECTED)
            return TacticalResult.Success(Unit)
        }

        if (wasAlreadyInGroup) {
            android.util.Log.w(
                TAG,
                "Refusing Wi-Fi Direct connection to " +
                    cleanedAddress +
                    " because this phone is already in another P2P group"
            )
            return TacticalResult.Failure(
                "Already connected to a Wi-Fi Direct group; the new device must join that group"
            )
        }

        connectTargetDeviceAddress = cleanedAddress
        connectionAttemptInProgress = true
        frameworkConnectionInProgress = true
        val connectFailureBefore = connectFailureSequence.get()

        // Keep Android's normal peer discovery session alive while starting
        // the connection. Some OEM stacks invalidate the discovered peer
        // immediately when stopPeerDiscovery() is called, causing connect()
        // to be rejected with reason 0.
        //
        // Remove only the DNS-SD service request. This pauses our application
        // service-discovery bookkeeping but leaves Android's P2P peer cache
        // intact for the connect() call.
        removeServiceRequest()
        _state.value = RadioLinkState.CONNECTING
        _peers.value = _peers.value.map {
            if (it.deviceAddress.equals(cleanedAddress, ignoreCase = true)) {
                it.copy(linkState = RadioLinkState.CONNECTING)
            } else {
                it
            }
        }

        try {
            val accepted = suspendCancellableCoroutine<Boolean> { continuation ->
                // Use the exact ordinary peer-to-peer connection path from
                // the Android reference implementation. Do not add PCC mode,
                // WPS/PBC, persistent mode, SSID, passphrase, or group-owner
                // settings until the baseline two-phone negotiation is proven.
                val advertisedGroupCredentials =
                    synchronized(groupCredentialsByDeviceAddress) {
                        groupCredentialsByDeviceAddress[cleanedAddress.lowercase()]
                    }
                val discoveredPeer = _peers.value.firstOrNull {
                    it.deviceAddress.equals(cleanedAddress, ignoreCase = true)
                }
                val canJoinAdvertisedGroup =
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                        discoveredPeer?.isGroupOwner == true &&
                        advertisedGroupCredentials != null

                val config = if (canJoinAdvertisedGroup) {
                    android.util.Log.d(
                        TAG,
                        "Joining existing iTantra group for " +
                            cleanedAddress + " using advertised group credentials"
                    )
                    WifiP2pConfig.Builder()
                        .setNetworkName(advertisedGroupCredentials!!.networkName)
                        .setPassphrase(advertisedGroupCredentials.passphrase)
                        .build()
                } else {
                    WifiP2pConfig().apply {
                        this.deviceAddress = cleanedAddress
                    }
                }

                try {
                    wifiP2pManager.connect(
                        wifichannel,
                        config,
                        object : WifiP2pManager.ActionListener {
                            override fun onSuccess() {
                                android.util.Log.d(
                                    TAG,
                                    "Wi-Fi Direct connect request accepted by Android for " +
                                        cleanedAddress
                                )
                                if (continuation.isActive) continuation.resume(true)
                            }

                            override fun onFailure(reason: Int) {
                                android.util.Log.w(
                                    TAG,
                                    "Wi-Fi Direct connect request rejected by Android for " +
                                        cleanedAddress + ": " + reason
                                )
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

            val established = withTimeoutOrNull(CONNECT_TIMEOUT_MS) {
                while (true) {
                    if (!wasAlreadyInGroup && _connectionInfo.value.groupFormed) {
                        return@withTimeoutOrNull true
                    }

                    if (
                        wasAlreadyInGroup &&
                        _connectionInfo.value.groupFormed &&
                        _connectionInfo.value.groupMemberDeviceAddresses.any {
                            it.equals(cleanedAddress, ignoreCase = true)
                        }
                    ) {
                        return@withTimeoutOrNull true
                    }

                    if (connectFailureSequence.get() != connectFailureBefore) {
                        return@withTimeoutOrNull false
                    }

                    delay(50L)
                }
            } == true

            if (!established) {
                if (
                    connectFailureSequence.get() == connectFailureBefore &&
                    !wasAlreadyInGroup &&
                    !_connectionInfo.value.groupFormed
                ) {
                    runCatching {
                        wifiP2pManager.cancelConnect(
                            wifichannel,
                            null
                        )
                    }
                }

                _state.value = RadioLinkState.FAILED
                markPeerState(cleanedAddress, RadioLinkState.FAILED)
                return TacticalResult.Failure(
                    if (wasAlreadyInGroup) {
                        "Wi-Fi Direct peer did not join the existing group"
                    } else if (connectFailureSequence.get() == connectFailureBefore) {
                        "Wi-Fi Direct connection timed out"
                    } else {
                        "Wi-Fi Direct group creation failed"
                    }
                )
            }

            resetAutoReconnectRecoveryState()
            markPeerState(cleanedAddress, RadioLinkState.CONNECTED)
            return TacticalResult.Success(Unit)
        } finally {
            if (connectTargetDeviceAddress.equals(cleanedAddress, ignoreCase = true)) {
                connectTargetDeviceAddress = null
            }
            connectionAttemptInProgress = false
            frameworkConnectionInProgress = false

            // Resume normal discovery after our explicit negotiation attempt
            // has ended, whether it succeeded or failed.
            if (
                started.get() &&
                wifiManager.isWifiEnabled &&
                hasWifiDirectPermission() &&
                !_connectionInfo.value.groupFormed
            ) {
                managerScope.launch {
                    delay(300L)
                    startServiceDiscoveryInternal()
                }
            }
        }
    }

    private fun rememberAppDeviceId(
        wifiDeviceAddress: String,
        appDeviceId: String
    ) {
        val normalizedAddress = wifiDeviceAddress.trim().lowercase()
        if (normalizedAddress.isBlank() || appDeviceId.isBlank()) return

        synchronized(appDeviceIdByWifiDeviceAddress) {
            appDeviceIdByWifiDeviceAddress[normalizedAddress] = appDeviceId
        }

        wifiIdentityPreferences.edit()
            .putString(WIFI_IDENTITY_PREFIX + normalizedAddress, appDeviceId)
            .apply()
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

    private fun registerModernP2pListener() {
        if (wifiP2pListenerRegistered || Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            return
        }

        val listener = wifiP2pListener ?: return
        runCatching {
            wifiP2pManager.registerWifiP2pListener(
                context.mainExecutor,
                listener
            )
            wifiP2pListenerRegistered = true
            android.util.Log.d(
                TAG,
                "Wi-Fi P2P modern listener registered"
            )
        }.onFailure { error ->
            android.util.Log.w(
                TAG,
                "Wi-Fi P2P modern listener registration failed: " +
                    (error.message ?: error.javaClass.simpleName)
            )
        }
    }

    private fun unregisterModernP2pListener() {
        if (!wifiP2pListenerRegistered || Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            return
        }

        val listener = wifiP2pListener ?: return
        runCatching {
            wifiP2pManager.unregisterWifiP2pListener(listener)
        }
        wifiP2pListenerRegistered = false
    }

    private fun registerReceiverOnce() {
        if (receiverRegistered) return

        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                addAction(WifiP2pManager.ACTION_WIFI_P2P_REQUEST_RESPONSE_CHANGED)
            }
            addAction(WifiP2pManager.WIFI_P2P_DISCOVERY_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
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

    private data class GroupCredentials(
        val networkName: String,
        val passphrase: String
    )

    companion object {
        private const val TAG = "AndroidWifiDirect"
        private const val CONNECT_TIMEOUT_MS = 30_000L
        private const val SERVICE_DISCOVERY_RETRY_MS = 5_000L
        private const val PRESENCE_RETRY_MS = 5_000L
        private const val AUTO_RECONNECT_INTERVAL_MS = 750L
        private const val FAST_RECONNECT_WINDOW_MS = 15_000L
        private const val AUTO_RECONNECT_REARM_DELAY_MS = 2_000L
        private const val AUTO_RECONNECT_MAX_FAILURES = 3
        private const val PEER_REFRESH_MS = 1_000L
        private const val MANUAL_ADD_DISCOVERY_TIMEOUT_MS = 6_000L

        private fun autoReconnectBackoffMs(failureCount: Int): Long =
            when (failureCount) {
                1 -> 2_000L
                2 -> 4_000L
                else -> 8_000L
            }
        private const val WIFI_IDENTITY_PREFIX = "app_id_"
    }
}