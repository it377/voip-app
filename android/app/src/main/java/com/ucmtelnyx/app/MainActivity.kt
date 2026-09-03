package com.ucmtelnyx.app

import android.Manifest
import android.content.ComponentName
import android.content.Context
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
        backend = BackendClient(this, prefs.backendUrl)

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
    var me by remember { mutableStateOf<Me?>(null) }
    var checkedSession by remember { mutableStateOf(false) }
    var tab by remember { mutableStateOf(Tab.PHONE) }
    val scope = rememberCoroutineScope()

    var sipConfig by remember { mutableStateOf<SipConfig?>(null) }
    var sipStatus by remember { mutableStateOf("") }

    var conversations by remember { mutableStateOf(listOf<ConversationSummary>()) }
    var openConversation by remember { mutableStateOf<String?>(null) }
    var openMessages by remember { mutableStateOf(listOf<Message>()) }

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

    // Pull the assigned extension and register with it - the whole point of the
    // admin-provisioned setup: nothing typed in on the device.
    fun connectSip(force: Boolean = false) {
        scope.launch {
            backend.sipConfig()
                .onSuccess { config ->
                    sipConfig = config
                    when {
                        config.extension.isBlank() || config.password.isBlank() ->
                            sipStatus = "An administrator has not assigned you an extension yet."
                        config.domain.isBlank() ->
                            sipStatus = "An administrator has not filled in the PBX settings yet."
                        else -> {
                            sipStatus = ""
                            sipManager?.register(
                                config.domain,
                                config.sipPort,
                                config.sipTransport,
                                config.extension,
                                config.password,
                                force = force,
                            )
                        }
                    }
                }
                .onFailure { sipStatus = it.message ?: "Could not load your extension settings" }
        }
    }

    LaunchedEffect(Unit) {
        me = backend.session()
        checkedSession = true
    }

    LaunchedEffect(me, sipManager) {
        if (me != null && sipManager != null) connectSip()
    }

    LaunchedEffect(me) {
        val current = me
        if (current != null && current.canMessage) {
            refreshConversations()
            onOpenWebSocket { message ->
                refreshConversations()
                val other = if (message.direction == "outbound") message.to else message.fromNumber
                if (other == openConversation) openMessages = openMessages + message
            }
        } else {
            onCloseWebSocket()
        }
    }

    // Texting is opt-in per account, so the Messages tab only exists for users
    // an admin enabled it for (the server enforces this too).
    val tabs = buildList {
        add(Tab.PHONE)
        if (me?.canMessage == true) add(Tab.MESSAGES)
        add(Tab.SETTINGS)
    }
    // Derive rather than assign: writing state during composition can loop.
    val activeTab = if (tab in tabs) tab else Tab.PHONE

    when {
        !checkedSession -> Unit // still checking for an existing session
        me == null -> LoginScreen(
            backendUrl = backendUrl,
            onBackendUrlChange = { backendUrl = it; prefs.backendUrl = it; backend.updateBaseUrl(it) },
            onLogin = { username, password ->
                backend.login(username, password).map { user -> me = user }
            },
            onLoggedIn = { },
        )
        else -> Scaffold(
            bottomBar = {
                NavigationBar {
                    tabs.forEach { entry ->
                        NavigationBarItem(
                            selected = activeTab == entry,
                            onClick = { tab = entry },
                            icon = {
                                when (entry) {
                                    Tab.PHONE -> Icon(Icons.Filled.Call, contentDescription = "Phone")
                                    Tab.MESSAGES -> Icon(Icons.Filled.Chat, contentDescription = "Messages")
                                    Tab.SETTINGS -> Icon(Icons.Filled.Settings, contentDescription = "Settings")
                                }
                            },
                            label = {
                                Text(
                                    when (entry) {
                                        Tab.PHONE -> "Phone"
                                        Tab.MESSAGES -> "Messages"
                                        Tab.SETTINGS -> "Settings"
                                    }
                                )
                            },
                        )
                    }
                }
            },
        ) { padding ->
            Box(modifier = Modifier.padding(padding)) {
                when (activeTab) {
                    Tab.PHONE -> {
                        val regStatus by (sipManager?.regStatus?.collectAsStateWithLifecycle()
                            ?: remember { mutableStateOf(RegStatus.UNREGISTERED) })
                        val callState by (sipManager?.callState?.collectAsStateWithLifecycle()
                            ?: remember { mutableStateOf(CallUiState()) })
                        val regDetail by (sipManager?.regDetail?.collectAsStateWithLifecycle()
                            ?: remember { mutableStateOf("") })
                        PhoneScreen(
                            regStatus = regStatus,
                            regDetail = regDetail,
                            callState = callState,
                            onCall = { number -> sipManager?.call(number) },
                            onAnswer = { sipManager?.answer() },
                            onDecline = { sipManager?.decline() },
                            onHangup = { sipManager?.hangup() },
                            onMute = { sipManager?.toggleMute() },
                            onSpeaker = { sipManager?.toggleSpeaker() },
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
                        onSend = { to, text -> scope.launch { backend.sendMessage(to, text) } },
                    )
                    Tab.SETTINGS -> {
                        val regDetail by (sipManager?.regDetail?.collectAsStateWithLifecycle()
                            ?: remember { mutableStateOf("") })
                        SettingsScreen(
                            me = me,
                            sipConfig = sipConfig,
                            statusText = sipStatus,
                            regDetail = regDetail,
                            onReconnect = { connectSip(force = true) },
                            onUnregister = { sipManager?.unregister() },
                            onLogout = {
                                scope.launch {
                                    backend.logout()
                                    sipManager?.unregister()
                                    onCloseWebSocket()
                                    me = null
                                    sipConfig = null
                                    conversations = emptyList()
                                    openConversation = null
                                    openMessages = emptyList()
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}
