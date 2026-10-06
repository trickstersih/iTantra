package com.tactical.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tactical.app.di.DeviceIdentityStore
import com.tactical.app.di.LocalAppDataStore
import com.tactical.app.di.PttModePreferences
import com.tactical.app.di.UiLanguagePreferences
import com.tactical.app.di.StoredPairedDevice
import com.tactical.app.di.StoredReceivedMessage
import com.tactical.app.di.StoredSentMessage
import com.tactical.app.service.MeshSquadControlCoordinator
import com.tactical.domain.identity.DeviceId
import com.tactical.domain.identity.DeviceNode
import com.tactical.domain.identity.LinkType
import com.tactical.domain.packet.EmergencyPacket
import com.tactical.domain.packet.TextPacket
import com.tactical.domain.packet.Severity
import com.tactical.domain.result.TacticalResult
import com.tactical.domain.audio.AudioConfig
import com.tactical.platform.api.audio.AudioRecorder
import com.tactical.platform.api.haptics.HapticEngine
import com.tactical.platform.api.speech.SpeechToText
import com.tactical.platform.speech.mms.MmsTtsEngine
import com.tactical.platform.speech.mms.MmsTtsLanguage
import com.tactical.platform.speech.SpeechLanguagePreferences
import com.tactical.platform.speech.RoutingSpeechToText
import com.tactical.ptt.controller.DefaultPttController
import com.tactical.ptt.controller.PttTransmission
import com.tactical.ptt.controller.PttTransmissionStatus
import com.tactical.ptt.controller.PttController
import com.tactical.ptt.feedback.PatternedHapticFeedback
import com.tactical.ptt.relay.PttMeshDispatcher
import com.tactical.ptt.relay.PttPacketBuilder
import com.tactical.ptt.session.SessionState
import com.tactical.engine.discovery.proximity.RssiProximityEstimator
import com.tactical.engine.discovery.service.DefaultDiscoveryService
import com.tactical.engine.discovery.service.DiscoveryService
import com.tactical.engine.mesh.service.MeshService
import com.tactical.platform.api.ble.BleConnectionManager
import com.tactical.platform.api.ble.BleLinkState
import com.tactical.platform.api.radio.RadioTransport
import com.tactical.platform.api.wifi.WifiDirectManager
import com.tactical.platform.api.squad.SquadMembershipStore
import com.tactical.domain.identity.RadioLinkState
import com.tactical.domain.identity.RadioType
import com.tactical.platform.api.ble.SquadRequest
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.text.SimpleDateFormat
import java.util.Date
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

data class PeerNodeUi(
    val deviceAddress: String,
    val callsign: String,
    val isConnected: Boolean,
    val distanceText: String,
    val signalBars: Int,
    val linkText: String,
    val bleState: BleLinkState = BleLinkState.AVAILABLE,
    val wifiDirectState: RadioLinkState = RadioLinkState.UNAVAILABLE,
    /**
     * Discovery path from the original sender to this device. For a relayed
     * packet the last element is the immediate relay we must still be able
     * to reach physically before reporting the route as connected.
     */
    val path: List<String> = emptyList()
)

data class ChatMessageUi(
    val sender: String,
    val text: String,
    val timestampText: String,
    val statusText: String,
    val isAlert: Boolean = false,
    val isVoice: Boolean = false,
    val isCallMode: Boolean = false,
    val emergencyData: EmergencyAlertData? = null,
    val timestampEpochMs: Long = 0L,
    // Local time used only for ordering messages in the conversation.
    // Unlike timestampEpochMs, this never comes from another device.
    val conversationOrderEpochMs: Long = 0L
)

data class EmergencyAlertData(
    val sender: String,
    val timestampText: String,
    val severity: String,
    val message: String,
    val languageCode: String,
    val locationLatitude: Double? = null,
    val locationLongitude: Double? = null,
    val locationAccuracyMeters: Float? = null
) {
    val hasLocation: Boolean
        get() = locationLatitude != null && locationLongitude != null
}

data class NetworkMetrics(
    val rttMs: Int = 0,
    val hopCount: Int = 0,
    val packetLossPercent: Int = 0,
    val transportName: String = "BLE / Wi-Fi Direct"
)

data class PttTransmissionUi(
    val id: String,
    val text: String,
    val timestampEpochMs: Long,
    val statusText: String
)

data class MainUiState(
    val username: String = "",
    val selectedLanguageCode: String = "hi",
    val selectedLanguage: String = "हिन्दी",
    val uiLanguageCode: String = "en",
    val languageLoadingCode: String? = null,
    val languageLoadError: String? = null,
    val squadPeers: List<PeerNodeUi> = emptyList(),
    val availablePeers: List<PeerNodeUi> = emptyList(),
    val messages: List<ChatMessageUi> = emptyList(),
    val sentMessages: List<ChatMessageUi> = emptyList(),
    val receivedMessages: List<ChatMessageUi> = emptyList(),
    val networkMetrics: NetworkMetrics = NetworkMetrics(),
    val isScanning: Boolean = false,
    val pttSessionState: SessionState = SessionState.IDLE,
    val pttLastTranscription: String? = null,
    val pttEnabled: Boolean = true,
    val pttContinuousSession: Boolean = false,
    val pttTransmissionHistory: List<PttTransmissionUi> = emptyList(),
    val unreadMessageCount: Int = 0,
    val emergencyComposerVisible: Boolean = false,
    val emergencyRecording: Boolean = false,
    val emergencySending: Boolean = false,
    val emergencyTranscription: String = "",
    val emergencyError: String? = null,
    val pendingSquadRequest: SquadRequest? = null,
    val pendingSquadRequestCount: Int = 0,
    val showWifiMultipleRequestWarning: Boolean = false,
    // Wi-Fi Direct group role/topology exposed to the Squad screen.
    val wifiDirectGroupFormed: Boolean = false,
    val wifiDirectIsGroupOwner: Boolean = false,
    val wifiDirectGroupOwnerDeviceAddress: String? = null,
    val wifiDirectGroupOwnerAppDeviceId: String? = null,
    val wifiDirectDiscoveredGroupHeadIds: Set<String> = emptySet(),
    val wifiDirectDiscoveredGroupMemberIds: Set<String> = emptySet(),
    val respondingSquadRequestId: String? = null,
    val squadRequestError: String? = null,
    val squadNotification: String? = null,
    val ttsPlaybackMode: com.tactical.platform.speech.mms.MmsTtsPlaybackMode =
        com.tactical.platform.speech.mms.MmsTtsPlaybackMode.OVERLAPPING
)

@HiltViewModel
class MainViewModel @Inject constructor(
    private val discoveryService: DiscoveryService,
    private val meshService: MeshService,
    private val identityStore: DeviceIdentityStore,
    private val bleConnectionManager: BleConnectionManager,
    private val wifiDirectManager: WifiDirectManager,
    private val radioTransport: RadioTransport,
    private val squadMembershipStore: SquadMembershipStore,
    private val audioRecorder: AudioRecorder,
    private val speechToText: SpeechToText,
    private val hapticEngine: HapticEngine,
    private val mmsTtsEngine: MmsTtsEngine,
    private val speechLanguagePreferences: SpeechLanguagePreferences,
    private val routingSpeechToText: RoutingSpeechToText,
    private val localAppDataStore: LocalAppDataStore,
    private val pttModePreferences: PttModePreferences,
    private val uiLanguagePreferences: UiLanguagePreferences,
    private val mmsTtsPlaybackPreferences: com.tactical.platform.speech.mms.MmsTtsPlaybackPreferences,
    private val mmsTtsPlaybackCoordinator: com.tactical.platform.speech.mms.MmsTtsPlaybackCoordinator,
    private val messageNotificationNotifier: com.tactical.app.service.MessageNotificationNotifier,
    private val meshSquadControlCoordinator: MeshSquadControlCoordinator
) : ViewModel() {

    // Must be initialized before _uiState because storedPeerToUi() uses it
    // while the initial state is being constructed.
    private val estimator = RssiProximityEstimator()

    private val _uiState = MutableStateFlow(
        MainUiState(
            username = identityStore.callsign,
            selectedLanguageCode = speechLanguagePreferences.selectedLanguageCode,
            selectedLanguage = displayLanguageName(speechLanguagePreferences.selectedLanguageCode),
            uiLanguageCode = uiLanguagePreferences.selectedLanguageCode,
            squadPeers = localAppDataStore.loadPairedDevices()
                .filter { it.deviceId in squadMembershipStore.squadDeviceIds() }
                .map(::storedPeerToUi),
            receivedMessages = localAppDataStore.loadReceivedMessages()
                .asReversed()
                .map(::storedMessageToUi),
            messages = localAppDataStore.loadReceivedMessages()
                .asReversed()
                .map(::storedMessageToUi),
            sentMessages = localAppDataStore.loadSentMessages()
                .map(::storedSentMessageToUi),
            pttEnabled = pttModePreferences.isPttEnabled,
            unreadMessageCount = localAppDataStore.unreadMessageCount(),
            ttsPlaybackMode = mmsTtsPlaybackPreferences.playbackMode
        )
    )
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()
    private var healthJob: Job? = null
    private val observedPeerIds = mutableSetOf<String>()
    private val recentlyRemovedFromSquadIds =
        ConcurrentHashMap.newKeySet<String>()

    // A Wi-Fi head can remove a member whose only path to another squad
    // member was head-relayed. Keep those relay paths invalid until a fresh
    // Wi-Fi route is observed or the peer reconnects directly. This state is
    // deliberately separate from BLE, so an independent BLE link remains live.
    private val invalidatedWifiRelayRoutes =
        ConcurrentHashMap<String, Long>()
    private val invalidatedWifiRelayPeerIds =
        ConcurrentHashMap.newKeySet<String>()
    // Tracks membership across the whole app lifecycle so a remote REMOVE is
    // treated the same as a locally initiated removal for Home-screen retention.
    private var lastObservedSquadIds =
        squadMembershipStore.squadDeviceIds().toSet()
    private val reconnectJobs = mutableMapOf<String, Job>()
    private val pttController: PttController = DefaultPttController(
        deviceId = DeviceId(identityStore.deviceIdValue),
        audioRecorder = audioRecorder,
        speechToText = speechToText,
        packetBuilder = PttPacketBuilder(),
        meshDispatcher = PttMeshDispatcher(meshService),
        hapticFeedback = PatternedHapticFeedback(hapticEngine),
        scope = viewModelScope,
        releaseGraceMs = 5000L
    )

    private var lastHandledPttSessionId: String? = null
    private val continuousTransmissionMessages = mutableMapOf<String, ChatMessageUi>()
    private var resumeContinuousAfterEmergency = false
    private var wifiMultipleRequestWarningDismissed = false

    private val emergencyTrigger =
        com.tactical.emergency.trigger.HoldPanicTrigger(viewModelScope)

    private var emergencyRecordingJob: Job? = null

    private val emergencyMessageBuilder =
        com.tactical.emergency.message.EmergencyMessageBuilder()

    private val emergencyBroadcaster =
        com.tactical.emergency.broadcast.RadiusEmergencyBroadcaster(meshService)

    init {
        // Keep the unread badge synchronized with messages persisted by either
        // the Activity/ViewModel or the foreground mesh service. This avoids
        // losing the badge when a SharedFlow packet is consumed before the
        // Activity's collector is ready.
        viewModelScope.launch {
            localAppDataStore.receivedMessagesChanged.collect {
                // The foreground service persists incoming messages even when
                // the Messages screen is already visible. Reload the list and
                // unread count immediately so navigation is not required.
                refreshReceivedMessages()
            }
        }

        viewModelScope.launch {
            emergencyTrigger.state().collect { triggerState ->
                if (triggerState == com.tactical.emergency.trigger.PanicTriggerState.TRIGGERED) {
                    startEmergencyRecording()
                }
            }
        }

        viewModelScope.launch {
            pttController.state().collect { ptt ->
                _uiState.update {
                    it.copy(
                        pttSessionState = ptt.sessionState,
                        pttLastTranscription = ptt.lastTranscription,
                        pttContinuousSession = ptt.continuousSession,
                        pttTransmissionHistory = if (ptt.continuousSession) {
                            ptt.transmissions.map(::pttTransmissionToUi)
                        } else {
                            it.pttTransmissionHistory
                        }
                    )
                }

                if (ptt.continuousSession) {
                    syncContinuousTransmissions(ptt.transmissions)
                    return@collect
                }

                val sessionId = ptt.sessionId
                val text = ptt.lastTranscription?.trim().orEmpty()
                val result = ptt.lastResult

                if (
                    ptt.sessionState == SessionState.IDLE &&
                    !sessionId.isNullOrBlank() &&
                    sessionId != lastHandledPttSessionId &&
                    text.isNotBlank() &&
                    result != null
                ) {
                    lastHandledPttSessionId = sessionId
                    val status = when (result) {
                        is TacticalResult.Success -> "Sent"
                        is TacticalResult.Failure -> "Queued"
                    }
                    val localSendTime = System.currentTimeMillis()
                    val message = ChatMessageUi(
                        sender = "YOU",
                        text = text,
                        timestampText = "Just now",
                        statusText = status,
                        isVoice = true,
                        timestampEpochMs = localSendTime,
                        conversationOrderEpochMs = localSendTime
                    )
                    localAppDataStore.saveSentMessage(
                        storedSentMessage(message)
                    )
                    _uiState.update {
                        it.copy(
                            messages = listOf(message) + it.messages,
                            sentMessages = listOf(message) + it.sentMessages,
                            pttTransmissionHistory = listOf(
                                PttTransmissionUi(
                                    id = sessionId,
                                    text = text,
                                    timestampEpochMs = localSendTime,
                                    statusText = status
                                )
                            ) + it.pttTransmissionHistory.take(19)
                        )
                    }
                }
            }
        }

        viewModelScope.launch {
            startDiscovery()
        }

        // Wi-Fi Direct service discovery also tells us which advertised
        // devices are group heads. Keep this separate from connection state so
        // the Available Devices list can offer only a head when a peer is
        // already part of a Wi-Fi Direct group.
        viewModelScope.launch {
            wifiDirectManager.discoverPeers().collect { peers ->
                val headIds = peers
                    .filter { it.isGroupOwner == true }
                    .mapNotNull { it.appDeviceId }
                    .toSet()
                val memberIds = peers
                    .filter { it.isGroupOwner == false }
                    .mapNotNull { it.appDeviceId }
                    .toSet()

                _uiState.update { state ->
                    if (
                        state.wifiDirectDiscoveredGroupHeadIds == headIds &&
                        state.wifiDirectDiscoveredGroupMemberIds == memberIds
                    ) {
                        state
                    } else {
                        state.copy(
                            wifiDirectDiscoveredGroupHeadIds = headIds,
                            wifiDirectDiscoveredGroupMemberIds = memberIds,
                            availablePeers = state.availablePeers.filter {
                                it.deviceAddress !in memberIds ||
                                    it.deviceAddress in recentlyRemovedFromSquadIds
                            }
                        )
                    }
                }
            }
        }

        squadMembershipStore.squadDeviceIds().forEach { pairedId ->
            if (observedPeerIds.add(pairedId)) {
                observePeerState(pairedId)
            }
        }
        viewModelScope.launch {
            squadMembershipStore.squadDeviceIds().forEach { pairedId ->
                runCatching { bleConnectionManager.reconnectSquadMember(pairedId) }
            }
        }

        viewModelScope.launch {
            bleConnectionManager.peerIdentityUpdates().collect { update ->
                localAppDataStore.loadPairedDevices()
                    .firstOrNull { it.deviceId == update.deviceId }
                    ?.let { stored ->
                        localAppDataStore.savePairedDevice(
                            stored.copy(callsign = update.callsign)
                        )
                    }

                _uiState.update { state ->
                    state.copy(
                        squadPeers = state.squadPeers.map { peer ->
                            if (peer.deviceAddress == update.deviceId) {
                                peer.copy(callsign = update.callsign)
                            } else peer
                        },
                        availablePeers = state.availablePeers.map { peer ->
                            if (peer.deviceAddress == update.deviceId) {
                                peer.copy(callsign = update.callsign)
                            } else peer
                        }
                    )
                }
            }
        }

        viewModelScope.launch {
            combine(
                bleConnectionManager.pendingSquadRequests(),
                meshSquadControlCoordinator.pendingRequests
            ) { direct, mesh ->
                (direct + mesh).distinctBy { it.deviceId }
            }.collect { requests ->
                val groupFormed = wifiDirectManager.connectionInfo().value.groupFormed

                if (requests.size <= 1) {
                    wifiMultipleRequestWarningDismissed = false
                }

                _uiState.update {
                    it.copy(
                        pendingSquadRequest = requests.firstOrNull(),
                        pendingSquadRequestCount = requests.size,
                        showWifiMultipleRequestWarning =
                            requests.size > 1 &&
                                groupFormed &&
                                !wifiMultipleRequestWarningDismissed,
                        squadRequestError = null
                    )
                }
            }
        }

        // Re-evaluate the warning when a Wi-Fi Direct group forms/disappears
        // after pending requests have already arrived.
        viewModelScope.launch {
            wifiDirectManager.connectionInfo().collect { info ->
                val requests = _uiState.value.pendingSquadRequestCount

                if (requests <= 1) {
                    wifiMultipleRequestWarningDismissed = false
                }

                _uiState.update {
                    it.copy(
                        wifiDirectGroupFormed = info.groupFormed,
                        wifiDirectIsGroupOwner = info.isGroupOwner,
                        wifiDirectGroupOwnerDeviceAddress = info.groupOwnerDeviceAddress,
                        wifiDirectGroupOwnerAppDeviceId = info.groupOwnerAppDeviceId,
                        showWifiMultipleRequestWarning =
                            requests > 1 &&
                                info.groupFormed &&
                                !wifiMultipleRequestWarningDismissed
                    )
                }
            }
        }

        // On some OEMs the P2P group is reported as connected before the
        // DNS-SD record that maps the Wi-Fi head's device address to its
        // stable iTantra UUID arrives. Keep checking the already-known group
        // state briefly so the Squad HEAD badge appears as soon as Android has
        // assigned the owner identity, without starting a new Wi-Fi operation.
        viewModelScope.launch {
            while (true) {
                delay(750L)
                val info = wifiDirectManager.connectionInfo().value
                val state = _uiState.value

                if (!info.groupFormed) {
                    if (
                        state.wifiDirectGroupFormed ||
                        state.wifiDirectGroupOwnerAppDeviceId != null
                    ) {
                        _uiState.update {
                            it.copy(
                                wifiDirectGroupFormed = false,
                                wifiDirectGroupOwnerDeviceAddress = null,
                                wifiDirectGroupOwnerAppDeviceId = null
                            )
                        }
                    }
                    continue
                }

                val candidateHeadIds = state.wifiDirectDiscoveredGroupHeadIds
                    .filter { it in squadMembershipStore.squadDeviceIds() }

                val resolvedOwnerAppDeviceId =
                    info.groupOwnerAppDeviceId
                        ?: candidateHeadIds.singleOrNull()

                if (
                    state.wifiDirectGroupFormed != info.groupFormed ||
                    state.wifiDirectIsGroupOwner != info.isGroupOwner ||
                    state.wifiDirectGroupOwnerDeviceAddress !=
                        info.groupOwnerDeviceAddress ||
                    state.wifiDirectGroupOwnerAppDeviceId !=
                        resolvedOwnerAppDeviceId
                ) {
                    _uiState.update {
                        it.copy(
                            wifiDirectGroupFormed = info.groupFormed,
                            wifiDirectIsGroupOwner = info.isGroupOwner,
                            wifiDirectGroupOwnerDeviceAddress =
                                info.groupOwnerDeviceAddress,
                            wifiDirectGroupOwnerAppDeviceId =
                                resolvedOwnerAppDeviceId
                        )
                    }
                }
            }
        }

        viewModelScope.launch {
            meshSquadControlCoordinator.membershipChanged.collect {
                val currentSquadIds = squadMembershipStore.squadDeviceIds().toSet()
                val removedIds = lastObservedSquadIds - currentSquadIds
                if (removedIds.isNotEmpty()) {
                    recentlyRemovedFromSquadIds.addAll(removedIds)
                }
                lastObservedSquadIds = currentSquadIds
                refreshSquadPeers()
            }
        }

        viewModelScope.launch {
            meshSquadControlCoordinator.removalNotices.collect { callsign ->
                val message = "REMOVED FROM SQUAD BY " + callsign
                _uiState.update { it.copy(squadNotification = message) }
                delay(5000L)
                _uiState.update { state ->
                    if (state.squadNotification == message) {
                        state.copy(squadNotification = null)
                    } else {
                        state
                    }
                }
            }
        }

        viewModelScope.launch {
            meshSquadControlCoordinator.wifiRemovalNotices.collect { notice ->
                // Block the exact Wi-Fi relationships named by the head. This
                // does not touch BLE and only affects background P2P reconnects.
                invalidatedWifiRelayPeerIds.add(notice.removedDeviceId)

                notice.blockedPeerIds.forEach { peerId ->
                    wifiDirectManager.suppressAutoReconnectTo(peerId)
                    invalidatedWifiRelayPeerIds.add(peerId)
                    invalidatedWifiRelayRoutes[
                        wifiRelayRouteKey(
                            removedDeviceId = peerId,
                            relayDeviceId = notice.removedByDeviceId
                        )
                    ] = System.currentTimeMillis()
                }

                // The remaining member gets the user-facing notice. The removed
                // member receives this packet only to suppress automatic Wi-Fi
                // reconnection; it already received the normal squad REMOVE.
                if (notice.removedDeviceId != identityStore.deviceIdValue) {
                    val message =
                        notice.removedCallsign +
                            " REMOVED FROM WI-FI GROUP BY " +
                            notice.removedByCallsign

                    _uiState.update {
                        it.copy(squadNotification = message)
                    }
                    refreshSquadConnectionStates()

                    delay(5000L)
                    _uiState.update { state ->
                        if (state.squadNotification == message) {
                            state.copy(squadNotification = null)
                        } else {
                            state
                        }
                    }
                } else {
                    refreshSquadConnectionStates()
                }
            }
        }
        viewModelScope.launch {
            refreshSquadConnectionStates()
        }

        viewModelScope.launch {
            discoveryService.peers().collectLatest { devices ->
                _uiState.update { state ->
                    val existing = (state.availablePeers + state.squadPeers)
                        .associateBy { it.deviceAddress }

                    val knownCallsigns = buildMap {
                        (state.availablePeers + state.squadPeers).forEach { peer ->
                            put(peer.deviceAddress, peer.callsign)
                        }
                        devices.forEach { device ->
                            put(device.id.value, device.callsign)
                        }
                    }

                    val peers = devices.map { device ->
                        val id = device.id.value
                        val previous = existing[id]
                        val hasRssi = device.rssi != 0
                        val callsign = device.callsign.ifBlank {
                            previous?.callsign ?: id
                        }

                        // Persist discovered callsigns for every peer, not
                        // just squad members. Messages can come from any available
                        // device, so the sender name must be resolvable even when
                        // that peer has never been added to the squad.
                        localAppDataStore.savePairedDevice(
                            StoredPairedDevice(
                                deviceId = id,
                                callsign = callsign,
                                lastSeenEpochMs = device.lastSeen.toEpochMilli(),
                                rssi = device.rssi,
                                linkText = device.link.name
                            )
                        )

                        val bluetoothState = if (
                            previous?.bleState != null
                        ) {
                            previous.bleState
                        } else {
                            BleLinkState.AVAILABLE
                        }
                        val wifiState =
                            device.transportStates[RadioType.WIFI_DIRECT]
                                ?: previous?.wifiDirectState
                                ?: RadioLinkState.UNAVAILABLE

                        val liveConnectedByTransport =
                            radioTransport.connectedPeerIdsByTransport()
                        val liveBleIds =
                            liveConnectedByTransport[RadioType.BLUETOOTH].orEmpty()
                        val liveWifiIds =
                            liveConnectedByTransport[RadioType.WIFI_DIRECT].orEmpty()
                        val liveBleConnected = id in liveBleIds
                        val liveWifiConnected = id in liveWifiIds

                        // A direct Wi-Fi rejoin is authoritative proof that
                        // the previously invalidated Wi-Fi route is alive
                        // again. BLE state is intentionally not used here.
                        if (liveWifiConnected) {
                            clearWifiRelayInvalidationsForPeer(id)
                        }

                        val routeText = routeLinkText(
                            device = device,
                            knownCallsigns = knownCallsigns
                        )

                        val immediateRelayId =
                            device.path.lastOrNull()?.value
                        val relayRouteKey =
                            immediateRelayId?.let {
                                wifiRelayRouteKey(
                                    removedDeviceId = id,
                                    relayDeviceId = it
                                )
                            }

                        // A fresh topology beacon that arrives after the
                        // removal notice proves the Wi-Fi relay route has been
                        // rebuilt. Ignore the brief in-flight beacon window
                        // immediately after removal so the route cannot flicker
                        // back to connected while the head is closing the link.
                        val invalidatedAt =
                            relayRouteKey?.let { invalidatedWifiRelayRoutes[it] }
                        if (
                            invalidatedAt != null &&
                                device.link == LinkType.RELAYED &&
                                device.lastSeen.toEpochMilli() >
                                    invalidatedAt + WIFI_ROUTE_RESTORE_GRACE_MS
                        ) {
                            invalidatedWifiRelayRoutes.remove(
                                relayRouteKey,
                                invalidatedAt
                            )
                        }

                        val previousRelayedRoute =
                            previous?.linkText
                                ?.takeIf {
                                    it.startsWith("VIA ") ||
                                        it == "RELAYED"
                                }
                        val stableRouteText =
                            if (
                                routeText.startsWith("VIA ") ||
                                routeText == "RELAYED"
                            ) {
                                routeText
                            } else {
                                previousRelayedRoute ?: routeText
                            }

                        val effectiveBleState =
                            when {
                                liveBleConnected ->
                                    BleLinkState.CONNECTED
                                bluetoothState == BleLinkState.CONNECTING ->
                                    BleLinkState.CONNECTING
                                bluetoothState == BleLinkState.AVAILABLE ->
                                    BleLinkState.AVAILABLE
                                else ->
                                    BleLinkState.DISCONNECTED
                            }

                        val effectiveWifiState =
                            when {
                                liveWifiConnected ->
                                    RadioLinkState.CONNECTED
                                wifiState == RadioLinkState.CONNECTING ->
                                    RadioLinkState.CONNECTING
                                wifiState == RadioLinkState.AVAILABLE ->
                                    RadioLinkState.AVAILABLE
                                wifiState == RadioLinkState.UNAVAILABLE ->
                                    RadioLinkState.UNAVAILABLE
                                else ->
                                    RadioLinkState.DISCONNECTED
                            }

                        val relayRouteInvalidated =
                            id in invalidatedWifiRelayPeerIds ||
                                (
                                    relayRouteKey != null &&
                                        invalidatedWifiRelayRoutes.containsKey(
                                            relayRouteKey
                                        )
                                )
                        val liveRelayedRoute =
                            !relayRouteInvalidated &&
                                immediateRelayId != null &&
                                (
                                    immediateRelayId in liveBleIds ||
                                        immediateRelayId in liveWifiIds
                                    )
                        val hasLiveRadio =
                            liveBleConnected ||
                                liveWifiConnected ||
                                liveRelayedRoute

                        val transportText = buildList {
                            if (effectiveBleState == BleLinkState.CONNECTED) {
                                add("BLE")
                            }
                            if (effectiveWifiState == RadioLinkState.CONNECTED) {
                                add("Wi-Fi")
                            }
                        }.joinToString(" + ")

                        val effectiveLinkText = when {
                            liveBleConnected || liveWifiConnected -> {
                                if (transportText.isNotBlank()) {
                                    "DIRECT • $transportText"
                                } else {
                                    "DIRECT"
                                }
                            }
                            relayRouteInvalidated -> "STALE"
                            else -> stableRouteText
                        }

                        PeerNodeUi(
                            deviceAddress = id,
                            callsign = callsign,
                            isConnected = hasLiveRadio,
                            distanceText = if (hasRssi) {
                                formatDistance(estimator.estimate(device.rssi))
                            } else {
                                previous?.distanceText ?: "Unknown"
                            },
                            signalBars = if (hasRssi) {
                                signalBars(device.rssi)
                            } else {
                                previous?.signalBars ?: 0
                            },
                            linkText = effectiveLinkText,
                            bleState = effectiveBleState,
                            wifiDirectState = effectiveWifiState,
                            path = device.path.map { it.value }
                        )
                    }

                    peers.forEach { peer ->
                        if (observedPeerIds.add(peer.deviceAddress)) {
                            observePeerState(peer.deviceAddress)
                        }
                    }

                    val squadIds = squadMembershipStore.squadDeviceIds()
                    val squadById = state.squadPeers.associateBy { it.deviceAddress }
                    val currentSquad = squadIds.mapNotNull { id ->
                        peers.firstOrNull { it.deviceAddress == id } ?: squadById[id]
                    }

                    // Explicitly removed peers must remain selectable on Home
                    // even after Android's P2P/DNS-SD discovery list temporarily
                    // goes empty during group teardown. Rehydrate those entries
                    // from persistent identity data until the user re-adds them.
                    val retainedRemovedPeers = localAppDataStore
                        .loadPairedDevices()
                        .filter {
                            it.deviceId in recentlyRemovedFromSquadIds &&
                                it.deviceId !in squadIds
                        }
                        .map { stored ->
                            storedPeerToUi(stored).copy(
                                isConnected = false,
                                linkText = "STALE",
                                bleState = BleLinkState.AVAILABLE,
                                wifiDirectState = RadioLinkState.UNAVAILABLE
                            )
                        }

                    val homePeers = (peers + retainedRemovedPeers)
                        .distinctBy { it.deviceAddress }

                    val knownWifiGroupMembers =
                        state.wifiDirectDiscoveredGroupMemberIds
                            .filterNot { it in recentlyRemovedFromSquadIds }
                            .toSet()

                    state.copy(
                        squadPeers = currentSquad,
                        // Keep normal Wi-Fi group members hidden so another phone
                        // does not try to create a competing P2P group. Explicitly
                        // removed peers are retained from persistent identity data
                        // so they stay on Home through the P2P discovery gap.
                        availablePeers = homePeers.filter { peer ->
                            peer.deviceAddress !in squadIds &&
                                peer.deviceAddress !in knownWifiGroupMembers
                        }
                    )
                }
            }
        }

        healthJob = viewModelScope.launch {
            try {
                while (true) {
                    try {
                        delay(1000L)
                        refreshSquadConnectionStates()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        // Keep the health loop alive after transient errors.
                    }
                }
            } catch (_: CancellationException) {
                // Normal ViewModel cancellation.
            }
        }
    }

    /**
     * Reconciles the UI's connection flags against the BLE registry. This is
     * the source of truth even when an inbound and outbound GATT callback race.
     */
    private fun refreshSquadConnectionStates() {
        val connectedByTransport = radioTransport.connectedPeerIdsByTransport()
        val connectedIds = connectedByTransport.values.flatten().toSet()
        val connectedBleIds = connectedByTransport[RadioType.BLUETOOTH].orEmpty()
        val connectedWifiIds = connectedByTransport[RadioType.WIFI_DIRECT].orEmpty()

        _uiState.update { state ->
            fun updatePeer(peer: PeerNodeUi): PeerNodeUi {
                val bleConnected = peer.deviceAddress in connectedBleIds
                val wifiConnected = peer.deviceAddress in connectedWifiIds
                val isDirectlyConnected = peer.deviceAddress in connectedIds

                // A relayed mesh route is still a usable squad connection.
                // The physical TCP socket terminates at the relay/GO, so a
                // peer reached through "VIA ..." will not appear in the direct
                // transport registry even though messages can traverse the
                // route normally.
                // A remembered "VIA ..." label is not proof that the relay
                // is still reachable. For a relayed discovery path, the last
                // hop is the immediate relay that must have a live physical
                // bearer on this device.
                val immediateRelayId = peer.path.lastOrNull()
                val relayRouteInvalidated =
                    peer.deviceAddress in invalidatedWifiRelayPeerIds ||
                        (
                            immediateRelayId != null &&
                                invalidatedWifiRelayRoutes.containsKey(
                                    wifiRelayRouteKey(
                                        removedDeviceId = peer.deviceAddress,
                                        relayDeviceId = immediateRelayId
                                    )
                                )
                        )
                val isRelayedReachable =
                    !isDirectlyConnected &&
                        !relayRouteInvalidated &&
                        immediateRelayId != null &&
                        immediateRelayId in connectedIds

                val isConnected = isDirectlyConnected || isRelayedReachable

                val updatedLinkText = when {
                    isDirectlyConnected -> {
                        connectedTransportText(
                            peer.copy(
                                bleState = if (bleConnected) {
                                    BleLinkState.CONNECTED
                                } else {
                                    peer.bleState
                                },
                                wifiDirectState = if (wifiConnected) {
                                    RadioLinkState.CONNECTED
                                } else {
                                    peer.wifiDirectState
                                }
                            )
                        )
                    }
                    relayRouteInvalidated -> "STALE"
                    else -> peer.linkText
                }

                return peer.copy(
                    isConnected = isConnected,
                    linkText = updatedLinkText,
                    bleState = when {
                        bleConnected -> BleLinkState.CONNECTED
                        peer.bleState == BleLinkState.CONNECTED ->
                            BleLinkState.DISCONNECTED
                        else -> peer.bleState
                    },
                    wifiDirectState = when {
                        wifiConnected -> RadioLinkState.CONNECTED
                        peer.wifiDirectState == RadioLinkState.CONNECTED ->
                            RadioLinkState.DISCONNECTED
                        else -> peer.wifiDirectState
                    }
                )
            }

            val updatedSquadPeers = state.squadPeers.map(::updatePeer)
            val updatedAvailablePeers = state.availablePeers
                .map(::updatePeer)

            state.copy(
                squadPeers = updatedSquadPeers,
                availablePeers = updatedAvailablePeers
            )
        }

        if (connectedIds.isEmpty() && !_uiState.value.pttEnabled) {
            viewModelScope.launch {
                if (_uiState.value.pttContinuousSession) {
                    runCatching { pttController.stopContinuous() }
                }
                pttModePreferences.setPttEnabled(true)
                _uiState.update { it.copy(pttEnabled = true) }
            }
        }
    }

    /**
     * Starts discovery for the application. The DiscoveryService owns the
     * persistent low-duty-cycle scan loop; the UI does not stop it.
     *
     * Background scan cycles intentionally do not toggle isScanning, so the
     * device list remains visually stable while maintenance scans run.
     */
    fun startDiscovery() {
        viewModelScope.launch {
            runCatching { discoveryService.start() }
        }
    }

    /**
     * Requests an immediate scan without replacing the background maintenance
     * loop. The visible indicator is only for an explicit user action.
     */
    fun forceDiscovery() {
        val service = discoveryService as? DefaultDiscoveryService ?: return

        // Manual scan refreshes both radios. Wi-Fi Direct may have been off
        // when the app started, so explicitly re-running its startup/discovery
        // path is required when Wi-Fi has just been enabled.
        service.scanNow()
        _uiState.update { it.copy(isScanning = true) }

        viewModelScope.launch {
            delay(MANUAL_SCAN_DISPLAY_MS)
            _uiState.update { it.copy(isScanning = false) }
        }
    }

    fun refreshWifiDirect() {
        val service = discoveryService as? DefaultDiscoveryService ?: return
        viewModelScope.launch {
            runCatching { service.refreshWifiDirect() }
        }
    }

    fun addPeerToSquad(deviceAddress: String) {
        // Explicit Add starts a fresh application-level authorization attempt.
        // Do not clear Wi-Fi removal suppression until we actually enter the
        // Wi-Fi path; BLE-only Add must remain completely independent of Wi-Fi.
        recentlyRemovedFromSquadIds.remove(deviceAddress)
        clearWifiRelayInvalidationsForPeer(deviceAddress)
        radioTransport.allowPeer(deviceAddress)

        val peer = _uiState.value.availablePeers
            .firstOrNull { it.deviceAddress == deviceAddress }

        viewModelScope.launch {
            val liveBleConnected =
                deviceAddress in radioTransport
                    .connectedPeerIdsByTransport()[RadioType.BLUETOOTH].orEmpty() ||
                    withTimeoutOrNull(500L) {
                        bleConnectionManager.state(deviceAddress).first {
                            it == BleLinkState.CONNECTED
                        }
                    } == BleLinkState.CONNECTED

            val result = runCatching {
                when {
                    // A live BLE session always wins over stale UI topology or
                    // remembered Wi-Fi state. Wi-Fi is only an automatic upgrade
                    // performed by WifiDirectManager after BLE is already connected.
                    liveBleConnected ||
                        deviceAddress in radioTransport.connectedPeerIds() -> {
                        android.util.Log.d(
                            "MainViewModel",
                            "Adding peer through existing BLE transport: " + deviceAddress
                        )
                        meshSquadControlCoordinator.requestAddToSquad(deviceAddress)
                    }

                    peer != null &&
                        (peer.linkText.startsWith("VIA ") || peer.linkText == "RELAYED") -> {
                        // Relayed peers must use the mesh control plane; there is
                        // no direct Wi-Fi/BLE connection to establish here.
                        meshSquadControlCoordinator.requestAddToSquad(deviceAddress)
                    }

                    // A live physical bearer is authoritative. This is used for
                    // both BLE-only and Wi-Fi-only tests even when discovery state
                    // still contains an older/stale entry for the other bearer.
                    deviceAddress in radioTransport.connectedPeerIds() -> {
                        android.util.Log.d(
                            "MainViewModel",
                            "Adding already-connected peer via existing transport: " +
                                deviceAddress
                        )
                        meshSquadControlCoordinator.requestAddToSquad(deviceAddress)
                    }

                    // When Wi-Fi Direct is available, prefer it for an
                    // explicit Add-to-Squad even if the UI catalog is currently
                    // showing only the peer's BLE beacon. connectByAppDeviceId()
                    // can recover the Wi-Fi MAC from the manager's persisted
                    // identity cache and can wait for fresh P2P rediscovery.
                    wifiDirectManager.isWifiEnabled() &&
                        peer?.bleState != BleLinkState.CONNECTED -> {
                        // Wi-Fi suppression is cleared only when the user is
                        // actually attempting a Wi-Fi re-add.
                        wifiDirectManager.allowAutoReconnectTo(deviceAddress)

                        val connectResult =
                            wifiDirectManager.connectByAppDeviceId(deviceAddress)

                        if (connectResult is TacticalResult.Failure) {
                            // Do not silently switch back to BLE while Wi-Fi is
                            // enabled. The explicit add should exercise the
                            // requested Wi-Fi path and surface the real failure
                            // when Wi-Fi identity/discovery is unavailable.
                            connectResult
                        } else {
                            val ready = withTimeoutOrNull(5_000L) {
                                while (deviceAddress !in radioTransport.connectedPeerIds()) {
                                    delay(100L)
                                }
                                true
                            } == true

                            if (!ready) {
                                TacticalResult.Failure(
                                    "Wi-Fi Direct connected, but the data link did not become ready"
                                )
                            } else {
                                meshSquadControlCoordinator.requestAddToSquad(deviceAddress)
                            }
                        }
                    }

                    else -> {
                        android.util.Log.d(
                            "MainViewModel",
                            "Adding peer through BLE squad request: " + deviceAddress
                        )
                        bleConnectionManager.addToSquad(deviceAddress)
                    }
                }
            }.getOrElse {
                TacticalResult.Failure(
                    it.message ?: it.javaClass.simpleName
                )
            }

            if (result is TacticalResult.Failure) {
                val error = result.error
                android.util.Log.w(
                    "MainViewModel",
                    "Add to squad failed for " + deviceAddress + ": " + error
                )
                _uiState.update {
                    it.copy(squadRequestError = error)
                }
            }
        }
    }
    fun dismissWifiMultipleRequestWarning() {
        wifiMultipleRequestWarningDismissed = true
        _uiState.update {
            it.copy(showWifiMultipleRequestWarning = false)
        }
    }

    fun respondToSquadRequest(deviceId: String, approve: Boolean) {
        if (_uiState.value.respondingSquadRequestId != null) return

        val isMeshRequest = meshSquadControlCoordinator.hasPending(deviceId)

        // Keep the display data from the request itself. Approval can succeed
        // over GATT even when the requester has not appeared in discovery yet.
        val request = _uiState.value.pendingSquadRequest
            ?.takeIf { it.deviceId == deviceId }

        _uiState.update {
            it.copy(
                respondingSquadRequestId = deviceId,
                squadRequestError = null
            )
        }

        viewModelScope.launch {
            val result = runCatching {
                if (isMeshRequest) {
                    meshSquadControlCoordinator.respondToRequest(deviceId, approve)
                } else {
                    bleConnectionManager.respondToSquadRequest(deviceId, approve)
                }
            }.getOrElse {
                TacticalResult.Failure(it.message ?: it.javaClass.simpleName)
            }

            if (result is TacticalResult.Success && approve && request != null) {
                localAppDataStore.savePairedDevice(
                    StoredPairedDevice(
                        deviceId = request.deviceId,
                        callsign = request.callsign,
                        lastSeenEpochMs = System.currentTimeMillis(),
                        rssi = 0,
                        linkText = "CONNECTED"
                    )
                )

                if (observedPeerIds.add(request.deviceId)) {
                    observePeerState(request.deviceId)
                }

                // The requester may not be in the discovery catalog yet, so
                // rebuild the squad list from persistent local peer data too.
                refreshSquadPeers()
            }

            _uiState.update {
                it.copy(
                    respondingSquadRequestId = null,
                    squadRequestError = when (result) {
                        is TacticalResult.Success -> null
                        is TacticalResult.Failure -> result.error
                    }
                )
            }
        }
    }
    fun removePeerFromSquad(deviceAddress: String) {
        viewModelScope.launch {
            val wifiInfo = wifiDirectManager.connectionInfo().value
            val liveWifiPeerIds = radioTransport
                .connectedPeerIdsByTransport()[RadioType.WIFI_DIRECT]
                .orEmpty()

            // In a 3+ phone Wi-Fi group, the head's "remove" action means
            // remove only the Wi-Fi relationship to that member. This preserves
            // squad membership and BLE. The existing 2-device squad-removal
            // behavior is intentionally left untouched so the earlier
            // two-phone fix cannot regress.
            val isMultiMemberWifiHeadRemoval =
                wifiInfo.groupFormed &&
                    wifiInfo.isGroupOwner &&
                    wifiInfo.groupMemberDeviceAddresses.size >= 3 &&
                    deviceAddress in liveWifiPeerIds

            val result = runCatching {
                if (isMultiMemberWifiHeadRemoval) {
                    meshSquadControlCoordinator.removeFromWifiGroup(deviceAddress)
                } else {
                    meshSquadControlCoordinator.removeFromSquad(deviceAddress)
                }
            }.getOrElse {
                TacticalResult.Failure(
                    it.message ?: it.javaClass.simpleName
                )
            }

            if (result is TacticalResult.Success) {
                if (isMultiMemberWifiHeadRemoval) {
                    // The peer is still a squad member. Do not add it to the
                    // squad-removal tombstone or change membership state.
                    refreshSquadConnectionStates()
                } else {
                    // Keep the peer's persisted identity so it immediately
                    // remains available on Home after actual squad removal.
                    recentlyRemovedFromSquadIds.add(deviceAddress)
                    refreshSquadPeers()
                }
            } else {
                val error = (result as TacticalResult.Failure).error
                android.util.Log.w(
                    "MainViewModel",
                    "Remove peer action failed for " + deviceAddress + ": " + error
                )
                _uiState.update { it.copy(squadRequestError = error) }
            }
        }
    }

    private fun refreshSquadPeers() {
        val squadIds = squadMembershipStore.squadDeviceIds()
        val storedById = localAppDataStore
            .loadPairedDevices()
            .associateBy { it.deviceId }

        _uiState.update { state ->
            val knownById = (state.availablePeers + state.squadPeers)
                .associateBy { it.deviceAddress }

            val allKnown = buildList {
                addAll(knownById.values)
                storedById.values.forEach { stored ->
                    if (knownById[stored.deviceId] == null) {
                        add(storedPeerToUi(stored))
                    }
                }
            }.distinctBy { it.deviceAddress }

            val squad = squadIds.mapNotNull { id ->
                allKnown.firstOrNull { it.deviceAddress == id }
            }

            state.copy(
                squadPeers = squad,
                availablePeers = allKnown.filter {
                    it.deviceAddress !in squadIds
                }
            )
        }
    }
    fun observePeerState(deviceAddress: String) {
        viewModelScope.launch {
            bleConnectionManager.state(deviceAddress).collect { linkState ->
                _uiState.update { state ->
                    fun updatePeer(peer: PeerNodeUi): PeerNodeUi =
                        if (peer.deviceAddress == deviceAddress) {
                            peer.copy(
                                bleState = linkState,
                                isConnected = linkState == BleLinkState.CONNECTED,
                                linkText = if (linkState == BleLinkState.CONNECTED) {
                                    "DIRECT"
                                } else {
                                    peer.linkText
                                }
                            )
                        } else {
                            peer
                        }

                    val updatedPeers = (state.squadPeers + state.availablePeers)
                        .map(::updatePeer)
                        .distinctBy { it.deviceAddress }
                    val squadIds = squadMembershipStore.squadDeviceIds()

                    state.copy(
                        squadPeers = updatedPeers.filter { it.deviceAddress in squadIds },
                        // Keep discovered peers visible even when a transport
                        // is already connected. "Available" here means not in
                        // the squad; the card displays the live transport state.
                        availablePeers = updatedPeers.filter {
                            it.deviceAddress !in squadIds
                        }
                    )
                }
            }
        }

        // Keep RSSI/distance/signal information fresh between discovery scans.
        viewModelScope.launch {
            bleConnectionManager.rssi(deviceAddress).collect { rssi ->
                if (rssi == null) return@collect

                _uiState.update { state ->
                    fun updatePeer(peer: PeerNodeUi): PeerNodeUi =
                        if (peer.deviceAddress == deviceAddress) {
                            peer.copy(
                                distanceText = formatDistance(estimator.estimate(rssi)),
                                signalBars = signalBars(rssi)
                            )
                        } else {
                            peer
                        }

                    state.copy(
                        squadPeers = state.squadPeers.map(::updatePeer),
                        availablePeers = state.availablePeers.map(::updatePeer)
                    )
                }
            }
        }
    }

    fun connectPeer(deviceAddress: String) {
        viewModelScope.launch { bleConnectionManager.connect(deviceAddress) }
    }

    fun repairPeer(deviceAddress: String) {
        viewModelScope.launch { bleConnectionManager.repairAndReconnect(deviceAddress) }
    }

    fun setTtsPlaybackMode(mode: com.tactical.platform.speech.mms.MmsTtsPlaybackMode) {
        if (_uiState.value.ttsPlaybackMode == mode) return

        mmsTtsPlaybackPreferences.setPlaybackMode(mode)
        mmsTtsPlaybackCoordinator.setMode(mode)
        _uiState.update { it.copy(ttsPlaybackMode = mode) }
    }

    fun setUiLanguage(languageCode: String) {
        if (languageCode !in setOf("en", "hi", "gu", "mr", "kn", "ml", "ta", "te", "or", "bn")) return
        uiLanguagePreferences.setSelectedLanguageCode(languageCode)
        _uiState.update { it.copy(uiLanguageCode = languageCode) }
    }

    fun setSelectedLanguage(languageCode: String) {
        if (languageCode !in SUPPORTED_SPEECH_LANGUAGE_CODES) return
        if (_uiState.value.languageLoadingCode != null) return
        if (_uiState.value.selectedLanguageCode == languageCode) return

        val wasContinuousCallMode =
            !_uiState.value.pttEnabled && _uiState.value.pttContinuousSession

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    languageLoadingCode = languageCode,
                    languageLoadError = null
                )
            }

            try {
                if (wasContinuousCallMode) {
                    runCatching { pttController.stopContinuous() }
                }

                // Persist and expose the new language before model warm-up.
                // A preload failure must not silently switch the user back to
                // the old language; the STT backend can retry lazily on use.
                speechLanguagePreferences.setSelectedLanguageCode(languageCode)
                routingSpeechToText.onSelectedLanguageChanged(languageCode)

                routingSpeechToText.preloadSelectedLanguage()

                _uiState.update {
                    it.copy(
                        selectedLanguageCode = languageCode,
                        selectedLanguage = displayLanguageName(languageCode),
                        languageLoadingCode = null,
                        languageLoadError = null
                    )
                }

                if (wasContinuousCallMode && !_uiState.value.pttEnabled) {
                    runCatching { pttController.startContinuous() }
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t

                val message = t.message ?: t.javaClass.simpleName
                android.util.Log.e(
                    "MainViewModel",
                    "Failed to preload STT for language $languageCode: $message",
                    t
                )

                // Keep the user's selection. transcribe() will retry loading
                // the selected backend when the next voice transmission starts.
                _uiState.update {
                    it.copy(
                        selectedLanguageCode = languageCode,
                        selectedLanguage = displayLanguageName(languageCode),
                        languageLoadingCode = null,
                        languageLoadError = "STT preload failed; will retry when recording starts."
                    )
                }

                if (wasContinuousCallMode && !_uiState.value.pttEnabled) {
                    runCatching { pttController.startContinuous() }
                }
            }

            // Incoming multilingual TTS is warmed independently and never
            // extends the visible language-loading state.
            MmsTtsLanguage.fromIsoCode(languageCode)?.let { language ->
                viewModelScope.launch {
                    runCatching { mmsTtsEngine.preload(language) }
                }
            }
        }
    }

    fun setPttEnabled(enabled: Boolean) {
        val state = _uiState.value

        if (state.pttEnabled == enabled) return
        if (state.emergencyComposerVisible) return

        // Do not switch voice mode underneath an active manual PTT session.
        // The microphone must remain owned by exactly one capture session.
        if (state.pttSessionState != SessionState.IDLE && !state.pttContinuousSession) {
            return
        }

        if (!enabled) {
            val squadIds = squadMembershipStore.squadDeviceIds()
            val connectedTransportIds = radioTransport.connectedPeerIds()
            val hasConnectedSquadTransport = squadIds.any { it in connectedTransportIds }

            if (!hasConnectedSquadTransport) {
                return
            }
        }

        pttModePreferences.setPttEnabled(enabled)
        _uiState.update { it.copy(pttEnabled = enabled) }

        viewModelScope.launch {
            if (enabled) {
                runCatching { pttController.stopContinuous() }
            } else {
                runCatching { pttController.startContinuous() }
            }
        }
    }

    /**
     * Starts continuous voice mode after microphone permission is available.
     */
    fun ensureVoiceMode() {
        if (_uiState.value.pttEnabled) return

        val squadIds = squadMembershipStore.squadDeviceIds()
        val connectedTransportIds = radioTransport.connectedPeerIds()
        val hasConnectedSquadTransport = squadIds.any { it in connectedTransportIds }

        if (hasConnectedSquadTransport) {
            viewModelScope.launch {
                runCatching { pttController.startContinuous() }
            }
        }
    }

    fun setUsername(username: String): String? {
        val cleaned = username.trim()
        if (cleaned.isBlank()) return "Username cannot be blank."

        return runCatching {
            identityStore.setCallsign(cleaned)
        }.fold(
            onSuccess = {
                _uiState.update { state ->
                    state.copy(username = cleaned)
                }
                viewModelScope.launch {
                    runCatching { discoveryService.stop() }
                    runCatching { discoveryService.start() }
                    runCatching { bleConnectionManager.announceLocalCallsign(cleaned) }
                }
                null
            },
            onFailure = { it.message ?: "Could not save username." }
        )
    }


    fun startEmergencyHold() {
        val state = _uiState.value
        if (state.emergencyComposerVisible) return

        if (state.pttContinuousSession) {
            // Emergency recording shares the microphone with continuous mode.
            // Stop continuous capture immediately while the existing 2-second
            // emergency hold timer continues from the user's initial press.
            resumeContinuousAfterEmergency = !state.pttEnabled
            audioRecorder.stop()
            viewModelScope.launch {
                runCatching { pttController.stopContinuous() }
            }
        } else if (state.pttSessionState != SessionState.IDLE) {
            return
        }

        emergencyTrigger.startHold()
    }

    fun releaseEmergencyHold() {
        val shouldResume = resumeContinuousAfterEmergency &&
            !_uiState.value.emergencyComposerVisible

        emergencyTrigger.releaseHold()

        if (shouldResume) {
            resumeContinuousVoiceIfNeeded()
        }
    }

    private fun startEmergencyRecording() {
        if (_uiState.value.emergencyComposerVisible) return

        // The emergency UI becomes active first so releasing the emergency
        // button after the verified hold cannot accidentally restart call mode.
        _uiState.update {
            it.copy(
                emergencyComposerVisible = true,
                emergencyRecording = false,
                emergencySending = false,
                emergencyTranscription = "",
                emergencyError = null
            )
        }

        emergencyRecordingJob?.cancel()
        emergencyRecordingJob = viewModelScope.launch {
            try {
                // Continuous voice mode shares the same microphone. Wait for it
                // to finish releasing the recorder before starting emergency STT.
                runCatching { pttController.stopContinuous() }

                _uiState.update {
                    it.copy(emergencyRecording = true)
                }

                var finalizedText = ""
                var latestPartial = ""

                val frames = audioRecorder.start(AudioConfig())
                speechToText.transcribe(frames).collect { chunk ->
                    if (chunk.isFinal) {
                        val finalText = chunk.text.trim()
                        if (finalText.isNotBlank()) {
                            // Vosk/Moonshine can finalize a sentence at a
                            // pause. Keep those segments instead of replacing
                            // the previously transcribed emergency text.
                            finalizedText = appendEmergencyTranscript(
                                finalizedText,
                                finalText
                            )
                        }
                        latestPartial = ""
                    } else {
                        latestPartial = chunk.text.trim()
                    }

                    val liveText = appendEmergencyTranscript(
                        finalizedText,
                        latestPartial
                    )
                    if (liveText.isNotBlank()) {
                        _uiState.update {
                            it.copy(emergencyTranscription = liveText)
                        }
                    }
                }
            } catch (t: Throwable) {
                _uiState.update {
                    it.copy(
                        emergencyRecording = false,
                        emergencyError = t.message ?: t.javaClass.simpleName
                    )
                }
                resumeContinuousVoiceIfNeeded()
            }
        }
    }

    private fun appendEmergencyTranscript(existing: String, next: String): String {
        if (next.isBlank()) return existing
        if (existing.isBlank()) return next.trim()
        if (existing == next.trim()) return existing
        return existing.trim() + " " + next.trim()
    }

    fun cancelEmergency() {
        audioRecorder.stop()
        emergencyRecordingJob?.cancel()
        emergencyRecordingJob = null
        emergencyTrigger.reset()
        _uiState.update {
            it.copy(
                emergencyComposerVisible = false,
                emergencyRecording = false,
                emergencySending = false,
                emergencyTranscription = "",
                emergencyError = null
            )
        }
        resumeContinuousVoiceIfNeeded()
    }

    /**
     * Emergency is a true broadcast: every currently discovered direct peer
     * (available or already in the squad) should have a chance to receive it.
     * MeshService already broadcasts EmergencyPacket to all connected radios;
     * this step brings discovered-but-not-yet-connected direct peers online
     * before that broadcast. Relayed peers are intentionally left alone because
     * the mesh relay path handles them through the peers already connected.
     */
    private suspend fun prepareEmergencyRecipients() {
        val state = _uiState.value
        val recipients = (state.availablePeers + state.squadPeers)
            .distinctBy { it.deviceAddress }
            .filter { peer ->
                peer.linkText != "RELAYED" &&
                    !peer.linkText.startsWith("VIA ")
            }

        if (recipients.isEmpty()) return

        coroutineScope {
            recipients
                .filter { it.deviceAddress !in radioTransport.connectedPeerIds() }
                .map { peer ->
                    async {
                        val deviceId = peer.deviceAddress

                        // Prefer Wi-Fi Direct when this peer was discovered
                        // through the Wi-Fi P2P layer; BLE is the fallback.
                        if (peer.wifiDirectState != RadioLinkState.UNAVAILABLE) {
                            val wifiResult = runCatching {
                                wifiDirectManager.connectByAppDeviceId(deviceId)
                            }.getOrNull()

                            if (wifiResult is TacticalResult.Success) {
                                withTimeoutOrNull(3_000L) {
                                    while (deviceId !in radioTransport.connectedPeerIds()) {
                                        delay(100L)
                                    }
                                }
                                if (deviceId in radioTransport.connectedPeerIds()) {
                                    return@async
                                }
                            }
                        }

                        runCatching {
                            bleConnectionManager.connect(deviceId)
                        }

                        withTimeoutOrNull(3_000L) {
                            while (deviceId !in radioTransport.connectedPeerIds()) {
                                delay(100L)
                            }
                        }
                    }
                }
                .awaitAll()
        }
    }

    fun sendEmergency() {
        if (_uiState.value.emergencySending) return

        viewModelScope.launch {
            _uiState.update {
                it.copy(emergencySending = true, emergencyError = null)
            }

            audioRecorder.stop()
            withTimeoutOrNull(4000L) {
                emergencyRecordingJob?.join()
            }

            val text = _uiState.value.emergencyTranscription.trim()
            if (text.isBlank()) {
                _uiState.update {
                    it.copy(
                        emergencySending = false,
                        emergencyRecording = false,
                        emergencyError = "No message was transcribed."
                    )
                }
                return@launch
            }

            val packet = emergencyMessageBuilder.build(
                sender = DeviceId(identityStore.deviceIdValue),
                severity = Severity.CRITICAL,
                description = text,
                location = null,
                languageCode = _uiState.value.selectedLanguageCode
            )

            // Bring every currently discovered direct recipient online first.
            // The emergency mesh broadcast itself remains unrestricted and is
            // then delivered to all connected BLE/Wi-Fi Direct peers.
            prepareEmergencyRecipients()
            val result = emergencyBroadcaster.broadcastSos(packet)
            val status = when (result) {
                is TacticalResult.Success -> "Sent"
                is TacticalResult.Failure -> "Queued"
            }
            val details = emergencyAlertData(packet, "YOU")
            val localSendTime = System.currentTimeMillis()
            val message = ChatMessageUi(
                sender = "YOU",
                text = packet.description,
                timestampText = formatTimestamp(packet.timestamp),
                statusText = status,
                isAlert = true,
                emergencyData = details,
                timestampEpochMs = packet.timestamp,
                conversationOrderEpochMs = localSendTime
            )
            localAppDataStore.saveSentMessage(
                storedSentMessage(message)
            )

            _uiState.update {
                it.copy(
                    messages = listOf(message) + it.messages,
                    sentMessages = listOf(message) + it.sentMessages,
                    emergencyComposerVisible = false,
                    emergencyRecording = false,
                    emergencySending = false,
                    emergencyTranscription = "",
                    emergencyError = null
                )
            }

            emergencyRecordingJob = null
            emergencyTrigger.reset()
            resumeContinuousVoiceIfNeeded()
        }
    }

    private fun emergencyAlertData(
        packet: EmergencyPacket,
        senderName: String
    ): EmergencyAlertData =
        EmergencyAlertData(
            sender = senderName,
            timestampText = formatTimestamp(packet.timestamp),
            severity = packet.severity.name,
            message = packet.description,
            languageCode = packet.languageCode,
            locationLatitude = packet.location?.latitude,
            locationLongitude = packet.location?.longitude,
            locationAccuracyMeters = packet.location?.accuracyMeters
        )

    fun pressPtt() {
        if (!_uiState.value.pttEnabled) return
        viewModelScope.launch {
            runCatching { pttController.press() }
        }
    }

    fun releasePtt() {
        if (!_uiState.value.pttEnabled) return
        viewModelScope.launch {
            runCatching { pttController.release() }
        }
    }

    fun deleteMessages(messages: Set<ChatMessageUi>, isSent: Boolean) {
        if (messages.isEmpty()) return

        val keys = messages.mapTo(mutableSetOf()) { messageStorageKey(it) }

        if (isSent) {
            localAppDataStore.deleteSentMessages(keys)
            _uiState.update { state ->
                state.copy(
                    messages = state.messages.filterNot { messageStorageKey(it) in keys },
                    sentMessages = state.sentMessages.filterNot { messageStorageKey(it) in keys }
                )
            }
        } else {
            localAppDataStore.deleteReceivedMessages(keys)
            refreshReceivedMessages()
        }
    }

    private fun messageStorageKey(message: ChatMessageUi): String =
        localAppDataStore.messageStorageKey(
            senderName = message.sender,
            timestampEpochMs = message.timestampEpochMs,
            text = message.text,
            isAlert = message.isAlert,
            isVoice = message.isVoice,
            isCallMode = message.isCallMode
        )

    fun refreshReceivedMessages() {
        val received = localAppDataStore.loadReceivedMessages()
            .asReversed()
            .map(::storedMessageToUi)

        _uiState.update {
            it.copy(
                receivedMessages = received,
                messages = (it.sentMessages + received)
                    .distinctBy(::messageStorageKey)
                    .sortedBy {
                        it.conversationOrderEpochMs.takeIf { time -> time > 0L }
                            ?: it.timestampEpochMs
                    },
                unreadMessageCount = localAppDataStore.unreadMessageCount()
            )
        }
    }

    fun markMessagesRead() {
        // Refresh from persistent storage first so messages received while the
        // Activity was backgrounded are present as soon as Messages is opened.
        refreshReceivedMessages()
        localAppDataStore.markMessagesRead()
        messageNotificationNotifier.clearMessageNotifications()
        _uiState.update { it.copy(unreadMessageCount = 0) }
    }

    fun cancelPtt() {
        viewModelScope.launch {
            runCatching { pttController.cancel() }
        }
    }

    fun sendTextMessage(text: String) {
        val value = text.trim()
        if (value.isBlank()) return

        val localSendTime = System.currentTimeMillis()
        val pending = ChatMessageUi(
            sender = "YOU",
            text = value,
            timestampText = "Just now",
            statusText = "Sending…",
            timestampEpochMs = localSendTime,
            conversationOrderEpochMs = localSendTime
        )
        localAppDataStore.saveSentMessage(
            storedSentMessage(pending)
        )

        _uiState.update {
            it.copy(
                messages = listOf(pending) + it.messages,
                sentMessages = listOf(pending) + it.sentMessages
            )
        }

        viewModelScope.launch {
            val squadIds = squadMembershipStore.squadDeviceIds()
            if (squadIds.isEmpty()) {
                updateSentMessageStatus(pending, "No squad members")
                return@launch
            }

            // Send immediately when a GATT session is already ready.
            // Do not block the UI behind the BLE manager's 20-second reconnect
            // timeout just because a squad member is temporarily disconnected.
            // The connection manager already performs background retries.
            val hasConnectedPeer = squadIds.any { peerId ->
                peerId in radioTransport.connectedPeerIds()
            }

            val connectionReady = if (hasConnectedPeer) {
                true
            } else {
                // Give one short, parallel reconnect window rather than
                // reconnecting squad members serially.
                coroutineScope {
                    squadIds.map { peerId ->
                        async {
                            runCatching {
                                kotlinx.coroutines.withTimeoutOrNull(4000L) {
                                    bleConnectionManager.reconnectSquadMember(peerId)
                                }
                            }.getOrNull() is TacticalResult.Success
                        }
                    }.awaitAll().any { it }
                }
            }

            if (!connectionReady) {
                updateSentMessageStatus(pending, "No active connections")
                return@launch
            }

            val result = meshService.send(
                TextPacket(
                    sender = DeviceId(identityStore.deviceIdValue),
                    text = value,
                    languageCode = "und",
                    timestamp = System.currentTimeMillis()
                )
            )

            val status = when (result) {
                is TacticalResult.Success -> "Sent"
                is TacticalResult.Failure -> "Queued"
            }

            updateSentMessageStatus(pending, status)
        }
    }

    private fun updateSentMessageStatus(
        message: ChatMessageUi,
        statusText: String
    ) {
        val key = messageStorageKey(message)

        localAppDataStore.updateSentMessageStatus(key, statusText)

        _uiState.update { state ->
            state.copy(
                messages = state.messages.map {
                    if (messageStorageKey(it) == key) {
                        it.copy(statusText = statusText)
                    } else {
                        it
                    }
                },
                sentMessages = state.sentMessages.map {
                    if (messageStorageKey(it) == key) {
                        it.copy(statusText = statusText)
                    } else {
                        it
                    }
                }
            )
        }
    }

    private fun pttTransmissionToUi(transmission: PttTransmission): PttTransmissionUi =
        PttTransmissionUi(
            id = transmission.id,
            text = transmission.text,
            timestampEpochMs = transmission.timestampEpochMs,
            statusText = when (transmission.status) {
                PttTransmissionStatus.SENDING -> "Sending…"
                PttTransmissionStatus.SENT -> "Sent"
                PttTransmissionStatus.QUEUED -> "Queued"
            }
        )

    private suspend fun syncContinuousTransmissions(
        transmissions: List<PttTransmission>
    ) {
        transmissions.forEach { transmission ->
            val statusText = when (transmission.status) {
                PttTransmissionStatus.SENDING -> "Sending…"
                PttTransmissionStatus.SENT -> "Sent"
                PttTransmissionStatus.QUEUED -> "Queued"
            }

            val existingMessage = continuousTransmissionMessages[transmission.id]

            if (existingMessage == null) {
                val message = ChatMessageUi(
                    sender = "YOU",
                    text = transmission.text,
                    timestampText = formatTimestamp(transmission.timestampEpochMs),
                    statusText = statusText,
                    isVoice = true,
                    isCallMode = true,
                    timestampEpochMs = transmission.timestampEpochMs,
                    conversationOrderEpochMs = transmission.timestampEpochMs
                )

                continuousTransmissionMessages[transmission.id] = message
                localAppDataStore.saveSentMessage(storedSentMessage(message))

                _uiState.update { state ->
                    if (state.sentMessages.any {
                        messageStorageKey(it) == messageStorageKey(message)
                    }) {
                        state
                    } else {
                        state.copy(
                            messages = listOf(message) + state.messages,
                            sentMessages = listOf(message) + state.sentMessages
                        )
                    }
                }
            } else if (existingMessage.statusText != statusText) {
                val updated = existingMessage.copy(statusText = statusText)
                continuousTransmissionMessages[transmission.id] = updated
                updateSentMessageStatus(existingMessage, statusText)
            }
        }
    }

    private fun resumeContinuousVoiceIfNeeded() {
        if (!resumeContinuousAfterEmergency) return
        resumeContinuousAfterEmergency = false

        if (!_uiState.value.pttEnabled) {
            viewModelScope.launch {
                runCatching { pttController.startContinuous() }
            }
        }
    }

    private fun displayLanguageName(languageCode: String): String =
        when (languageCode) {
            "hi" -> "हिन्दी"
            "gu" -> "ગુજરાતી"
            "mr" -> "मराठी"
            "kn" -> "ಕನ್ನಡ"
            "ml" -> "മലയാളം"
            "ta" -> "தமிழ்"
            "te" -> "తెలుగు"
            "or" -> "ଓଡ଼ିଆ"
            "bn" -> "বাংলা"
            "en" -> "English"
            else -> languageCode.uppercase(Locale.US)
        }

    private fun storedPeerToUi(peer: StoredPairedDevice): PeerNodeUi =
        PeerNodeUi(
            deviceAddress = peer.deviceId,
            callsign = peer.callsign,
            isConnected = false,
            distanceText = if (peer.rssi != 0) {
                formatDistance(estimator.estimate(peer.rssi))
            } else {
                "Unknown"
            },
            signalBars = if (peer.rssi != 0) signalBars(peer.rssi) else 0,
            linkText = peer.linkText,
            bleState = BleLinkState.AVAILABLE
        )

    private fun storedSentMessageToUi(message: StoredSentMessage): ChatMessageUi =
        ChatMessageUi(
            sender = message.senderName,
            text = message.text,
            timestampText = if (message.timestampEpochMs > 0L) {
                formatTimestamp(message.timestampEpochMs)
            } else {
                "Unknown"
            },
            statusText = message.statusText,
            isVoice = message.isVoice,
            isCallMode = message.isCallMode,
            isAlert = message.isAlert,
            emergencyData = if (message.isAlert) {
                EmergencyAlertData(
                    sender = message.senderName,
                    timestampText = formatTimestamp(message.timestampEpochMs),
                    severity = message.severity ?: Severity.CRITICAL.name,
                    message = message.text,
                    languageCode = message.languageCode ?: "und",
                    locationLatitude = message.locationLatitude,
                    locationLongitude = message.locationLongitude,
                    locationAccuracyMeters = message.locationAccuracyMeters
                )
            } else {
                null
            },
            timestampEpochMs = message.timestampEpochMs,
            conversationOrderEpochMs = message.conversationOrderEpochMs
        )

    private fun storedSentMessage(message: ChatMessageUi): StoredSentMessage =
        StoredSentMessage(
            senderName = message.sender,
            text = message.text,
            timestampEpochMs = message.timestampEpochMs,
            statusText = message.statusText,
            isVoice = message.isVoice,
            isCallMode = message.isCallMode,
            isAlert = message.isAlert,
            severity = message.emergencyData?.severity,
            languageCode = message.emergencyData?.languageCode,
            locationLatitude = message.emergencyData?.locationLatitude,
            locationLongitude = message.emergencyData?.locationLongitude,
            locationAccuracyMeters = message.emergencyData?.locationAccuracyMeters,
            conversationOrderEpochMs = message.conversationOrderEpochMs
        )

    private fun storedMessageToUi(message: StoredReceivedMessage): ChatMessageUi {
        val senderName = localAppDataStore.callsignForPeer(message.senderId)
            ?.takeIf { it.isNotBlank() }
            ?: message.senderName

        val emergencyData = if (message.isAlert) {
            EmergencyAlertData(
                sender = senderName,
                timestampText = formatTimestamp(message.timestampEpochMs),
                severity = message.severity ?: Severity.CRITICAL.name,
                message = message.text,
                languageCode = message.languageCode ?: "und",
                locationLatitude = message.locationLatitude,
                locationLongitude = message.locationLongitude,
                locationAccuracyMeters = message.locationAccuracyMeters
            )
        } else {
            null
        }

        return ChatMessageUi(
            sender = senderName,
            text = message.text,
            timestampText = formatTimestamp(message.timestampEpochMs),
            statusText = if (message.isAlert) "Emergency" else "",
            isVoice = message.isVoice,
            isCallMode = message.isCallMode,
            isAlert = message.isAlert,
            emergencyData = emergencyData,
            timestampEpochMs = message.timestampEpochMs,
            conversationOrderEpochMs = message.receivedAtEpochMs
        )
    }

    private fun formatTimestamp(epochMs: Long): String {
        if (epochMs <= 0L) return "Unknown"
        return SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(epochMs))
    }

    private fun connectedTransportText(
        peer: PeerNodeUi
    ): String {
        val transports = buildList {
            if (
                peer.bleState == BleLinkState.CONNECTED ||
                peer.bleState == BleLinkState.CONNECTING
            ) {
                add("BLE")
            }
            if (peer.wifiDirectState == RadioLinkState.CONNECTED) {
                add("Wi-Fi")
            }
        }

        return if (transports.isEmpty()) {
            "DIRECT"
        } else {
            "DIRECT • " + transports.joinToString(" + ")
        }
    }

    private fun wifiRelayRouteKey(
        removedDeviceId: String,
        relayDeviceId: String
    ): String = removedDeviceId + "|" + relayDeviceId

    private fun clearWifiRelayInvalidationsForPeer(deviceId: String) {
        invalidatedWifiRelayPeerIds.remove(deviceId)
        invalidatedWifiRelayRoutes.keys
            .filter { key -> key.startsWith(deviceId + "|") }
            .forEach { key -> invalidatedWifiRelayRoutes.remove(key) }
    }

    private fun routeLinkText(
        device: DeviceNode,
        knownCallsigns: Map<String, String>
    ): String =
        when (device.link) {
            LinkType.DIRECT -> "DIRECT"
            LinkType.RELAYED -> {
                val intermediateHops = device.path.drop(1)
                if (intermediateHops.isEmpty()) {
                    "RELAYED"
                } else {
                    "VIA " + intermediateHops.joinToString(" → ") { hop ->
                        knownCallsigns[hop.value] ?: hop.value.take(8)
                    }
                }
            }
            LinkType.STALE -> "STALE"
        }

    private fun formatDistance(distance: Double): String =
        if (distance < 0) "Unknown"
        else if (distance < 1000) "${distance.toInt()} m"
        else String.format(Locale.US, "%.1f km", distance / 1000.0)

    private fun signalBars(rssi: Int) = when {
        rssi >= -55 -> 4
        rssi >= -65 -> 3
        rssi >= -75 -> 2
        else -> 1
    }

    override fun onCleared() {
        // The foreground TacticalMeshService owns the singleton discovery
        // lifecycle. Do not stop discovery when the Activity/ViewModel goes
        // away, otherwise background reconnection would silently lose scans.
        healthJob?.cancel()
        audioRecorder.stop()
        reconnectJobs.values.forEach { it.cancel() }
        reconnectJobs.clear()
        super.onCleared()
    }
    companion object {
        private const val MANUAL_SCAN_DISPLAY_MS = 5000L
        private const val WIFI_ROUTE_RESTORE_GRACE_MS = 2000L

        private val SUPPORTED_SPEECH_LANGUAGE_CODES = setOf(
            "hi", "gu", "mr", "kn", "ml",
            "ta", "te", "or", "bn", "en"
        )
    }
}
