package com.tactical.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import com.tactical.app.ui.theme.RedTacticalTextSecondary
import com.tactical.app.ui.i18n.LocalUiStrings
import com.tactical.app.ui.i18n.UiTextKey
import com.tactical.app.ui.ResponsiveScreen
import com.tactical.app.ui.theme.SquadBlueBackground
import com.tactical.app.ui.theme.SquadBlueBorder
import com.tactical.app.ui.theme.SquadBlueGlow
import com.tactical.app.ui.theme.SquadBluePrimary
import com.tactical.app.ui.theme.SquadBlueSurface

@Composable
fun SettingsScreen(
    uiLanguageCode: String,
    onUiLanguageSelected: (String) -> Unit,
    ttsPlaybackMode: com.tactical.platform.speech.mms.MmsTtsPlaybackMode,
    onTtsPlaybackModeSelected: (com.tactical.platform.speech.mms.MmsTtsPlaybackMode) -> Unit,
    username: String,
    onUsernameSave: (String) -> String?,
    modifier: Modifier = Modifier
) {
    var usernameInput by remember(username) {
        mutableStateOf(username)
    }
    var usernameError by remember {
        mutableStateOf<String?>(null)
    }

    ResponsiveScreen(
        modifier = modifier.fillMaxSize()
    ) { ui ->
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .background(SquadBlueBackground)
                .padding(ui.horizontalPadding)
        ) {
        Text(
            LocalUiStrings.current.text(UiTextKey.USERNAME_CALLSIGN),
            color = Color.White,
            fontSize = 22.sp,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = 1.sp
        )

        Spacer(Modifier.height(ui.dp(6.dp)))

        Text(
            LocalUiStrings.current.text(UiTextKey.NAME_SHOWN),
            color = RedTacticalTextSecondary,
            fontSize = 12.sp
        )

        Spacer(Modifier.height(ui.sectionSpacing))

        OutlinedTextField(
            value = usernameInput,
            onValueChange = {
                usernameInput = it
                usernameError = null
            },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text(LocalUiStrings.current.text(UiTextKey.USERNAME)) },
            supportingText = {
                Text(
                    usernameError ?: LocalUiStrings.current.text(UiTextKey.MAX_CALLSIGN),
                    color = if (usernameError != null) {
                        SquadBluePrimary
                    } else {
                        RedTacticalTextSecondary
                    }
                )
            },
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = SquadBlueSurface,
                unfocusedContainerColor = SquadBlueSurface,
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                focusedLabelColor = SquadBluePrimary,
                unfocusedLabelColor = RedTacticalTextSecondary,
                focusedBorderColor = SquadBluePrimary,
                unfocusedBorderColor = SquadBlueBorder
            )
        )

        Spacer(Modifier.height(ui.dp(6.dp)))

        Button(
            onClick = {
                usernameError = onUsernameSave(usernameInput)
            },
            colors = ButtonDefaults.buttonColors(
                containerColor = SquadBluePrimary
            )
        ) {
            Text(LocalUiStrings.current.text(UiTextKey.SAVE_USERNAME), fontWeight = FontWeight.Bold)
        }

        Spacer(Modifier.height(ui.dp(24.dp)))

        Text(
            LocalUiStrings.current.text(UiTextKey.UI_LANGUAGE),
            color = Color.White,
            fontSize = 22.sp,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = 1.sp
        )

        Spacer(Modifier.height(ui.dp(6.dp)))

        Spacer(Modifier.height(ui.sectionSpacing))

        Column(verticalArrangement = Arrangement.spacedBy(ui.smallSpacing)) {
            listOf("en" to "English", "hi" to "हिन्दी").forEach { (code, name) ->
                val selected = uiLanguageCode == code
                Card(
                    colors = CardDefaults.cardColors(containerColor = SquadBlueSurface),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(
                            1.dp,
                            if (selected) SquadBluePrimary else SquadBlueBorder,
                            RoundedCornerShape(14.dp)
                        )
                        .clickable(enabled = !selected) {
                            onUiLanguageSelected(code)
                        }
                ) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                             .padding(
                            horizontal = ui.dp(12.dp),
                            vertical = ui.dp(10.dp)
                        ),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = selected,
                            colors = RadioButtonDefaults.colors(
                                selectedColor = SquadBluePrimary,
                                unselectedColor = RedTacticalTextSecondary
                            ),
                            onClick = {
                                if (!selected) onUiLanguageSelected(code)
                            }
                        )
                        Spacer(Modifier.width(ui.smallSpacing))
                        Text(
                            name,
                            color = Color.White,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(ui.dp(24.dp)))

        Text(
            LocalUiStrings.current.text(UiTextKey.INCOMING_VOICE_PLAYBACK),
            color = Color.White,
            fontSize = 22.sp,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = 1.sp
        )

        Spacer(Modifier.height(ui.dp(6.dp)))

        Text(
            LocalUiStrings.current.text(UiTextKey.INCOMING_VOICE_PLAYBACK_DESC),
            color = RedTacticalTextSecondary,
            fontSize = 12.sp
        )

        Spacer(Modifier.height(ui.sectionSpacing))

        Column(
            verticalArrangement = Arrangement.spacedBy(ui.smallSpacing)
        ) {
            val playbackOptions = listOf(
                Triple(
                    com.tactical.platform.speech.mms.MmsTtsPlaybackMode.ONE_BY_ONE,
                    LocalUiStrings.current.text(UiTextKey.ONE_BY_ONE),
                    LocalUiStrings.current.text(UiTextKey.ONE_BY_ONE_DESC)
                ),
                Triple(
                    com.tactical.platform.speech.mms.MmsTtsPlaybackMode.OVERLAPPING,
                    LocalUiStrings.current.text(UiTextKey.OVERLAPPING_VOICES),
                    LocalUiStrings.current.text(UiTextKey.OVERLAPPING_DESC)
                )
            )

            playbackOptions.forEach { (mode, title, description) ->
                val selected = ttsPlaybackMode == mode

                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = SquadBlueSurface
                    ),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(
                            width = 1.dp,
                            color = if (selected) {
                                SquadBluePrimary
                            } else {
                                SquadBlueBorder
                            },
                            shape = RoundedCornerShape(14.dp)
                        )
                        .clickable { onTtsPlaybackModeSelected(mode) }
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                             .padding(
                            horizontal = ui.dp(12.dp),
                            vertical = ui.dp(10.dp)
                        ),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = selected,
                            colors = RadioButtonDefaults.colors(
                                selectedColor = SquadBluePrimary,
                                unselectedColor = RedTacticalTextSecondary
                            ),
                            onClick = { onTtsPlaybackModeSelected(mode) }
                        )

                        Column {
                            Text(
                                title,
                                color = Color.White,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(Modifier.height(ui.dp(2.dp)))
                            Text(
                                description,
                                color = RedTacticalTextSecondary,
                                fontSize = 11.sp
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(ui.dp(6.dp)))

        Text(
            LocalUiStrings.current.text(UiTextKey.OVERLAPPING_NOTE),
            color = RedTacticalTextSecondary,
            fontSize = 11.sp
        )
        }
    }
}
