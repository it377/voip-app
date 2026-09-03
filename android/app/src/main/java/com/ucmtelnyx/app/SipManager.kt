package com.ucmtelnyx.app

import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.linphone.core.Call
import org.linphone.core.Core
import org.linphone.core.CoreListenerStub
import org.linphone.core.Factory
import org.linphone.core.RegistrationState
import org.linphone.core.TransportType

enum class RegStatus { UNREGISTERED, CONNECTING, REGISTERED, FAILED }

enum class CallPhase { IDLE, OUTGOING, INCOMING, ACTIVE }

data class CallUiState(
    val phase: CallPhase = CallPhase.IDLE,
    val remoteAddress: String = "",
    val isMuted: Boolean = false,
    val isOnHold: Boolean = false,
)

/**
 * Wraps the Linphone Core (native SIP/RTP engine) so the rest of the app only
 * deals with plain Kotlin state. This talks directly to the PBX over real SIP
 * (UDP/TCP/TLS on the normal SIP port) - it does NOT use WebRTC/WSS the way
 * the browser softphone does, so the port/transport here is different from
 * the web app's Settings tab. See android/README.md.
 */
class SipManager(private val context: Context) {

    private val core: Core = Factory.instance().createCore(null, null, context)
    private val iterateHandler = Handler(Looper.getMainLooper())
    private var iterating = false

    private val _regStatus = MutableStateFlow(RegStatus.UNREGISTERED)
    val regStatus: StateFlow<RegStatus> = _regStatus.asStateFlow()

    // The PBX's own words for why a registration failed ("Forbidden", "Timeout",
    // "Not Found", ...). Without this, a failure is just a red dot with no clue
    // whether it's credentials, the wrong port, or nothing listening at all.
    private val _regDetail = MutableStateFlow("")
    val regDetail: StateFlow<String> = _regDetail.asStateFlow()

    private val _callState = MutableStateFlow(CallUiState())
    val callState: StateFlow<CallUiState> = _callState.asStateFlow()

    private var activeCall: Call? = null

    private val iterateRunnable = object : Runnable {
        override fun run() {
            core.iterate()
            if (iterating) iterateHandler.postDelayed(this, 20)
        }
    }

    init {
        // NOTE: this app uses the modern Account/AccountParams API (not the legacy
        // ProxyConfig one), so the matching listener callback is
        // onAccountRegistrationStateChanged - not onRegistrationStateChanged, which is
        // the ProxyConfig-flavored callback and takes a different second parameter type.
        // The Linphone SDK's callback surface has shifted across versions; if Android
        // Studio flags this override as not matching anything, use its autocomplete on
        // CoreListenerStub to find the current method name/signature for your pinned
        // linphone-sdk-android version and adjust here.
        core.addListener(object : CoreListenerStub() {
            override fun onAccountRegistrationStateChanged(
                core: Core,
                account: org.linphone.core.Account,
                state: RegistrationState?,
                message: String,
            ) {
                android.util.Log.i("SipManager", "Registration state=$state message=$message")
                _regStatus.value = when (state) {
                    RegistrationState.Ok -> RegStatus.REGISTERED
                    RegistrationState.Progress -> RegStatus.CONNECTING
                    RegistrationState.Failed -> RegStatus.FAILED
                    RegistrationState.Cleared, RegistrationState.None -> RegStatus.UNREGISTERED
                    else -> _regStatus.value
                }
                _regDetail.value = when (state) {
                    RegistrationState.Ok -> ""
                    RegistrationState.Failed -> explainFailure(message)
                    else -> message
                }
            }

            override fun onCallStateChanged(
                core: Core,
                call: Call,
                state: Call.State?,
                message: String,
            ) {
                when (state) {
                    Call.State.IncomingReceived -> {
                        activeCall = call
                        _callState.value = CallUiState(
                            phase = CallPhase.INCOMING,
                            remoteAddress = call.remoteAddress.username ?: "",
                        )
                    }
                    Call.State.OutgoingInit, Call.State.OutgoingProgress, Call.State.OutgoingRinging -> {
                        activeCall = call
                        _callState.value = _callState.value.copy(
                            phase = CallPhase.OUTGOING,
                            remoteAddress = call.remoteAddress.username ?: "",
                        )
                    }
                    Call.State.Connected, Call.State.StreamsRunning -> {
                        context.setAudioModeInCall(true)
                        _callState.value = _callState.value.copy(phase = CallPhase.ACTIVE)
                    }
                    Call.State.Paused, Call.State.PausedByRemote -> {
                        _callState.value = _callState.value.copy(isOnHold = true)
                    }
                    Call.State.Resuming -> {
                        _callState.value = _callState.value.copy(isOnHold = false)
                    }
                    Call.State.End, Call.State.Error, Call.State.Released -> {
                        if (activeCall == call) {
                            activeCall = null
                            context.setAudioModeInCall(false)
                            _callState.value = CallUiState()
                        }
                    }
                    else -> Unit
                }
            }
        })

        core.start()
        iterating = true
        iterateHandler.post(iterateRunnable)
    }

    /**
     * transport: "tls" (recommended, typically port 5061), "tcp" or "udp" (typically 5060).
     * This registers a normal SIP extension - the same extension can also be used for
     * WebRTC in a browser, but not registered from both at the same instant on some PBXes.
     */
    fun register(domain: String, port: Int, transport: String, username: String, password: String) {
        _regStatus.value = RegStatus.CONNECTING

        val authInfo = Factory.instance().createAuthInfo(username, null, password, null, null, domain)
        core.addAuthInfo(authInfo)

        val transportType = when (transport.lowercase()) {
            "tcp" -> TransportType.Tcp
            "udp" -> TransportType.Udp
            else -> TransportType.Tls
        }

        val accountParams = core.createAccountParams()
        val identity = Factory.instance().createAddress("sip:$username@$domain")
        accountParams.identityAddress = identity

        val serverAddress = Factory.instance().createAddress("sip:$domain:$port")
        serverAddress?.transport = transportType
        accountParams.serverAddress = serverAddress
        accountParams.isRegisterEnabled = true

        val account = core.createAccount(accountParams)
        core.clearAccounts()
        core.addAccount(account)
        core.defaultAccount = account
    }

    fun unregister() {
        core.defaultAccount?.let { account ->
            val params = account.params.clone()
            params.isRegisterEnabled = false
            account.params = params
        }
        _regStatus.value = RegStatus.UNREGISTERED
    }

    fun call(number: String) {
        val account = core.defaultAccount ?: return
        val domain = account.params.domain
        val address = Factory.instance().createAddress("sip:$number@$domain") ?: return
        core.inviteAddress(address)
    }

    fun answer() {
        activeCall?.accept()
    }

    fun decline() {
        activeCall?.decline(org.linphone.core.Reason.Declined)
    }

    fun hangup() {
        activeCall?.terminate()
    }

    fun toggleMute() {
        core.isMicEnabled = !core.isMicEnabled
        _callState.value = _callState.value.copy(isMuted = !core.isMicEnabled)
    }

    fun toggleHold() {
        val call = activeCall ?: return
        if (_callState.value.isOnHold) call.resume() else call.pause()
    }

    fun sendDtmf(digit: Char) {
        activeCall?.sendDtmf(digit)
    }

    fun destroy() {
        iterating = false
        iterateHandler.removeCallbacks(iterateRunnable)
        core.stop()
    }
}

/**
 * Turn the PBX's raw rejection into something that points at a cause. The
 * distinction that matters most: a timeout means nothing answered (wrong port,
 * blocked, unreachable), while a 401/403 means something answered and refused
 * the credentials.
 */
private fun explainFailure(message: String): String {
    val raw = message.ifBlank { "no response from the PBX" }
    val lower = raw.lowercase()
    val hint = when {
        "timeout" in lower || "timed out" in lower ->
            "Nothing answered on that host/port - check the SIP port and transport, " +
                "and that the phone can actually reach the PBX."
        "forbidden" in lower || "401" in lower || "403" in lower ->
            "The PBX answered but rejected the credentials - check the extension " +
                "number and its SIP secret."
        "not found" in lower || "404" in lower ->
            "The PBX doesn't know that extension - check the extension number."
        "unauthorized" in lower ->
            "Authentication rejected - check the extension's SIP secret."
        "unavailable" in lower || "503" in lower ->
            "The PBX is refusing service right now - it may be blocking this IP."
        else -> "Check the extension, SIP secret, port/transport, and reachability."
    }
    return "$raw. $hint"
}

private fun Context.setAudioModeInCall(inCall: Boolean) {
    val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
    audioManager.mode = if (inCall) AudioManager.MODE_IN_COMMUNICATION else AudioManager.MODE_NORMAL
}
