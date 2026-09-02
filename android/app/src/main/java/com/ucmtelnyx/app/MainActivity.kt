package com.ucmtelnyx.app

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ucmtelnyx.app.ui.LoginScreen
import com.ucmtelnyx.app.ui.MessagesScreen
import com.ucmtelnyx.app.ui.PhoneScreen
import com.ucmtelnyx.app.ui.SettingsScreen
import com.ucmtelnyx.app.ui.SipFormState
import com.ucmtelnyx.app.ui.UcmTelnyxTheme
import kotlinx.coroutines.launch
import okhttp3.WebSocket

private enum class Tab { PHONE, MESSAGES, SETTINGS }

class MainActivity : ComponentActivity() {

    private lateinit var prefs: Prefs
    private lateinit var backend: BackendClient
    private var sipManager by mutableStateOf<SipManager?>(null)
    private var activeWebSocket: WebSocket? = null

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            sipManager = (binder as CallService.LocalBinder).sipManager()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            sipManager = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        backend = BackendClient(prefs.backendUrl)

        requestRuntimePermissions()
        // Plain bind (not startForegroundService): CallService only promotes itself to a
        // foreground service once a call actually starts ringing/connects. Calling
        // startForegroundService() here would obligate it to call startForeground()
        // within a few seconds of process start, which it doesn't do while idle.
        bindService(callServiceIntent(), serviceConnection, Context.BIND_AUTO_CREATE)

        setContent {
            UcmTelnyxTheme {
                AppRoot(
                    prefs = prefs,
                    backend = backend,
                    sipManager = sipManager,
                    onOpenWebSocket = { onMessage ->
                        activeWebSocket?.close(1000, null)
                        activeWebSocket = backend.connectWebSocket(onMessage) { }
                    },
                    onCloseWebSocket = { activeWebSocket?.close(1000, null); activeWebSocket = null },
                )
            }
        }
    }

    private fun requestRuntimePermissions() {
        val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        permissionLauncher.launch(permissions.toTypedArray())
    }

    override fun onDestroy() {
        activeWebSocket?.close(1000, null)
        unbindService(serviceConnection)
        super.onDestroy()
    }
}

@Composable
private fun AppRoot(
    prefs: Prefs,
    backend: BackendClient,
    sipManager: SipManager?,
    onOpenWebSocket: ((Message) -> Unit) -> Unit,
    onCloseWebSocket: () -> Unit,
) {
    var backendUrl by remember { mutableStateOf(prefs.backendUrl) }
    var loggedIn by remember { mutableStateOf<Boolean?>(null) }
    var tab by remember { mutableStateOf(Tab.PHONE) }
    val scope = rememberCoroutineScope()

    var conversations by remember { mutableStateOf(listOf<ConversationSummary>()) }
    var openConversation by remember { mutableStateOf<String?>(null) }
    var openMessages by remember { mutableStateOf(listOf<Message>()) }
    var sipStatus by remember { mutableStateOf("") }

    fun refreshConversations() {
        scope.launch {
            runCatching { backend.listConversations() }.onSuccess { conversations = it }
        }
    }

    fun openThread(number: String) {
        openConversation = number
        scope.launch {
            runCatching { backend.getConversation(number) }.onSuccess { openMessages = it.messages }
        }
    }

    LaunchedEffect(Unit) {
        loggedIn = backend.session()
    }

    LaunchedEffect(loggedIn) {
        if (loggedIn == true) {
            refreshConversations()
            onOpenWebSocket { message ->
                refreshConversations()
                val other = if (message.direction == "outbound") message.to else message.fromNumber
                if (other == openConversation) openMessages = openMessages + message
            }
            if (prefs.hasSipSettings()) {
                sipManager?.register(prefs.sipDomain, prefs.sipPort, prefs.sipTransport, prefs.sipExtension, prefs.sipPassword)
            }
        } else {
            onCloseWebSocket()
        }
    }

    when (loggedIn) {
        null -> Unit // still checking session
        false -> LoginScreen(
            backendUrl = backendUrl,
            onBackendUrlChange = { backendUrl = it; prefs.backendUrl = it; backend.updateBaseUrl(it) },
            onLogin = { password -> backend.login(password) },
            onLoggedIn = { loggedIn = true },
        )
        true -> Scaffold(
            bottomBar = {
                NavigationBar {
                    NavigationBarItem(
                        selected = tab == Tab.PHONE,
                        onClick = { tab = Tab.PHONE },
                        icon = { Icon(Icons.Filled.Call, contentDescription = "Phone") },
                        label = { Text("Phone") },
                    )
                    NavigationBarItem(
                        selected = tab == Tab.MESSAGES,
                        onClick = { tab = Tab.MESSAGES },
                        icon = { Icon(Icons.Filled.Chat, contentDescription = "Messages") },
                        label = { Text("Messages") },
                    )
                    NavigationBarItem(
                        selected = tab == Tab.SETTINGS,
                        onClick = { tab = Tab.SETTINGS },
                        icon = { Icon(Icons.Filled.Settings, contentDescription = "Settings") },
                        label = { Text("Settings") },
                    )
                }
            },
        ) { padding ->
            Box(modifier = Modifier.padding(padding)) {
            when (tab) {
                Tab.PHONE -> {
                    val regStatus by (sipManager?.regStatus?.collectAsStateWithLifecycle()
                        ?: remember { mutableStateOf(RegStatus.UNREGISTERED) })
                    val callState by (sipManager?.callState?.collectAsStateWithLifecycle()
                        ?: remember { mutableStateOf(CallUiState()) })
                    PhoneScreen(
                        regStatus = regStatus,
                        callState = callState,
                        onCall = { number -> sipManager?.call(number) },
                        onAnswer = { sipManager?.answer() },
                        onDecline = { sipManager?.decline() },
                        onHangup = { sipManager?.hangup() },
                        onMute = { sipManager?.toggleMute() },
                        onHold = { sipManager?.toggleHold() },
                        onDtmf = { digit -> sipManager?.sendDtmf(digit) },
                    )
                }
                Tab.MESSAGES -> MessagesScreen(
                    conversations = conversations,
                    openConversation = openConversation,
                    openMessages = openMessages,
                    onOpenConversation = { number -> openThread(number) },
                    onBack = { openConversation = null },
                    onNewConversation = { number -> openThread(number) },
                    onSend = { to, text ->
                        scope.launch { backend.sendMessage(to, text) }
                    },
                )
                Tab.SETTINGS -> SettingsScreen(
                    initial = SipFormState(
                        domain = prefs.sipDomain,
                        port = prefs.sipPort.toString(),
                        transport = prefs.sipTransport,
                        extension = prefs.sipExtension,
                        password = prefs.sipPassword,
                    ),
                    onSave = { form ->
                        prefs.sipDomain = form.domain
                        prefs.sipPort = form.port.toIntOrNull() ?: 5061
                        prefs.sipTransport = form.transport
                        prefs.sipExtension = form.extension
                        prefs.sipPassword = form.password
                        sipManager?.register(prefs.sipDomain, prefs.sipPort, prefs.sipTransport, prefs.sipExtension, prefs.sipPassword)
                        sipStatus = "Registering…"
                    },
                    onUnregister = { sipManager?.unregister(); sipStatus = "Unregistered" },
                    statusText = sipStatus,
                )
            }
            }
        }
    }
}
