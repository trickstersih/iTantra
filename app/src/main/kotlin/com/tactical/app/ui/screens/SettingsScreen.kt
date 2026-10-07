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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tactical.app.ui.ResponsiveScreen
import com.tactical.app.ui.i18n.LocalUiStrings
import com.tactical.app.ui.i18n.UiTextKey
import com.tactical.app.ui.theme.RedTacticalTextSecondary
import com.tactical.app.ui.theme.SquadBlueBackground
import com.tactical.app.ui.theme.SquadBlueBorder
import com.tactical.app.ui.theme.SquadBlueGlow
import com.tactical.app.ui.theme.SquadBluePrimary
import com.tactical.app.ui.theme.SquadBlueSurface
import com.tactical.app.ui.theme.SquadBlueSurfaceRaised

private data class UiLanguageOption(
    val code: String,
    val englishName: String,
    val nativeName: String
)

private val UI_LANGUAGE_OPTIONS = listOf(
    UiLanguageOption("en", "English", "English"),
    UiLanguageOption("hi", "Hindi", "हिन्दी"),
    UiLanguageOption("gu", "Gujarati", "ગુજરાતી"),
    UiLanguageOption("mr", "Marathi", "मराठी"),
    UiLanguageOption("kn", "Kannada", "ಕನ್ನಡ"),
    UiLanguageOption("ml", "Malayalam", "മലയാളം"),
    UiLanguageOption("ta", "Tamil", "தமிழ்"),
    UiLanguageOption("te", "Telugu", "తెలుగు"),
    UiLanguageOption("or", "Odia", "ଓଡ଼ିଆ"),
    UiLanguageOption("bn", "Bengali", "বাংলা")
)

@Composable
fun SettingsScreen(
    uiLanguageCode: String,
    onUiLanguageSelected: (String) -> Unit,
    ttsPlaybackMode: com.tactical.platform.speech.mms.MmsTtsPlaybackMode,
    onTtsPlaybackModeSelected: (com.tactical.platform.speech.mms.MmsTtsPlaybackMode) -> Unit,
    username: String,
    onUsernameSave: (String) -> String?,
    spokenName: String,
    spokenNameEnabled: Boolean,
    onSpokenNameSave: (String) -> Unit,
    onSpokenNameEnabledChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    var usernameInput by remember(username) { mutableStateOf(username) }
    var usernameError by remember { mutableStateOf<String?>(null) }
    var uiLanguagePickerVisible by rememberSaveable { mutableStateOf(false) }

    ResponsiveScreen(
        modifier = modifier.fillMaxSize(),
        languageCode = uiLanguageCode
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
                fontSize = ui.sp(22f),
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = ui.sp(1f)
            )

            Spacer(Modifier.height(ui.dp(6.dp)))

            Text(
                LocalUiStrings.current.text(UiTextKey.NAME_SHOWN),
                color = RedTacticalTextSecondary,
                fontSize = ui.sp(12f)
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
                onClick = { usernameError = onUsernameSave(usernameInput) },
                colors = ButtonDefaults.buttonColors(containerColor = SquadBluePrimary)
            ) {
                Text(
                    LocalUiStrings.current.text(UiTextKey.SAVE_USERNAME),
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(Modifier.height(ui.dp(24.dp)))

            Text(
                LocalUiStrings.current.text(UiTextKey.SPOKEN_PTT_IDENTITY),
                color = Color.White,
                fontSize = ui.sp(22f),
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = ui.sp(1f)
            )

            Spacer(Modifier.height(ui.dp(6.dp)))

            Text(
                LocalUiStrings.current.text(UiTextKey.SPOKEN_PTT_IDENTITY_DESC),
                color = RedTacticalTextSecondary,
                fontSize = ui.sp(12f)
            )

            Spacer(Modifier.height(ui.sectionSpacing))

            var spokenNameInput by remember(spokenName) { mutableStateOf(spokenName) }

            OutlinedTextField(
                value = spokenNameInput,
                onValueChange = { spokenNameInput = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text(LocalUiStrings.current.text(UiTextKey.FIRST_NAME)) },
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
                onClick = { onSpokenNameSave(spokenNameInput) },
                colors = ButtonDefaults.buttonColors(containerColor = SquadBluePrimary)
            ) {
                Text(
                    LocalUiStrings.current.text(UiTextKey.SAVE_SPOKEN_NAME),
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(Modifier.height(ui.dp(6.dp)))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        LocalUiStrings.current.text(UiTextKey.SPOKEN_PTT_IDENTITY_TOGGLE),
                        color = Color.White,
                        fontSize = ui.sp(14f),
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(ui.dp(2.dp)))
                    Text(
                        LocalUiStrings.current.text(UiTextKey.SPOKEN_PTT_IDENTITY_TOGGLE_DESC),
                        color = RedTacticalTextSecondary,
                        fontSize = ui.sp(11f)
                    )
                }

                androidx.compose.material3.Switch(
                    checked = spokenNameEnabled,
                    onCheckedChange = onSpokenNameEnabledChange
                )
            }

            Spacer(Modifier.height(ui.dp(24.dp)))

            Text(
                LocalUiStrings.current.text(UiTextKey.UI_LANGUAGE),
                color = Color.White,
                fontSize = ui.sp(22f),
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = ui.sp(1f)
            )

            Spacer(Modifier.height(ui.dp(6.dp)))
            Spacer(Modifier.height(ui.sectionSpacing))

            Surface(
                onClick = { uiLanguagePickerVisible = true },
                color = SquadBlueSurface,
                shape = RoundedCornerShape(22.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, SquadBlueBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            horizontal = ui.dp(16.dp),
                            vertical = ui.dp(10.dp)
                        ),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            LocalUiStrings.current.text(UiTextKey.UI_LANGUAGE),
                            color = RedTacticalTextSecondary,
                            fontSize = ui.sp(9f),
                            fontWeight = FontWeight.Bold,
                            letterSpacing = ui.sp(0.8f)
                        )
                        Spacer(Modifier.height(ui.dp(2.dp)))
                        Text(
                            UI_LANGUAGE_OPTIONS
                                .firstOrNull { it.code == uiLanguageCode }
                                ?.nativeName
                                ?: "English",
                            color = Color.White,
                            fontSize = ui.sp(14f),
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    Icon(
                        imageVector = Icons.Default.ChevronRight,
                        contentDescription = LocalUiStrings.current.text(UiTextKey.SELECT_LANGUAGE),
                        tint = Color(0xFFBFD6EA)
                    )
                }
            }

            Spacer(Modifier.height(ui.dp(24.dp)))

            Text(
                LocalUiStrings.current.text(UiTextKey.INCOMING_VOICE_PLAYBACK),
                color = Color.White,
                fontSize = ui.sp(22f),
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = ui.sp(1f)
            )

            Spacer(Modifier.height(ui.dp(6.dp)))

            Text(
                LocalUiStrings.current.text(UiTextKey.INCOMING_VOICE_PLAYBACK_DESC),
                color = RedTacticalTextSecondary,
                fontSize = ui.sp(12f)
            )

            Spacer(Modifier.height(ui.sectionSpacing))

            Column(verticalArrangement = Arrangement.spacedBy(ui.smallSpacing)) {
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
                        colors = CardDefaults.cardColors(containerColor = SquadBlueSurface),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(
                                width = 1.dp,
                                color = if (selected) SquadBluePrimary else SquadBlueBorder,
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
                                    fontSize = ui.sp(15f),
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(Modifier.height(ui.dp(2.dp)))
                                Text(
                                    description,
                                    color = RedTacticalTextSecondary,
                                    fontSize = ui.sp(11f)
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
                fontSize = ui.sp(11f)
            )

            if (uiLanguagePickerVisible) {
                AlertDialog(
                    onDismissRequest = { uiLanguagePickerVisible = false },
                    containerColor = SquadBlueSurfaceRaised,
                    titleContentColor = Color.White,
                    textContentColor = Color.White,
                    title = {
                        Text(
                            LocalUiStrings.current.text(UiTextKey.SELECT_LANGUAGE),
                            color = Color.White,
                            fontSize = ui.sp(20f),
                            fontWeight = FontWeight.ExtraBold
                        )
                    },
                    text = {
                        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                            UI_LANGUAGE_OPTIONS.forEach { option ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            uiLanguagePickerVisible = false
                                            onUiLanguageSelected(option.code)
                                        }
                                        .padding(vertical = ui.dp(7.dp)),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            option.nativeName,
                                            color = Color.White,
                                            fontSize = ui.sp(14f),
                                            fontWeight = FontWeight.SemiBold
                                        )
                                        Text(
                                            option.englishName,
                                            color = Color(0xFFBFD6EA),
                                            fontSize = ui.sp(10f)
                                        )
                                    }

                                    RadioButton(
                                        selected = uiLanguageCode == option.code,
                                        colors = RadioButtonDefaults.colors(
                                            selectedColor = SquadBluePrimary,
                                            unselectedColor = Color(0xFF8EAAC2)
                                        ),
                                        onClick = {
                                            uiLanguagePickerVisible = false
                                            onUiLanguageSelected(option.code)
                                        }
                                    )
                                }
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(
                            onClick = { uiLanguagePickerVisible = false }
                        ) {
                            Text(
                                LocalUiStrings.current.text(UiTextKey.CONFIRM),
                                color = SquadBlueGlow,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                )
            }
        }
    }
}
