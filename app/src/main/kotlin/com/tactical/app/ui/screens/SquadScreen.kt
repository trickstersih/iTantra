package com.tactical.app.ui.screens

import com.tactical.app.ui.i18n.LocalUiStrings
import com.tactical.app.ui.i18n.UiTextKey
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
import com.tactical.ptt.session.SessionState
import com.tactical.app.ui.theme.*

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

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(SquadBlueBackground)
            .padding(horizontal = 20.dp, vertical = 12.dp)
            .navigationBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(bottom = 18.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        LocalUiStrings.current.text(UiTextKey.SQUAD),
                        color = Color.White,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 1.sp
                    )
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
                            fontSize = 10.sp,
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = 0.8.sp,
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
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 0.8.sp
                                )
                                if (!uiState.pttEnabled) {
                                    Spacer(Modifier.height(3.dp))
                                    Text(
                                        if (uiState.pttContinuousSession) {
                                            LocalUiStrings.current.text(UiTextKey.LISTENING_CONTINUOUSLY)
                                        } else {
                                            LocalUiStrings.current.text(UiTextKey.CALL_READY)
                                        },
                                        color = Color.White,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }

                            if (uiState.pttTransmissionHistory.isNotEmpty()) {
                                Text(
                                    if (transmissionExpanded) "▲" else "▼",
                                    color = Color(0xFF8EA8C0),
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        if (
                            uiState.pttEnabled &&
                                uiState.pttSessionState == SessionState.RECORDING &&
                                !uiState.pttLastTranscription.isNullOrBlank()
                        ) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                uiState.pttLastTranscription.orEmpty(),
                                color = Color.White,
                                fontSize = 13.sp
                            )
                            Spacer(Modifier.height(6.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    LocalUiStrings.current.text(UiTextKey.RECORDING),
                                    color = RedTacticalVoiceOrange,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    LocalUiStrings.current.text(UiTextKey.LIVE),
                                    color = Color(0xFF8EA8C0),
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        } else if (uiState.pttTransmissionHistory.isNotEmpty()) {
                            Spacer(Modifier.height(8.dp))

                            if (!transmissionExpanded) {
                                val latest = uiState.pttTransmissionHistory.last()
                                Text(
                                    latest.text,
                                    color = Color.White,
                                    fontSize = 13.sp
                                )
                                Spacer(Modifier.height(6.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        latest.statusText,
                                        color = transmissionStatusColor(latest.statusText),
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        formatPttTimestamp(latest.timestampEpochMs),
                                        color = Color(0xFF8EA8C0),
                                        fontSize = 10.sp
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
                                                        fontSize = 13.sp
                                                    )
                                                    Spacer(Modifier.height(3.dp))
                                                    Text(
                                                        formatPttTimestamp(transmission.timestampEpochMs),
                                                        color = Color(0xFF8EA8C0),
                                                        fontSize = 9.sp
                                                    )
                                                }
                                                Spacer(Modifier.width(10.dp))
                                                Text(
                                                    transmission.statusText,
                                                    color = transmissionStatusColor(
                                                        transmission.statusText
                                                    ),
                                                    fontSize = 10.sp,
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
                                        fontSize = 12.sp
                                    )
                                }
                                ?: Text(
                                    LocalUiStrings.current.text(UiTextKey.SPEAK_NORMALLY),
                                    color = Color(0xFF8EA8C0),
                                    fontSize = 10.sp
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
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.8.sp
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            if (uiState.languageLoadingCode != null) {
                                LocalUiStrings.current.text(UiTextKey.LOADING)
                            } else {
                                uiState.selectedLanguage
                            },
                            color = Color.White,
                            fontSize = 14.sp,
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
                        modifier = Modifier.size(222.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            Modifier
                                .size(216.dp)
                                .border(
                                    1.5.dp,
                                    pttGlowColor.copy(alpha = 0.25f),
                                    CircleShape
                                )
                        )
                        Box(
                            Modifier
                                .size(202.dp)
                                .border(
                                    2.dp,
                                    pttGlowColor.copy(alpha = 0.45f),
                                    CircleShape
                                )
                        )
                        Box(
                            modifier = Modifier
                                .size(186.dp)
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
                                                        totalDragX > 80.dp.toPx() &&
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
                                    modifier = Modifier.size(44.dp)
                                )
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    pttLabel,
                                    color = Color.White,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    letterSpacing = 1.sp,
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
                    fontSize = 10.sp,
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
                                fontSize = 13.sp,
                                fontWeight = FontWeight.ExtraBold,
                                letterSpacing = 1.sp
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                LocalUiStrings.current.text(UiTextKey.CALL_MODE_VOICE),
                                color = Color(0xFF8EA8C0),
                                fontSize = 10.sp
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
                        modifier = Modifier.size(21.dp)
                    )
                }
            }
        }

        if (connectedPeers.isEmpty()) {
            item {
                EmptySquadSection(LocalUiStrings.current.text(UiTextKey.NO_CONNECTED_DEVICES))
            }
        } else {
            items(
                connectedPeers,
                key = { "connected_" + it.deviceAddress }
            ) { peer ->
                PeerCard(
                    peer = peer,
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
            SectionDividerLabel("OFFLINE MEMBERS (${squadOfflinePeers.size})")
        }

        if (squadOfflinePeers.isEmpty()) {
            item {
                EmptySquadSection(
                    if (uiState.squadPeers.isEmpty()) {
                        LocalUiStrings.current.text(UiTextKey.NO_SQUAD_MEMBERS)
                    } else {
                        LocalUiStrings.current.text(UiTextKey.ALL_SQUAD_CONNECTED)
                    }
                )
            }
        } else {
            items(
                squadOfflinePeers,
                key = { "squad_" + it.deviceAddress }
            ) { peer ->
                PeerCard(
                    peer = peer,
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
    if (languagePickerVisible) {
        Dialog(
            onDismissRequest = { languagePickerVisible = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth(0.92f),
                shape = RoundedCornerShape(20.dp),
                color = Color(0xFFF7FAFE)
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp)
                ) {
                    Text(
                        LocalUiStrings.current.text(UiTextKey.SELECT_LANGUAGE),
                        color = Color(0xFF10243A),
                        fontSize = 20.sp,
                        fontWeight = FontWeight.ExtraBold
                    )

                    Spacer(Modifier.height(14.dp))

                    Text(
                        LocalUiStrings.current.text(UiTextKey.INDIAN_LANGUAGES),
                        color = Color(0xFF54708C),
                        fontSize = 11.sp,
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
                        fontSize = 11.sp,
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
                        Text(LocalUiStrings.current.text(UiTextKey.CONFIRM), fontWeight = FontWeight.ExtraBold)
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
    SquadLanguageOption("gu", "Gujarati", "ગુજરાતી", false),
    SquadLanguageOption("mr", "Marathi", "मराठी", false),
    SquadLanguageOption("kn", "Kannada", "ಕನ್ನಡ", false),
    SquadLanguageOption("ml", "Malayalam", "മലയാളം", false),
    SquadLanguageOption("ta", "Tamil", "தமிழ்", false),
    SquadLanguageOption("te", "Telugu", "తెలుగు", false),
    SquadLanguageOption("or", "Odia", "ଓଡ଼ିଆ", false),
    SquadLanguageOption("bn", "Bengali", "বাংলা", false),
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
            fontSize = 10.sp,
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
private fun EmptySquadSection(message: String) {
    Text(
        text = message,
        color = Color(0xFF8EA8C0),
        fontSize = 12.sp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 14.dp),
        textAlign = TextAlign.Center
    )
}

@Composable
fun PeerCard(
    peer: PeerNodeUi,
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
                    .padding(horizontal = 4.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(38.dp)
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
                        modifier = Modifier.size(20.dp)
                    )
                }

                Spacer(Modifier.width(12.dp))

                Column(Modifier.weight(1f)) {
                    Text(
                        peer.callsign,
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        if (peer.isConnected) {
                            LocalUiStrings.current.text(UiTextKey.CONNECTED_DOT) + peer.distanceText
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
                        fontSize = 11.sp
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
                        fontSize = 10.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 0.4.sp
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
