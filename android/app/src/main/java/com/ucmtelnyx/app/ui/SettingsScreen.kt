package com.ucmtelnyx.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

data class SipFormState(
    var domain: String,
    var port: String,
    var transport: String,
    var extension: String,
    var password: String,
)

@Composable
fun SettingsScreen(
    initial: SipFormState,
    onSave: (SipFormState) -> Unit,
    onUnregister: () -> Unit,
    statusText: String,
) {
    var form by remember { mutableStateOf(initial) }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("SIP / PBX connection", style = MaterialTheme.typography.titleLarge, color = AppText)
        Text(
            "Native SIP registration - a different port/transport than the browser app's " +
                "WebRTC (WSS) settings. Typically port 5061 with TLS, or 5060 with UDP/TCP.",
            color = AppTextDim,
            style = MaterialTheme.typography.bodySmall,
        )

        OutlinedTextField(
            value = form.domain,
            onValueChange = { form = form.copy(domain = it) },
            label = { Text("SIP domain / PBX host") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = form.port,
            onValueChange = { form = form.copy(port = it.filter { c -> c.isDigit() }) },
            label = { Text("Port") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        TransportSelector(form.transport) { form = form.copy(transport = it) }
        OutlinedTextField(
            value = form.extension,
            onValueChange = { form = form.copy(extension = it) },
            label = { Text("Extension") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = form.password,
            onValueChange = { form = form.copy(password = it) },
            label = { Text("SIP password") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = { onSave(form) },
                colors = ButtonDefaults.buttonColors(containerColor = AppAccent),
                modifier = Modifier.weight(1f),
            ) { Text("Save & Register") }
            OutlinedButton(onClick = onUnregister) { Text("Unregister") }
        }

        if (statusText.isNotBlank()) {
            Text(statusText, color = AppTextDim, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun TransportSelector(selected: String, onSelect: (String) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("tls", "tcp", "udp").forEach { option ->
            FilterChip(
                selected = selected == option,
                onClick = { onSelect(option) },
                label = { Text(option.uppercase()) },
            )
        }
    }
}
