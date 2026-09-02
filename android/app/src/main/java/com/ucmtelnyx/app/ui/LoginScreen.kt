package com.ucmtelnyx.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@Composable
fun LoginScreen(
    backendUrl: String,
    onBackendUrlChange: (String) -> Unit,
    onLogin: suspend (password: String) -> Result<Unit>,
    onLoggedIn: () -> Unit,
) {
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Box(
        modifier = Modifier.fillMaxSize().background(AppBackground),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .padding(32.dp)
                .background(AppPanel, RoundedCornerShape(16.dp))
                .padding(32.dp)
                .widthIn(max = 340.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier.size(56.dp).background(AppAccent2, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Call, contentDescription = null, tint = Color.White)
            }
            Text("UCM / Telnyx", style = MaterialTheme.typography.titleLarge, color = AppText)

            OutlinedTextField(
                value = backendUrl,
                onValueChange = onBackendUrlChange,
                label = { Text("Server URL") },
                placeholder = { Text("https://voip.example.com") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = password,
                onValueChange = { password = it; error = null },
                label = { Text("Password") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )

            Button(
                onClick = {
                    if (backendUrl.isBlank() || password.isBlank()) {
                        error = "Enter the server URL and password"
                        return@Button
                    }
                    loading = true
                    scope.launch {
                        val result = onLogin(password)
                        loading = false
                        result.onSuccess { onLoggedIn() }
                            .onFailure { error = it.message ?: "Login failed" }
                    }
                },
                enabled = !loading,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = AppAccent2),
            ) {
                Text(if (loading) "Signing in…" else "Sign in")
            }

            error?.let {
                Text(it, color = AppDanger, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
