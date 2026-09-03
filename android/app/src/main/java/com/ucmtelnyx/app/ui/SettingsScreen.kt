package com.ucmtelnyx.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ucmtelnyx.app.Me
import com.ucmtelnyx.app.SipConfig

/**
 * Read-only: the extension comes from whatever an administrator assigned to
 * this account, so there is nothing to type in here any more.
 */
@Composable
fun SettingsScreen(
    me: Me?,
    sipConfig: SipConfig?,
    statusText: String,
    regDetail: String = "",
    onReconnect: () -> Unit,
    onUnregister: () -> Unit,
    onLogout: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Your account", style = MaterialTheme.typography.titleLarge, color = AppText)

        InfoRow("Signed in as", me?.let { "${it.displayName} (${it.username})" } ?: "—")
        InfoRow("Extension", sipConfig?.extension?.takeIf { it.isNotBlank() } ?: "Not assigned")
        InfoRow("PBX", sipConfig?.domain?.takeIf { it.isNotBlank() } ?: "Not configured")
        InfoRow(
            "Transport",
            sipConfig?.let { "${it.sipTransport.uppercase()} : ${it.sipPort}" } ?: "—",
        )
        InfoRow("Texting", if (me?.canMessage == true) "Enabled" else "Not enabled")

        Text(
            "Your extension is assigned by an administrator - there's nothing to configure here.",
            color = AppTextDim,
            style = MaterialTheme.typography.bodySmall,
        )

        if (statusText.isNotBlank()) {
            Text(statusText, color = AppTextDim, style = MaterialTheme.typography.bodySmall)
        }

        if (regDetail.isNotBlank()) {
            Text(
                "Last registration result: $regDetail",
                color = AppDanger,
                style = MaterialTheme.typography.bodySmall,
            )
            // Spell out exactly what it tried, so a wrong port or host is obvious.
            sipConfig?.let {
                Text(
                    "Tried: ${it.extension}@${it.domain}:${it.sipPort} over " +
                        it.sipTransport.uppercase(),
                    color = AppTextDim,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = onReconnect,
                colors = ButtonDefaults.buttonColors(containerColor = AppAccent),
                modifier = Modifier.weight(1f),
            ) { Text("Reconnect") }
            OutlinedButton(onClick = onUnregister) { Text("Unregister") }
        }

        OutlinedButton(onClick = onLogout, modifier = Modifier.fillMaxWidth()) { Text("Sign out") }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, color = AppTextDim, style = MaterialTheme.typography.bodyMedium)
        Text(value, color = AppText, style = MaterialTheme.typography.bodyMedium)
    }
}
