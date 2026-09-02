package com.ucmtelnyx.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.ucmtelnyx.app.ConversationSummary
import com.ucmtelnyx.app.Message

@Composable
fun MessagesScreen(
    conversations: List<ConversationSummary>,
    openConversation: String?,
    openMessages: List<Message>,
    onOpenConversation: (String) -> Unit,
    onBack: () -> Unit,
    onNewConversation: (String) -> Unit,
    onSend: (to: String, text: String) -> Unit,
) {
    if (openConversation == null) {
        ConversationListScreen(conversations, onOpenConversation, onNewConversation)
    } else {
        ThreadScreen(openConversation, openMessages, onBack) { text -> onSend(openConversation, text) }
    }
}

@Composable
private fun ConversationListScreen(
    conversations: List<ConversationSummary>,
    onOpen: (String) -> Unit,
    onNew: (String) -> Unit,
) {
    var showNewDialog by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.Start,
        ) {
            OutlinedButton(onClick = { showNewDialog = true }) { Text("+ New") }
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(conversations) { conversation ->
                Column(
                    Modifier.fillMaxWidth()
                        .clickable { onOpen(conversation.number) }
                        .padding(12.dp),
                ) {
                    Text(conversation.number, color = AppText, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                    conversation.lastMessage?.text?.let {
                        Text(it, color = AppTextDim, maxLines = 1)
                    }
                }
                HorizontalDivider(color = AppBorder)
            }
        }
    }

    if (showNewDialog) {
        NewConversationDialog(
            onDismiss = { showNewDialog = false },
            onConfirm = { number -> showNewDialog = false; onNew(number) },
        )
    }
}

@Composable
private fun NewConversationDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var number by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New conversation") },
        text = {
            OutlinedTextField(
                value = number,
                onValueChange = { number = it },
                placeholder = { Text("+15551234567") },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(onClick = { if (number.isNotBlank()) onConfirm(number.trim()) }) { Text("Start") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ThreadScreen(number: String, messages: List<Message>, onBack: () -> Unit, onSend: (String) -> Unit) {
    var text by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = AppAccent)
            }
            Spacer(Modifier.width(4.dp))
            Text(number, color = AppText, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
        }
        HorizontalDivider(color = AppBorder)

        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(messages) { message -> Bubble(message) }
        }

        HorizontalDivider(color = AppBorder)
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text("Type a message…") },
                modifier = Modifier.weight(1f),
                singleLine = true,
            )
            Button(
                onClick = { if (text.isNotBlank()) { onSend(text); text = "" } },
                colors = ButtonDefaults.buttonColors(containerColor = AppAccent),
            ) { Text("Send") }
        }
    }
}

@Composable
private fun Bubble(message: Message) {
    val outbound = message.direction == "outbound"
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (outbound) Arrangement.End else Arrangement.Start) {
        Column(
            modifier = Modifier
                .widthIn(max = 260.dp)
                .background(if (outbound) AppAccent else AppPanel2, RoundedCornerShape(16.dp))
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Text(message.text ?: "", color = if (outbound) Color.White else AppText)
        }
    }
}
