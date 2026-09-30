package com.tactical.app.ui.screens

import com.tactical.app.ui.i18n.LocalUiStrings
import com.tactical.app.ui.i18n.UiTextKey
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tactical.app.ui.MainUiState
import com.tactical.app.ui.PeerNodeUi
import com.tactical.app.ui.theme.*
import com.tactical.platform.api.ble.BleLinkState

@Composable
fun DevicesScreen(
    uiState: MainUiState,
    onScan: () -> Unit,
    onAddToSquad: (String) -> Unit = {},
    onEmergencyPress: () -> Unit = {},
    onEmergencyRelease: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var emergencyHeld by remember { mutableStateOf(false) }

    val emergencyHoldProgress by animateFloatAsState(
        targetValue = if (
            emergencyHeld && !uiState.emergencyComposerVisible
        ) {
            1f
        } else {
            0f
        },
        animationSpec = if (emergencyHeld) {
            tween(durationMillis = 2000)
        } else {
            tween(durationMillis = 150)
        },
        label = "devicesEmergencyHoldProgress"
    )

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(SquadBlueBackground)
            .padding(horizontal = 20.dp, vertical = 10.dp)
            .navigationBarsPadding()
    ) {
        EmergencySosButton(
            holdProgress = emergencyHoldProgress,
            enabled = !uiState.emergencyComposerVisible &&
                (
                    uiState.pttSessionState == com.tactical.ptt.session.SessionState.IDLE ||
                        uiState.pttContinuousSession
                    ),
            active = uiState.emergencyComposerVisible,
            onPress = {
                emergencyHeld = true
                onEmergencyPress()
            },
            onRelease = {
                emergencyHeld = false
                onEmergencyRelease()
            }
        )

        Spacer(Modifier.height(14.dp))

        Text(
            text = LocalUiStrings.current.text(UiTextKey.AVAILABLE_DEVICES),
            color = Color.White,
            fontSize = 22.sp,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = 1.sp
        )

        Spacer(Modifier.height(8.dp))

        Button(
            onClick = onScan,
            enabled = !uiState.isScanning,
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = SquadBluePrimary,
                contentColor = Color.White,
                disabledContainerColor = SquadBlueSurfaceRaised,
                disabledContentColor = Color(0xFF8EA8C0)
            )
        ) {
            Icon(
                imageVector = if (uiState.isScanning) {
                    Icons.Default.Sync
                } else {
                    Icons.Default.Search
                },
                contentDescription = null
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = if (uiState.isScanning) LocalUiStrings.current.text(UiTextKey.SCANNING) else LocalUiStrings.current.text(UiTextKey.SCAN),
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = 0.8.sp
            )
        }

        Spacer(Modifier.height(12.dp))

        if (uiState.availablePeers.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.TopCenter
            ) {
                EmptyDevicesState(uiState.isScanning)
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(bottom = 14.dp)
            ) {
                items(
                    uiState.availablePeers,
                    key = { it.deviceAddress }
                ) { peer ->
                    AvailableDeviceCard(peer, onAddToSquad)
                }
            }
        }
    }
}

@Composable
private fun EmergencySosButton(
    holdProgress: Float,
    enabled: Boolean,
    active: Boolean,
    onPress: () -> Unit,
    onRelease: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = LocalUiStrings.current.text(UiTextKey.SEND_ALERT),
            color = Color.White.copy(alpha = 0.88f),
            fontSize = 10.sp,
            lineHeight = 12.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(8.dp))

        Box(
            modifier = Modifier
                .size(176.dp)
                .pointerInput(enabled, active) {
                    if (enabled && !active) {
                        detectTapGestures(
                            onPress = {
                                onPress()
                                try {
                                    awaitRelease()
                                } finally {
                                    onRelease()
                                }
                            }
                        )
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(176.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF210A15).copy(alpha = if (enabled) 1f else 0.78f))
                    .border(
                        width = 1.dp,
                        color = SquadHoldRed.copy(alpha = 0.35f),
                        shape = CircleShape
                    )
                    .drawBehind {
                        if (holdProgress > 0f) {
                            drawArc(
                                color = SquadHoldRedGlow,
                                startAngle = -90f,
                                sweepAngle = 360f * holdProgress,
                                useCenter = false,
                                style = Stroke(
                                    width = 4.dp.toPx(),
                                    cap = StrokeCap.Round
                                )
                            )
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(164.dp)
                        .clip(CircleShape)
                        .background(SquadHoldRed.copy(alpha = 0.11f))
                        .border(
                            width = 2.dp,
                            color = SquadHoldRed.copy(alpha = 0.42f),
                            shape = CircleShape
                        )
                )

                Box(
                    modifier = Modifier
                        .size(148.dp)
                        .clip(CircleShape)
                        .background(SquadHoldRed.copy(alpha = 0.16f))
                        .border(
                            width = 2.dp,
                            color = SquadHoldRedGlow.copy(alpha = 0.50f),
                            shape = CircleShape
                        )
                )

                Box(
                    modifier = Modifier
                        .size(124.dp)
                        .shadow(
                            elevation = 24.dp,
                            shape = CircleShape,
                            clip = false,
                            ambientColor = SquadHoldRedGlow.copy(alpha = 0.72f),
                            spotColor = SquadHoldRedGlow.copy(alpha = 0.92f)
                        )
                        .clip(CircleShape)
                        .background(
                            Brush.radialGradient(
                                colors = listOf(
                                    SquadHoldRedGlow,
                                    SquadHoldRed,
                                    Color(0xFFD91636)
                                )
                            )
                        )
                        .border(
                            width = 2.dp,
                            color = SquadHoldRedGlow.copy(alpha = 0.92f),
                            shape = CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.NotificationsActive,
                            contentDescription = LocalUiStrings.current.text(UiTextKey.EMERGENCY_SOS),
                            tint = Color.White,
                            modifier = Modifier.size(29.dp)
                        )

                        Spacer(Modifier.height(1.dp))

                        Text(
                            text = LocalUiStrings.current.text(UiTextKey.SOS),
                            color = Color.White,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Black,
                            letterSpacing = 1.sp
                        )

                        Spacer(Modifier.height(1.dp))

                        Text(
                            text = if (active) {
                                LocalUiStrings.current.text(UiTextKey.ACTIVE)
                            } else {
                                LocalUiStrings.current.text(UiTextKey.HOLD_TWO_SECONDS)
                            },
                            color = Color.White.copy(alpha = 0.95f),
                            fontSize = 8.sp,
                            lineHeight = 9.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyDevicesState(isScanning: Boolean) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 2.dp),
        color = SquadBlueSurface,
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(
            modifier = Modifier.padding(22.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = if (isScanning) {
                    Icons.Default.Sync
                } else {
                    Icons.Default.Devices
                },
                contentDescription = null,
                tint = SquadBlueGlow,
                modifier = Modifier.size(32.dp)
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = if (isScanning) {
                    LocalUiStrings.current.text(UiTextKey.LOOKING_NEARBY)
                } else {
                    LocalUiStrings.current.text(UiTextKey.NO_DEVICES)
                },
                color = Color.White,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Discovery runs automatically in the background.",
                color = Color(0xFF8EA8C0),
                fontSize = 12.sp,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun AvailableDeviceCard(
    peer: PeerNodeUi,
    onAddToSquad: (String) -> Unit
) {
    Surface(
        color = SquadBlueSurface,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        peer.callsign,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        if (peer.linkText == "DIRECT") {
                            peer.linkText + " • " + peer.distanceText
                        } else {
                            peer.linkText
                        },
                        color = Color(0xFF8EA8C0),
                        fontSize = 11.sp
                    )
                }

                val connectedTransports = buildList {
                    if (peer.bleState == BleLinkState.CONNECTED) add("BLE")
                    if (peer.wifiDirectState == com.tactical.domain.identity.RadioLinkState.CONNECTED) {
                        add("Wi-Fi Direct")
                    }
                }
                val transportStatus = connectedTransports.joinToString(" + ")
                val statusText = when {
                    transportStatus.isNotBlank() ->
                        LocalUiStrings.current.text(UiTextKey.CONNECTED) + " • " + transportStatus
                    peer.bleState == BleLinkState.CONNECTING ||
                        peer.wifiDirectState == com.tactical.domain.identity.RadioLinkState.CONNECTING ->
                        LocalUiStrings.current.text(UiTextKey.CONNECTING)
                    else -> LocalUiStrings.current.text(UiTextKey.AVAILABLE)
                }

                Text(
                    statusText,
                    color = if (peer.isConnected) {
                        RedTacticalStatusGreen
                    } else {
                        RedTacticalStatusYellow
                    },
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(Modifier.height(10.dp))

            Button(
                onClick = { onAddToSquad(peer.deviceAddress) },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = SquadBlueSurfaceRaised,
                    contentColor = Color.White
                )
            ) {
                Icon(
                    imageVector = Icons.Default.Link,
                    contentDescription = null
                )
                Spacer(Modifier.width(5.dp))
                Text(
                    LocalUiStrings.current.text(UiTextKey.ADD_TO_SQUAD),
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 0.5.sp
                )
            }
        }
    }
}
