package com.tactical.app.ui.screens

import com.tactical.app.ui.i18n.LocalUiStrings
import com.tactical.app.ui.i18n.UiTextKey
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.shadow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tactical.app.ui.MainUiState
import com.tactical.app.ui.PeerNodeUi
import com.tactical.app.ui.ResponsiveScreen
import com.tactical.app.ui.ResponsiveUi
import com.tactical.ptt.session.SessionState
import com.tactical.app.ui.theme.*
import com.tactical.platform.api.ble.BleLinkState

@Composable
fun SquadScreen(
    uiState: MainUiState,
    onLanguageSelected: (String) -> Unit = {},
    onPttToggle: () -> Unit,
    onPttPress: () -> Unit,
    onPttRelease: () -> Unit,
    onPttCancel: () -> Unit,
    onRemoveFromSquad: (String) -> Unit = {},
    onRefreshDiscovery: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val connectedPeers = uiState.squadPeers.filter { it.isConnected }
    val squadOfflinePeers = uiState.squadPeers.filter { !it.isConnected }
    val hasConnection = connectedPeers.isNotEmpty()
    val pttButtonEnabled = uiState.pttEnabled &&
        hasConnection &&
        uiState.pttSessionState != SessionState.TRANSMITTING &&
        uiState.pttSessionState != SessionState.ARMED

    var pttHeld by remember { mutableStateOf(false) }
    var removeArmedDeviceId by rememberSaveable { mutableStateOf<String?>(null) }
    var languagePickerVisible by rememberSaveable { mutableStateOf(false) }
    var wifiGroupInfoVisible by rememberSaveable { mutableStateOf(false) }
    var pendingLanguageCode by rememberSaveable {
        mutableStateOf(uiState.selectedLanguageCode)
    }



    val pttLabel = when (uiState.pttSessionState) {
        SessionState.ARMED -> LocalUiStrings.current.text(UiTextKey.STARTING)
        SessionState.RECORDING -> LocalUiStrings.current.text(UiTextKey.RECORDING)
        SessionState.TRANSMITTING -> LocalUiStrings.current.text(UiTextKey.SENDING)
        else -> if (hasConnection) LocalUiStrings.current.text(UiTextKey.PUSH_TO_TALK) else LocalUiStrings.current.text(UiTextKey.NO_CONNECTION)
    }

    val pttHint = when (uiState.pttSessionState) {
        SessionState.RECORDING -> LocalUiStrings.current.text(UiTextKey.RELEASE_TO_SEND)
        SessionState.TRANSMITTING -> LocalUiStrings.current.text(UiTextKey.TRANSCRIBING_SENDING)
        else -> if (hasConnection) {
            LocalUiStrings.current.text(UiTextKey.HOLD_TO_RECORD)
        } else {
            LocalUiStrings.current.text(UiTextKey.CONNECT_SQUAD_MEMBER)
        }
    }

    ResponsiveScreen(
        modifier = modifier.fillMaxSize()
    ) { ui ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(SquadBlueBackground)
                .padding(
                    horizontal = ui.horizontalPadding,
                    vertical = ui.dp(12.dp)
                )
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(ui.smallSpacing),
            contentPadding = PaddingValues(bottom = ui.dp(18.dp))
        ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            LocalUiStrings.current.text(UiTextKey.SQUAD),
                            color = Color.White,
                            fontSize = ui.sp(22f),
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = ui.sp(1f)
                        )

                        if (uiState.wifiDirectGroupFormed) {
                            Spacer(Modifier.width(8.dp))

                            Surface(
                                color = SquadBlueSurfaceRaised,
                                shape = RoundedCornerShape(7.dp),
                                border = androidx.compose.foundation.BorderStroke(
                                    1.dp,
                                    if (uiState.wifiDirectIsGroupOwner) {
                                        SquadBlueGlow
                                    } else {
                                        SquadBlueBorder
                                    }
                                )
                            ) {
                                Text(
                                    if (uiState.wifiDirectIsGroupOwner) {
                                        "WI-FI HEAD"
                                    } else {
                                        "WI-FI MEMBER"
                                    },
                                    color = if (uiState.wifiDirectIsGroupOwner) {
                                        Color.White
                                    } else {
                                        Color(0xFFBFD6EA)
                                    },
                                    fontSize = ui.sp(7f),
                                    maxLines = 1,
                                    softWrap = false,
                                    fontWeight = FontWeight.ExtraBold,
                                    letterSpacing = ui.sp(0.5f),
                                    modifier = Modifier.padding(
                                        horizontal = ui.dp(6.dp),
                                        vertical = ui.dp(3.dp)
                                    )
                                )
                            }

                            IconButton(
                                onClick = { wifiGroupInfoVisible = true },
                                modifier = Modifier.size(ui.dp(30.dp))
                            ) {
                                Text(
                                    "ⓘ",
                                    color = Color(0xFFBFD6EA),
                                    fontSize = ui.sp(19f),
                                    fontWeight = FontWeight.ExtraBold
                                )
                            }
                        }
                    }
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        onClick = onPttToggle,
                        enabled = (
                            uiState.pttSessionState == SessionState.IDLE ||
                                uiState.pttContinuousSession
                            ) && (uiState.pttEnabled || hasConnection),
                        color = if (uiState.pttEnabled) {
                            SquadBluePrimary
                        } else {
                            SquadBlueSurface
                        },
                        shape = RoundedCornerShape(8.dp),
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            if (uiState.pttEnabled) {
                                SquadBlueGlow
                            } else {
                                SquadBlueBorder
                            }
                        )
                    ) {
                        Text(
                            if (uiState.pttEnabled) LocalUiStrings.current.text(UiTextKey.SWITCH_TO_CALL_MODE) else LocalUiStrings.current.text(UiTextKey.SWITCH_TO_PTT_MODE),
                            color = Color.White,
                            fontSize = ui.sp(10f),
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = ui.sp(0.8f),
                            modifier = Modifier.padding(
                                horizontal = 12.dp,
                                vertical = 7.dp
                            )
                        )
                    }
                }
            }
        }

        item {
            var transmissionExpanded by rememberSaveable { mutableStateOf(false) }

            if (
                uiState.pttTransmissionHistory.isNotEmpty() ||
                !uiState.pttEnabled ||
                (
                    uiState.pttEnabled &&
                        uiState.pttSessionState == SessionState.RECORDING &&
                        !uiState.pttLastTranscription.isNullOrBlank()
                    )
            ) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = SquadBlueSurface
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = uiState.pttTransmissionHistory.isNotEmpty()) {
                            transmissionExpanded = !transmissionExpanded
                        }
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    when {
                                        !uiState.pttEnabled ->
                                            LocalUiStrings.current.text(UiTextKey.CALL_MODE_VOICE)
                                        uiState.pttSessionState == SessionState.RECORDING ->
                                            LocalUiStrings.current.text(UiTextKey.LIVE_PTT_TRANSCRIPTION)
                                        else ->
                                            LocalUiStrings.current.text(UiTextKey.LAST_TRANSMISSION)
                                    },
                                    color = Color(0xFF8EA8C0),
                                    fontSize = ui.sp(9f),
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = ui.sp(0.8f)
                                )
                                if (!uiState.pttEnabled) {
                                    Spacer(Modifier.height(ui.dp(3.dp)))
                                    Text(
                                        if (uiState.pttContinuousSession) {
                                            LocalUiStrings.current.text(UiTextKey.LISTENING_CONTINUOUSLY)
                                        } else {
                                            LocalUiStrings.current.text(UiTextKey.CALL_READY)
                                        },
                                        color = Color.White,
                                        fontSize = ui.sp(11f),
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }

                            if (uiState.pttTransmissionHistory.isNotEmpty()) {
                                Text(
                                    if (transmissionExpanded) "▲" else "▼",
                                    color = Color(0xFF8EA8C0),
                                    fontSize = ui.sp(13f),
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        if (
                            uiState.pttEnabled &&
                                uiState.pttSessionState == SessionState.RECORDING &&
                                !uiState.pttLastTranscription.isNullOrBlank()
                        ) {
                            Spacer(Modifier.height(ui.smallSpacing))
                            Text(
                                uiState.pttLastTranscription.orEmpty(),
                                color = Color.White,
                                fontSize = ui.sp(13f)
                            )
                            Spacer(Modifier.height(6.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    LocalUiStrings.current.text(UiTextKey.RECORDING),
                                    color = RedTacticalVoiceOrange,
                                    fontSize = ui.sp(10f),
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    LocalUiStrings.current.text(UiTextKey.LIVE),
                                    color = Color(0xFF8EA8C0),
                                    fontSize = ui.sp(10f),
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        } else if (uiState.pttTransmissionHistory.isNotEmpty()) {
                            Spacer(Modifier.height(8.dp))

                            if (!transmissionExpanded) {
                                val latest = if (uiState.pttContinuousSession) {
                                    uiState.pttTransmissionHistory.last()
                                } else {
                                    uiState.pttTransmissionHistory.first()
                                }
                                Text(
                                    latest.text,
                                    color = Color.White,
                                    fontSize = ui.sp(13f)
                                )
                                Spacer(Modifier.height(6.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        latest.statusText,
                                        color = transmissionStatusColor(latest.statusText),
                                        fontSize = ui.sp(10f),
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        formatPttTimestamp(latest.timestampEpochMs),
                                        color = Color(0xFF8EA8C0),
                                        fontSize = ui.sp(10f)
                                    )
                                }
                            } else {
                                uiState.pttTransmissionHistory
                                    .asReversed()
                                    .forEachIndexed { index, transmission ->
                                        Column(
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                verticalAlignment = Alignment.Top
                                            ) {
                                                Column(Modifier.weight(1f)) {
                                                    Text(
                                                        transmission.text,
                                                        color = Color.White,
                                                        fontSize = ui.sp(13f)
                                                    )
                                                    Spacer(Modifier.height(3.dp))
                                                    Text(
                                                        formatPttTimestamp(transmission.timestampEpochMs),
                                                        color = Color(0xFF8EA8C0),
                                                        fontSize = ui.sp(9f)
                                                    )
                                                }
                                                Spacer(Modifier.width(10.dp))
                                                Text(
                                                    transmission.statusText,
                                                    color = transmissionStatusColor(
                                                        transmission.statusText
                                                    ),
                                                    fontSize = ui.sp(10f),
                                                    fontWeight = FontWeight.Bold
                                                )
                                            }

                                            if (
                                                index <
                                                uiState.pttTransmissionHistory.lastIndex
                                            ) {
                                                Spacer(Modifier.height(8.dp))
                                                HorizontalDivider(
                                                    color = SquadBlueBorder
                                                )
                                                Spacer(Modifier.height(8.dp))
                                            }
                                        }
                                    }
                            }
                        } else if (!uiState.pttEnabled) {
                            Spacer(Modifier.height(7.dp))
                            uiState.pttLastTranscription
                                ?.takeIf { it.isNotBlank() }
                                ?.let { liveText ->
                                    Text(
                                        "LIVE: $liveText",
                                        color = Color.White,
                                        fontSize = ui.sp(12f)
                                    )
                                }
                                ?: Text(
                                    LocalUiStrings.current.text(UiTextKey.SPEAK_NORMALLY),
                                    color = Color(0xFF8EA8C0),
                                    fontSize = ui.sp(10f)
                                )
                        }
                    }
                }

                Spacer(Modifier.height(6.dp))
            }

            Spacer(Modifier.height(2.dp))

            Surface(
                onClick = {
                    pendingLanguageCode = uiState.selectedLanguageCode
                    languagePickerVisible = true
                },
                color = SquadBlueSurface,
                shape = RoundedCornerShape(22.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, SquadBlueBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            LocalUiStrings.current.text(UiTextKey.LANGUAGE),
                            color = Color(0xFF8EA8C0),
                            fontSize = ui.sp(9f),
                            fontWeight = FontWeight.Bold,
                            letterSpacing = ui.sp(0.8f)
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            if (uiState.languageLoadingCode != null) {
                                LocalUiStrings.current.text(UiTextKey.LOADING)
                            } else {
                                uiState.selectedLanguage
                            },
                            color = Color.White,
                            fontSize = ui.sp(14f),
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    if (uiState.languageLoadingCode != null) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            color = SquadBlueGlow,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(
                            Icons.Default.ChevronRight,
                            contentDescription = LocalUiStrings.current.text(UiTextKey.SELECT_LANGUAGE),
                            tint = Color(0xFFBFD6EA)
                        )
                    }
                }
            }

            if (uiState.pttEnabled) {
                Spacer(Modifier.height(10.dp))

                val pttButtonColor = when {
                    uiState.pttSessionState == SessionState.TRANSMITTING -> SquadTransmitOrange
                    pttHeld -> SquadHoldRed
                    !pttButtonEnabled -> SquadBlueSurfaceRaised
                    else -> SquadBluePrimary
                }
                val pttGlowColor = when {
                    uiState.pttSessionState == SessionState.TRANSMITTING -> SquadTransmitGlow
                    pttHeld -> SquadHoldRedGlow
                    !pttButtonEnabled -> SquadBlueBorder
                    else -> SquadBlueGlow
                }

                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier.size(ui.pttOuter),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            Modifier
                                .size(ui.pttRingInner)
                                .border(
                                    1.5.dp,
                                    pttGlowColor.copy(alpha = 0.25f),
                                    CircleShape
                                )
                        )
                        Box(
                            Modifier
                                .size(ui.pttRingOuter)
                                .border(
                                    2.dp,
                                    pttGlowColor.copy(alpha = 0.45f),
                                    CircleShape
                                )
                        )
                        Box(
                            modifier = Modifier
                                .size(ui.pttCore)
                                .shadow(
                                    elevation = 28.dp,
                                    shape = CircleShape,
                                    clip = false,
                                    ambientColor = pttGlowColor.copy(alpha = 0.70f),
                                    spotColor = pttGlowColor.copy(alpha = 0.90f)
                                )
                                .clip(CircleShape)
                                .background(
                                    Brush.radialGradient(
                                        colors = listOf(
                                            pttGlowColor.copy(alpha = 0.88f),
                                            pttButtonColor,
                                            pttButtonColor.copy(alpha = 0.82f)
                                        )
                                    )
                                )
                                .border(
                                    2.dp,
                                    pttGlowColor.copy(alpha = 0.95f),
                                    CircleShape
                                )
                                .then(
                                    if (pttButtonEnabled) {
                                        Modifier.pointerInput(Unit) {
                                            var cancelled = false
                                            var totalDragX = 0f
                                            var totalDragY = 0f

                                            detectDragGesturesAfterLongPress(
                                                onDragStart = {
                                                    cancelled = false
                                                    totalDragX = 0f
                                                    totalDragY = 0f
                                                    pttHeld = true
                                                    onPttPress()
                                                },
                                                onDrag = { change, dragAmount ->
                                                    totalDragX += dragAmount.x
                                                    totalDragY += dragAmount.y

                                                    if (
                                                        !cancelled &&
                                                        totalDragX > ui.dp(80.dp).toPx() &&
                                                        totalDragX > kotlin.math.abs(totalDragY) * 1.2f
                                                    ) {
                                                        cancelled = true
                                                        pttHeld = false
                                                        onPttCancel()
                                                    }

                                                    change.consume()
                                                },
                                                onDragEnd = {
                                                    if (!cancelled && pttHeld) {
                                                        pttHeld = false
                                                        onPttRelease()
                                                    }
                                                },
                                                onDragCancel = {
                                                    if (!cancelled && pttHeld) {
                                                        pttHeld = false
                                                        onPttCancel()
                                                    }
                                                }
                                            )
                                        }
                                    } else {
                                        Modifier
                                    }
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    Icons.Default.Mic,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(ui.dp(44.dp))
                                )
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    pttLabel,
                                    color = Color.White,
                                    fontSize = ui.sp(13f),
                                    fontWeight = FontWeight.ExtraBold,
                                    letterSpacing = ui.sp(1f),
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))

                Text(
                    pttHint,
                    color = Color(0xFF8EA8C0),
                    fontSize = ui.sp(10f),
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )
            } else {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = SquadBlueSurface
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Mic,
                            contentDescription = null,
                            tint = SquadBlueGlow,
                            modifier = Modifier.size(26.dp)
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                LocalUiStrings.current.text(UiTextKey.CALL_MODE),
                                color = Color.White,
                                fontSize = ui.sp(13f),
                                fontWeight = FontWeight.ExtraBold,
                                letterSpacing = ui.sp(1f)
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                LocalUiStrings.current.text(UiTextKey.CALL_MODE_VOICE),
                                color = Color(0xFF8EA8C0),
                                fontSize = ui.sp(10f)
                            )
                        }
                    }
                }
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SectionDividerLabel(
                    "${connectedPeers.size} CONNECTED DEVICES",
                    ui = ui,
                    modifier = Modifier.weight(1f)
                )

                IconButton(
                    onClick = onRefreshDiscovery,
                    enabled = !uiState.isScanning
                ) {
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = LocalUiStrings.current.text(UiTextKey.SCAN),
                        tint = if (uiState.isScanning) {
                            Color(0xFF5F7890)
                        } else {
                            Color(0xFFBFD6EA)
                        },
                        modifier = Modifier.size(ui.compactIcon)
                    )
                }
            }
        }

        if (connectedPeers.isEmpty()) {
            item {
                EmptySquadSection(
                    LocalUiStrings.current.text(UiTextKey.NO_CONNECTED_DEVICES),
                    ui = ui
                )
            }
        } else {
            items(
                connectedPeers,
                key = { "connected_" + it.deviceAddress }
            ) { peer ->
                PeerCard(
                    ui = ui,
                    peer = peer,
                    isWifiGroupHead = uiState.wifiDirectGroupFormed &&
                        peer.deviceAddress.equals(
                            uiState.wifiDirectGroupOwnerAppDeviceId,
                            ignoreCase = true
                        ),
                    removeArmed = removeArmedDeviceId == peer.deviceAddress,
                    onLongPress = { removeArmedDeviceId = peer.deviceAddress },
                    onRemove = {
                        onRemoveFromSquad(peer.deviceAddress)
                        removeArmedDeviceId = null
                    },
                    onDismissRemove = { removeArmedDeviceId = null }
                )
            }
        }

        item {
            Spacer(Modifier.height(2.dp))
            SectionDividerLabel(
                "OFFLINE MEMBERS (${squadOfflinePeers.size})",
                ui = ui
            )
        }

        if (squadOfflinePeers.isEmpty()) {
            item {
                EmptySquadSection(
                    if (uiState.squadPeers.isEmpty()) {
                        LocalUiStrings.current.text(UiTextKey.NO_SQUAD_MEMBERS)
                    } else {
                        LocalUiStrings.current.text(UiTextKey.ALL_SQUAD_CONNECTED)
                    },
                    ui = ui
                )
            }
        } else {
            items(
                squadOfflinePeers,
                key = { "squad_" + it.deviceAddress }
            ) { peer ->
                PeerCard(
                    ui = ui,
                    peer = peer,
                    isWifiGroupHead = uiState.wifiDirectGroupFormed &&
                        peer.deviceAddress.equals(
                            uiState.wifiDirectGroupOwnerAppDeviceId,
                            ignoreCase = true
                        ),
                    removeArmed = removeArmedDeviceId == peer.deviceAddress,
                    onLongPress = { removeArmedDeviceId = peer.deviceAddress },
                    onRemove = {
                        onRemoveFromSquad(peer.deviceAddress)
                        removeArmedDeviceId = null
                    },
                    onDismissRemove = { removeArmedDeviceId = null }
                )
            }
        }
    }
}

    if (wifiGroupInfoVisible) {
        AlertDialog(
            onDismissRequest = { wifiGroupInfoVisible = false },
            title = {
                Text("HOW WI-FI GROUPS WORK")
            },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState())
                ) {
                    Text(
                        "Wi-Fi Direct creates a group with one Wi-Fi group head " +
                            "(the Android group owner) and other phones as members."
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Phones in the same group can communicate over Wi-Fi Direct. " +
                            "When a phone joins an existing group, it uses that group's " +
                            "Wi-Fi connection instead of creating a separate group."
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Two separate Wi-Fi groups do not automatically communicate " +
                            "with each other. In iTantra, devices in separate groups " +
                            "can still communicate through Bluetooth (BLE)."
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "A phone can belong to only one Wi-Fi Direct group at a time. " +
                            "To move to another Wi-Fi group, its current group must be " +
                            "left or disconnected first. iTantra does not automatically " +
                            "break an existing group just to move a phone to another one."
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "The Wi-Fi group head is only the Wi-Fi Direct owner. " +
                            "It is not the squad leader and does not control the squad."
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { wifiGroupInfoVisible = false }
                ) {
                    Text("OK")
                }
            }
        )
    }

    if (languagePickerVisible) {
        Dialog(
            onDismissRequest = { languagePickerVisible = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
            ) {
                val dialogWidth = minOf(maxWidth * 0.94f, 420.dp)
                val dialogMaxHeight = maxHeight * 0.86f

                Surface(
                    modifier = Modifier
                        .width(dialogWidth)
                        .heightIn(max = dialogMaxHeight),
                    shape = RoundedCornerShape(20.dp),
                    color = Color(0xFFF7FAFE)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 18.dp, vertical = 16.dp)
                    ) {
                        Text(
                            LocalUiStrings.current.text(UiTextKey.SELECT_LANGUAGE),
                            color = Color(0xFF10243A),
                            fontSize = ui.sp(20f),
                            fontWeight = FontWeight.ExtraBold
                        )

                        Spacer(Modifier.height(14.dp))

                        Text(
                            LocalUiStrings.current.text(UiTextKey.INDIAN_LANGUAGES),
                            color = Color(0xFF54708C),
                            fontSize = ui.sp(11f),
                            fontWeight = FontWeight.Bold
                        )

                        SQUAD_LANGUAGE_OPTIONS
                            .filter { it.code != "en" }
                            .forEach { option ->
                                SquadLanguageRow(
                                    option = option,
                                    selected = pendingLanguageCode == option.code,
                                    onSelect = { pendingLanguageCode = option.code }
                                )
                            }

                        Spacer(Modifier.height(8.dp))

                        Text(
                            LocalUiStrings.current.text(UiTextKey.OTHER),
                            color = Color(0xFF54708C),
                            fontSize = ui.sp(11f),
                            fontWeight = FontWeight.Bold
                        )

                        SquadLanguageRow(
                            option = SQUAD_LANGUAGE_OPTIONS.first { it.code == "en" },
                            selected = pendingLanguageCode == "en",
                            onSelect = { pendingLanguageCode = "en" }
                        )

                        Spacer(Modifier.height(12.dp))

                        Button(
                            onClick = {
                                languagePickerVisible = false
                                onLanguageSelected(pendingLanguageCode)
                            },
                            enabled = SQUAD_LANGUAGE_OPTIONS.any {
                                it.code == pendingLanguageCode && it.available
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text(
                                LocalUiStrings.current.text(UiTextKey.CONFIRM),
                                fontWeight = FontWeight.ExtraBold
                            )
                        }
                    }
                }
            }
        }
    }
}

private data class SquadLanguageOption(
    val code: String,
    val name: String,
    val nativeName: String,
    val available: Boolean
)

private val SQUAD_LANGUAGE_OPTIONS = listOf(
    SquadLanguageOption("hi", "Hindi", "हिन्दी", true),
    SquadLanguageOption("gu", "Gujarati", "ગુજરાતી", true),
    SquadLanguageOption("mr", "Marathi", "मराठी", true),
    SquadLanguageOption("kn", "Kannada", "ಕನ್ನಡ", true),
    SquadLanguageOption("ml", "Malayalam", "മലയാളം", true),
    SquadLanguageOption("ta", "Tamil", "தமிழ்", true),
    SquadLanguageOption("te", "Telugu", "తెలుగు", true),
    SquadLanguageOption("or", "Odia", "ଓଡ଼ିଆ", true),
    SquadLanguageOption("bn", "Bengali", "বাংলা", true),
    SquadLanguageOption("en", "English", "English", true)
)

@Composable
private fun SquadLanguageRow(
    option: SquadLanguageOption,
    selected: Boolean,
    onSelect: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = option.available, onClick = onSelect)
            .padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                option.nativeName,
                color = if (option.available) Color(0xFF10243A) else Color(0xFFA4B2BF),
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                option.name,
                color = if (option.available) Color(0xFF54708C) else Color(0xFFB3BEC8),
                fontSize = 10.sp
            )
        }

        RadioButton(
            selected = selected,
            onClick = onSelect,
            enabled = option.available
        )
    }
}


@Composable
private fun formatPttTimestamp(epochMs: Long): String =
    if (epochMs > 0L) {
        java.text.SimpleDateFormat(
            "HH:mm",
            java.util.Locale.getDefault()
        ).format(java.util.Date(epochMs))
    } else {
        LocalUiStrings.current.text(UiTextKey.UNKNOWN)
    }

@Composable
private fun transmissionStatusColor(status: String): Color =
    when (status) {
        LocalUiStrings.current.text(UiTextKey.SENT) -> RedTacticalStatusGreen
        LocalUiStrings.current.text(UiTextKey.SENDING_DOT) -> RedTacticalPrimaryBright
        else -> Color(0xFF8EA8C0)
    }

@Composable
private fun SectionDividerLabel(
    label: String,
    ui: ResponsiveUi,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        HorizontalDivider(
            modifier = Modifier.weight(1f),
            color = SquadBlueBorder
        )
        Text(
            text = label,
            color = Color(0xFF8EA8C0),
            fontSize = ui.sp(10f),
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.9.sp,
            modifier = Modifier.padding(horizontal = 10.dp)
        )
        HorizontalDivider(
            modifier = Modifier.weight(1f),
            color = SquadBlueBorder
        )
    }
}

@Composable
private fun EmptySquadSection(
    message: String,
    ui: ResponsiveUi
) {
    Text(
        text = message,
        color = Color(0xFF8EA8C0),
        fontSize = ui.sp(12f),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 14.dp),
        textAlign = TextAlign.Center
    )
}

@Composable
fun PeerCard(
    ui: ResponsiveUi,
    peer: PeerNodeUi,
    isWifiGroupHead: Boolean = false,
    removeArmed: Boolean = false,
    onLongPress: () -> Unit = {},
    onRemove: () -> Unit = {},
    onDismissRemove: () -> Unit = {}
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .pointerInput(peer.deviceAddress) {
                detectTapGestures(
                    onLongPress = { onLongPress() }
                )
            }
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        horizontal = ui.dp(4.dp),
                        vertical = ui.dp(14.dp)
                    ),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(ui.dp(38.dp))
                        .clip(CircleShape)
                        .background(SquadBlueSurfaceRaised)
                        .border(
                            1.dp,
                            if (peer.isConnected) {
                                SquadBlueGlow.copy(alpha = 0.75f)
                            } else {
                                SquadBlueBorder
                            },
                            CircleShape
                        )
                ) {
                    Icon(
                        Icons.Default.Person,
                        contentDescription = peer.callsign,
                        tint = Color.White,
                        modifier = Modifier.size(ui.dp(20.dp))
                    )
                }

                Spacer(Modifier.width(ui.dp(12.dp)))

                Column(Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            peer.callsign,
                            color = Color.White,
                            fontSize = ui.sp(14f),
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f)
                        )

                        if (isWifiGroupHead) {
                            Spacer(Modifier.width(7.dp))
                            Surface(
                                color = SquadBluePrimary.copy(alpha = 0.28f),
                                shape = RoundedCornerShape(6.dp),
                                border = androidx.compose.foundation.BorderStroke(
                                    1.dp,
                                    SquadBlueGlow.copy(alpha = 0.65f)
                                )
                            ) {
                                Text(
                                    "GROUP HEAD",
                                    color = Color.White,
                                    fontSize = ui.sp(7f),
                                    fontWeight = FontWeight.ExtraBold,
                                    letterSpacing = ui.sp(0.45f),
                                    modifier = Modifier.padding(
                                        horizontal = ui.dp(6.dp),
                                        vertical = ui.dp(3.dp)
                                    )
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(3.dp))
                    val connectedDetail = when {
                        peer.wifiDirectState == com.tactical.domain.identity.RadioLinkState.CONNECTED &&
                            peer.bleState == BleLinkState.CONNECTED ->
                            "Wi-Fi Direct + BLE"
                        peer.wifiDirectState == com.tactical.domain.identity.RadioLinkState.CONNECTED ->
                            "Wi-Fi Direct"
                        peer.bleState == BleLinkState.CONNECTED ->
                            if (peer.distanceText != "Unknown") {
                                "BLE • " + peer.distanceText
                            } else {
                                "BLE"
                            }
                        peer.linkText != "DIRECT" -> peer.linkText
                        else -> peer.distanceText
                    }

                    Text(
                        if (peer.isConnected) {
                            LocalUiStrings.current.text(UiTextKey.CONNECTED_DOT) + connectedDetail
                        } else if (peer.linkText == "DIRECT") {
                            LocalUiStrings.current.text(UiTextKey.IN_SQUAD_DOT) + peer.distanceText
                        } else {
                            LocalUiStrings.current.text(UiTextKey.IN_SQUAD_DOT) + peer.linkText
                        },
                        color = if (peer.isConnected) {
                            RedTacticalStatusGreen
                        } else {
                            Color(0xFF8EA8C0)
                        },
                        fontSize = ui.sp(11f)
                    )
                }

                Icon(
                    Icons.Default.SignalCellularAlt,
                    contentDescription = LocalUiStrings.current.text(UiTextKey.SIGNAL),
                    tint = if (peer.isConnected) {
                        RedTacticalStatusGreen
                    } else {
                        Color(0xFF8EA8C0)
                    },
                    modifier = Modifier.size(20.dp)
                )
            }

            HorizontalDivider(
                modifier = Modifier.fillMaxWidth(),
                color = SquadBlueBorder.copy(alpha = 0.75f),
                thickness = 1.dp
            )
        }

        DropdownMenu(
            expanded = removeArmed,
            onDismissRequest = onDismissRemove,
            modifier = Modifier.widthIn(min = 170.dp),
            containerColor = SquadBlueSurfaceRaised,
            shape = RoundedCornerShape(10.dp),
            shadowElevation = 8.dp
        ) {
            DropdownMenuItem(
                text = {
                    Text(
                        LocalUiStrings.current.text(UiTextKey.REMOVE_FROM_SQUAD),
                        color = SquadHoldRedGlow,
                        fontSize = ui.sp(10f),
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = ui.sp(0.4f)
                    )
                },
                onClick = onRemove,
                contentPadding = PaddingValues(
                    horizontal = 14.dp,
                    vertical = 4.dp
                )
            )
        }
    }
}
