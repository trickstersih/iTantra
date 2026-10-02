package com.tactical.app.ui.screens

import com.tactical.app.ui.i18n.LocalUiStrings
import com.tactical.app.ui.i18n.UiTextKey
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.KeyboardOptions
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.tactical.app.ui.ChatMessageUi
import com.tactical.app.ui.MainUiState
import com.tactical.app.ui.ResponsiveScreen
import com.tactical.app.ui.ResponsiveUi
import com.tactical.app.ui.components.EmergencyAlertDialog
import com.tactical.app.ui.theme.*

@Composable
fun MessagesScreen(
    uiState: MainUiState,
    onSendMessage: (String) -> Unit,
    onMessagesOpened: () -> Unit,
    onDeleteMessages: (Set<ChatMessageUi>, Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    var input by remember { mutableStateOf("") }
    var selectedEmergency by remember { mutableStateOf<ChatMessageUi?>(null) }
    var selectionMode by remember { mutableStateOf(false) }
    var selectedKeys by remember { mutableStateOf<Set<String>>(emptySet()) }
    var showDeleteConfirmation by remember { mutableStateOf(false) }
    var selectedTab by rememberSaveable { mutableStateOf(0) }

    // Refresh relative timestamps while this screen is visible.
    var currentTimeMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            currentTimeMs = System.currentTimeMillis()
            delay(15_000L)
        }
    }


    // The Messages destination is only composed while it is visible, so
    // entering this screen (and receiving a new message while it stays open)
    // marks the current conversation as seen.
    LaunchedEffect(
        uiState.receivedMessages.size,
        uiState.receivedMessages.lastOrNull()?.timestampEpochMs
    ) {
        onMessagesOpened()
    }

    // Keep normal text and speech-derived traffic in separate tabs
    // so Call Mode sentence-by-sentence transmissions do not flood the
    // ordinary conversation.
    val allMessages = remember(
        uiState.sentMessages,
        uiState.receivedMessages
    ) {
        (uiState.sentMessages + uiState.receivedMessages)
            .distinctBy(::messageStorageKey)
            .sortedBy {
                it.conversationOrderEpochMs.takeIf { time -> time > 0L }
                    ?: it.timestampEpochMs
            }
    }

    val conversationMessages = remember(allMessages, selectedTab) {
        allMessages.filter { message ->
            if (selectedTab == 1) message.isCallMode else !message.isCallMode
        }
    }

    val listState = rememberLazyListState()

    // Keep the newest message visible as the conversation grows.
    LaunchedEffect(conversationMessages.size) {
        if (conversationMessages.isNotEmpty()) {
            listState.animateScrollToItem(conversationMessages.lastIndex)
        }
    }

    val selectedMessages = conversationMessages.filter {
        messageStorageKey(it) in selectedKeys
    }.toSet()

    fun enterSelection(message: ChatMessageUi? = null) {
        selectionMode = true
        if (message != null) {
            selectedKeys = selectedKeys + messageStorageKey(message)
        }
    }

    fun toggleSelection(message: ChatMessageUi) {
        val key = messageStorageKey(message)
        selectedKeys = if (key in selectedKeys) {
            selectedKeys - key
        } else {
            selectedKeys + key
        }
    }

    fun selectAllCurrentTab() {
        val allKeys = conversationMessages.map(::messageStorageKey).toSet()
        if (allKeys.isEmpty()) return

        val allSelected = allKeys.all { it in selectedKeys }
        selectedKeys = if (allSelected) {
            selectedKeys - allKeys
        } else {
            selectedKeys + allKeys
        }
    }

    fun exitSelection() {
        selectionMode = false
        selectedKeys = emptySet()
        showDeleteConfirmation = false
    }

    ResponsiveScreen(
        modifier = modifier.fillMaxSize()
    ) { ui ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(SquadBlueBackground)
                .padding(ui.horizontalPadding)
                .imePadding()
                .navigationBarsPadding()
        ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (selectionMode) {
                    "${selectedKeys.size} SELECTED"
                } else {
                    LocalUiStrings.current.text(UiTextKey.MESSAGES)
                },
                color = Color.White,
                fontSize = 22.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = 1.sp,
                modifier = Modifier.weight(1f)
            )

            if (selectionMode) {
                TextButton(
                    onClick = ::selectAllCurrentTab
                ) {
                    Text(
                        if (
                            conversationMessages.isNotEmpty() &&
                                conversationMessages.all {
                                    messageStorageKey(it) in selectedKeys
                                }
                        ) {
                            LocalUiStrings.current.text(UiTextKey.CLEAR)
                        } else {
                            LocalUiStrings.current.text(UiTextKey.SELECT_ALL)
                        },
                        color = SquadBlueGlow,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                IconButton(
                    onClick = { showDeleteConfirmation = true },
                    enabled = selectedKeys.isNotEmpty()
                ) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = LocalUiStrings.current.text(UiTextKey.DELETE_SELECTED),
                        tint = if (selectedKeys.isNotEmpty()) {
                            SquadBlueGlow
                        } else {
                            RedTacticalTextSecondary
                        }
                    )
                }
                IconButton(onClick = ::exitSelection) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = LocalUiStrings.current.text(UiTextKey.CANCEL_SELECTION),
                        tint = Color.White
                    )
                }
            } else {
                TextButton(onClick = { enterSelection() }) {
                    Text(
                        LocalUiStrings.current.text(UiTextKey.SELECT),
                        color = SquadBlueGlow,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        Spacer(Modifier.height(ui.smallSpacing))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(ui.dp(46.dp))
                .background(
                    SquadBlueSurface,
                    RoundedCornerShape(12.dp)
                )
                .border(
                    width = 1.dp,
                    color = SquadBlueBorder,
                    shape = RoundedCornerShape(12.dp)
                )
                .padding(ui.dp(3.dp)),
            horizontalArrangement = Arrangement.spacedBy(ui.dp(3.dp)),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(9.dp))
                    .background(
                        if (selectedTab == 0) SquadBluePrimary else Color.Transparent
                    )
                    .clickable {
                        selectedTab = 0
                        selectedKeys = emptySet()
                    },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    LocalUiStrings.current.text(UiTextKey.CHAT_PPT_MODE),
                    color = if (selectedTab == 0) Color.White else RedTacticalTextSecondary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp
                )
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(9.dp))
                    .background(
                        if (selectedTab == 1) SquadBluePrimary else Color.Transparent
                    )
                    .clickable {
                        selectedTab = 1
                        selectedKeys = emptySet()
                    },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    LocalUiStrings.current.text(UiTextKey.CALL_MODE),
                    color = if (selectedTab == 1) Color.White else RedTacticalTextSecondary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        LazyColumn(
            state = listState,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            if (conversationMessages.isEmpty()) {
                item {
                    Text(
                        LocalUiStrings.current.text(UiTextKey.NO_MESSAGES),
                        color = RedTacticalTextSecondary,
                        fontSize = 13.sp,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center
                    )
                }
            }

            itemsIndexed(
                items = conversationMessages,
                key = { index, message ->
                    messageStorageKey(message) + "_" + index
                }
            ) { _, message ->
                MessageRow(
                    ui = ui,
                    message = message,
                    currentTimeMs = currentTimeMs,
                    isSent = message.sender == "YOU",
                    isSelectionMode = selectionMode,
                    isSelected = messageStorageKey(message) in selectedKeys,
                    onClick = {
                        if (selectionMode) {
                            toggleSelection(message)
                        } else if (message.isAlert && message.emergencyData != null) {
                            selectedEmergency = message
                        }
                    },
                    onLongClick = {
                        enterSelection(message)
                    }
                )
            }
        }

        if (!selectionMode && selectedTab == 0) {
            Spacer(Modifier.height(ui.sectionSpacing))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(ui.smallSpacing)
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    placeholder = {
                        Text(LocalUiStrings.current.text(UiTextKey.WRITE_MESSAGE), color = RedTacticalTextSecondary)
                    },
                    modifier = Modifier.weight(1f),
                    singleLine = false,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = SquadBlueSurface,
                        unfocusedContainerColor = SquadBlueSurface,
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = SquadBluePrimary,
                        unfocusedBorderColor = SquadBlueBorder
                    ),
                    shape = RoundedCornerShape(14.dp)
                )

                IconButton(
                    onClick = {
                        val message = input.trim()
                        if (message.isNotEmpty()) {
                            onSendMessage(message)
                            input = ""
                        }
                    },
                    enabled = input.isNotBlank()
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.Send,
                        contentDescription = LocalUiStrings.current.text(UiTextKey.SEND),
                        tint = if (input.isNotBlank()) {
                            SquadBlueGlow
                        } else {
                            RedTacticalTextSecondary
                        }
                    )
                }
            }
        }

        }
    }

    selectedEmergency?.emergencyData?.let { alert ->
        EmergencyAlertDialog(
            alertData = alert,
            onAcknowledge = { selectedEmergency = null }
        )
    }

    if (showDeleteConfirmation) {
        val selectedSent = selectedMessages
            .filter { it in uiState.sentMessages }
            .toSet()
        val selectedReceived = selectedMessages
            .filterNot { it in uiState.sentMessages }
            .toSet()

        AlertDialog(
            onDismissRequest = { showDeleteConfirmation = false },
            title = {
                Text(
                    LocalUiStrings.current.text(UiTextKey.DELETE_MESSAGES),
                    color = Color.White
                )
            },
            text = {
                Text(
                    LocalUiStrings.current.deleteConfirmation(selectedMessages.size),
                    color = RedTacticalTextSecondary
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (selectedSent.isNotEmpty()) {
                            onDeleteMessages(selectedSent, true)
                        }
                        if (selectedReceived.isNotEmpty()) {
                            onDeleteMessages(selectedReceived, false)
                        }
                        exitSelection()
                    }
                ) {
                    Text(
                        LocalUiStrings.current.text(UiTextKey.DELETE),
                        color = SquadBlueGlow,
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showDeleteConfirmation = false }
                ) {
                    Text(
                        LocalUiStrings.current.text(UiTextKey.CANCEL),
                        color = Color.White
                    )
                }
            },
            containerColor = SquadBlueSurface
        )
    }
}

@Composable
private fun formatLiveMessageTimestamp(
    message: ChatMessageUi,
    nowMs: Long
): String {
    val timestamp = message.timestampEpochMs
    if (timestamp <= 0L) return message.timestampText

    val ageMs = (nowMs - timestamp).coerceAtLeast(0L)

    return when {
        ageMs < 60_000L -> LocalUiStrings.current.text(UiTextKey.JUST_NOW)
        ageMs < 3_600_000L -> "${ageMs / 60_000L} min ago"
        else -> SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestamp))
    }
}

private fun messageStorageKey(message: ChatMessageUi): String =
    message.sender + "|" +
        message.timestampEpochMs + "|" +
        message.text + "|" +
        message.isAlert + "|" +
        message.isVoice

@Composable
private fun MessageRow(
    ui: ResponsiveUi,
    message: ChatMessageUi,
    currentTimeMs: Long,
    isSent: Boolean,
    isSelectionMode: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val borderColor = when {
        isSelected -> RedTacticalPrimaryBright
        message.isAlert -> RedTacticalPrimaryBright
        message.isVoice -> RedTacticalVoiceOrange
        else -> SquadBlueBorder
    }

    if (message.isAlert) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = if (isSelected) {
                    Color(0xFF3A1414)
                } else {
                    SquadBlueSurface
                }
            ),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .border(
                    width = if (isSelected) 1.5.dp else 1.dp,
                    color = borderColor,
                    shape = RoundedCornerShape(12.dp)
                )
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = onLongClick
                )
        ) {
            Column(
                modifier = Modifier.padding(ui.cardPadding)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "🚨 " + message.sender,
                        color = RedTacticalPrimaryBright,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                    Text(
                        formatLiveMessageTimestamp(message, currentTimeMs),
                        color = RedTacticalTextSecondary,
                        fontSize = 10.sp
                    )
                }

                Spacer(Modifier.height(ui.dp(6.dp)))

                Text(
                    message.text,
                    color = Color.White,
                    fontSize = 14.sp,
                    maxLines = 6
                )

                Spacer(Modifier.height(ui.dp(5.dp)))

                Text(
                    LocalUiStrings.current.text(UiTextKey.EMERGENCY_TAP_DETAILS),
                    color = RedTacticalPrimaryBright,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )

                if (isSelectionMode) {
                    Spacer(Modifier.height(8.dp))
                    SelectionIndicator(
                        ui = ui,
                        isSelected = isSelected,
                        modifier = Modifier.align(Alignment.End)
                    )
                }
            }
        }
        return
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isSent) {
            Arrangement.End
        } else {
            Arrangement.Start
        }
    ) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = if (isSelected) {
                    SquadBlueSurfaceRaised
                } else {
                    SquadBlueSurface
                }
            ),
            shape = RoundedCornerShape(
                topStart = 16.dp,
                topEnd = 16.dp,
                bottomStart = if (isSent) 4.dp else 16.dp,
                bottomEnd = if (isSent) 16.dp else 4.dp
            ),
            modifier = Modifier
                .widthIn(max = ui.dp(310.dp))
                .fillMaxWidth(fraction = 0.82f)
                .border(
                    width = if (isSelected) 1.5.dp else 1.dp,
                    color = borderColor,
                    shape = RoundedCornerShape(
                        topStart = 16.dp,
                        topEnd = 16.dp,
                        bottomStart = if (isSent) 4.dp else 16.dp,
                        bottomEnd = if (isSent) 16.dp else 4.dp
                    )
                )
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = onLongClick
                )
        ) {
            Column(
                modifier = Modifier.padding(
                    horizontal = ui.dp(12.dp),
                    vertical = ui.dp(9.dp)
                )
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Bottom
                ) {
                    if (!isSent) {
                        Text(
                            message.sender,
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(Modifier.width(ui.smallSpacing))
                    }

                    Text(
                        formatLiveMessageTimestamp(message, currentTimeMs),
                        color = RedTacticalTextSecondary,
                        fontSize = 10.sp
                    )
                }

                Spacer(Modifier.height(3.dp))

                Text(
                    message.text,
                    color = Color.White,
                    fontSize = 13.sp
                )

                Spacer(Modifier.height(ui.dp(4.dp)))

                if (message.statusText.isNotBlank() || isSelectionMode) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (message.statusText.isNotBlank()) {
                            Text(
                                message.statusText,
                                color = borderColor,
                                fontSize = 9.sp
                            )
                        }

                        if (isSelectionMode) {
                            if (message.statusText.isNotBlank()) {
                                Spacer(Modifier.width(8.dp))
                            }
                            SelectionIndicator(ui = ui, isSelected = isSelected)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SelectionIndicator(
    ui: ResponsiveUi,
    isSelected: Boolean,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(ui.dp(20.dp))
            .border(
                width = 2.dp,
                color = if (isSelected) {
                    SquadBlueGlow
                } else {
                    SquadBlueBorder
                },
                shape = RoundedCornerShape(50)
            )
            .then(
                if (isSelected) {
                    Modifier.background(
                        SquadBluePrimary,
                        RoundedCornerShape(50)
                    )
                } else {
                    Modifier
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        if (isSelected) {
            Text(
                "✓",
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.ExtraBold
            )
        }
    }
}
