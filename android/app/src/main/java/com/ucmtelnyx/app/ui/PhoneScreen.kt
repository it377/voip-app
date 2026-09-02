package com.ucmtelnyx.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ucmtelnyx.app.CallPhase
import com.ucmtelnyx.app.CallUiState
import com.ucmtelnyx.app.RegStatus

private val keypadRows = listOf(
    listOf("1" to "", "2" to "ABC", "3" to "DEF"),
    listOf("4" to "GHI", "5" to "JKL", "6" to "MNO"),
    listOf("7" to "PQRS", "8" to "TUV", "9" to "WXYZ"),
    listOf("*" to "", "0" to "+", "#" to ""),
)

@Composable
fun PhoneScreen(
    regStatus: RegStatus,
    callState: CallUiState,
    onCall: (String) -> Unit,
    onAnswer: () -> Unit,
    onDecline: () -> Unit,
    onHangup: () -> Unit,
    onMute: () -> Unit,
    onHold: () -> Unit,
    onDtmf: (Char) -> Unit,
) {
    var dialed by remember { mutableStateOf("") }

    Column(
        modifier = Modifier.fillMaxSize().padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        RegistrationBadge(regStatus)
        Spacer(Modifier.height(8.dp))

        when (callState.phase) {
            CallPhase.IDLE -> DialScreen(
                dialed = dialed,
                onDigit = { dialed += it },
                onBackspace = { dialed = dialed.dropLast(1) },
                onCall = { if (dialed.isNotBlank()) onCall(dialed) },
            )
            CallPhase.OUTGOING, CallPhase.ACTIVE -> ActiveCallScreen(callState, onMute, onHold, onHangup)
            CallPhase.INCOMING -> IncomingCallScreen(callState, onAnswer, onDecline)
        }
    }
}

@Composable
private fun RegistrationBadge(status: RegStatus) {
    val (color, label) = when (status) {
        RegStatus.REGISTERED -> AppAccent2 to "Registered"
        RegStatus.CONNECTING -> AppAccent to "Connecting…"
        RegStatus.FAILED -> AppDanger to "Registration failed"
        RegStatus.UNREGISTERED -> AppDanger to "Not registered"
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(9.dp).background(color, CircleShape))
        Text(label, color = AppTextDim, fontSize = 13.sp)
    }
}

@Composable
private fun DialScreen(dialed: String, onDigit: (String) -> Unit, onBackspace: () -> Unit, onCall: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.fillMaxWidth().height(48.dp), contentAlignment = Alignment.Center) {
            Text(dialed.ifEmpty { " " }, color = AppText, fontSize = 30.sp, fontWeight = FontWeight.Light)
            if (dialed.isNotEmpty()) {
                IconButton(onClick = onBackspace, modifier = Modifier.align(Alignment.CenterEnd)) {
                    Icon(Icons.Filled.Backspace, contentDescription = "Delete", tint = AppTextDim)
                }
            }
        }
        Spacer(Modifier.height(20.dp))
        keypadRows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                row.forEach { (digit, letters) -> KeypadButton(digit, letters) { onDigit(digit) } }
            }
            Spacer(Modifier.height(16.dp))
        }
        Spacer(Modifier.height(8.dp))
        RoundActionButton(icon = Icons.Filled.Call, background = AppAccent2, onClick = onCall)
    }
}

@Composable
private fun KeypadButton(digit: String, letters: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier.size(72.dp).background(AppPanel2, CircleShape).clickableRipple(onClick),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(digit, color = AppText, fontSize = 24.sp)
            if (letters.isNotEmpty()) Text(letters, color = AppTextDim, fontSize = 9.sp)
        }
    }
}

@Composable
private fun ActiveCallScreen(state: CallUiState, onMute: () -> Unit, onHold: () -> Unit, onHangup: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(vertical = 24.dp),
        verticalArrangement = Arrangement.SpaceEvenly,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Avatar()
        Text(state.remoteAddress, color = AppText, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            CtrlButton(Icons.Filled.MicOff, "Mute", state.isMuted, onMute)
            CtrlButton(Icons.Filled.PauseCircle, "Hold", state.isOnHold, onHold)
        }
        RoundActionButton(icon = Icons.Filled.CallEnd, background = AppDanger, onClick = onHangup)
    }
}

@Composable
private fun IncomingCallScreen(state: CallUiState, onAnswer: () -> Unit, onDecline: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(vertical = 24.dp),
        verticalArrangement = Arrangement.SpaceEvenly,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Avatar()
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Incoming call", color = AppText, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            Text(state.remoteAddress, color = AppTextDim)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(48.dp)) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                RoundActionButton(icon = Icons.Filled.CallEnd, background = AppDanger, onClick = onDecline)
                Text("Decline", color = AppTextDim, fontSize = 12.sp)
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                RoundActionButton(icon = Icons.Filled.Call, background = AppAccent2, onClick = onAnswer)
                Text("Answer", color = AppTextDim, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun Avatar() {
    Box(
        modifier = Modifier.size(110.dp).background(AppPanel2, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Filled.Person, contentDescription = null, tint = AppTextDim, modifier = Modifier.size(54.dp))
    }
}

@Composable
private fun RoundActionButton(icon: androidx.compose.ui.graphics.vector.ImageVector, background: Color, onClick: () -> Unit) {
    Box(
        modifier = Modifier.size(68.dp).background(background, CircleShape).clickableRipple(onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(28.dp))
    }
}

@Composable
private fun CtrlButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, active: Boolean, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            modifier = Modifier.size(50.dp)
                .background(if (active) AppAccent else AppPanel2, CircleShape)
                .clickableRipple(onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = label, tint = if (active) Color.White else AppText, modifier = Modifier.size(22.dp))
        }
        Text(label, color = AppTextDim, fontSize = 11.sp)
    }
}
