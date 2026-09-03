package com.ucmtelnyx.app

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.linphone.core.AudioDevice
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
    val isSpeakerOn: Boolean = false,
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

    // What we're currently registered as, so a repeated register() with identical
    // settings is a no-op rather than a disruptive account rebuild.
    private var registeredAs: String? = null

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
                android.util.Log.i("SipManager", "Call state=$state message=$message")
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
                        // Deliberately NOT comparing against a stored reference. The
                        // Call handed to this callback is a wrapper around a native
                        // object and isn't guaranteed to be the same Java instance we
                        // kept, so an identity check can silently miss the hangup and
                        // strand the UI on the call screen. This app only ever has one
                        // call at a time, so any End/Error/Released means "call over".
                        activeCall = null
                        context.setAudioModeInCall(false)
                        _callState.value = CallUiState()
                    }
                    else -> Unit
                }
            }
        })

        core.start()
        iterating = true
        iterateHandler.post(iterateRunnable)

        // Build marker: if this line is missing from logcat, the phone is running
        // an older APK than the source being debugged.
        android.util.Log.i("SipManager", "SipManager started [build: speaker-diag-4]")
    }

    /**
     * transport: "tls" (recommended, typically port 5061), "tcp" or "udp" (typically 5060).
     * This registers a normal SIP extension - the same extension can also be used for
     * WebRTC in a browser, but not registered from both at the same instant on some PBXes.
     */
    fun register(
        domain: String,
        port: Int,
        transport: String,
        username: String,
        password: String,
        force: Boolean = false,
    ) {
        val wanted = "$username@$domain:$port/$transport"

        // Re-registering tears down and rebuilds the account (clearAccounts below).
        // Doing that during a call drops the call, and the app re-runs this on every
        // Activity recreation - so skip when nothing changed, and never touch the
        // accounts while a call is up. `force` is for the explicit Reconnect button,
        // which should still work when already registered - but not mid-call.
        if (!force && wanted == registeredAs &&
            (_regStatus.value == RegStatus.REGISTERED || _regStatus.value == RegStatus.CONNECTING)
        ) {
            android.util.Log.i("SipManager", "Already registered as $wanted - skipping")
            return
        }
        // Check our own call state as well as the Core's. core.currentCall can
        // report null in states where a call is still very much up (held, early
        // media), and re-registering then tears down the account underneath it -
        // which shows up in logcat as "Unregistration done" mid-call, followed by
        // the call dying.
        if (core.currentCall != null || _callState.value.phase != CallPhase.IDLE) {
            android.util.Log.i(
                "SipManager",
                "Call in progress (phase=${_callState.value.phase}) - deferring re-registration",
            )
            return
        }

        registeredAs = wanted
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
        registeredAs = null
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

    // Always fall back to the Core's own view of the current call: our stored
    // reference can be stale (see the note in the End/Released handler), and a
    // hangup that silently does nothing leaves the other side stuck on the call.
    private fun currentCall(): Call? = activeCall ?: core.currentCall

    fun answer() {
        currentCall()?.accept()
    }

    fun decline() {
        currentCall()?.decline(org.linphone.core.Reason.Declined)
    }

    fun hangup() {
        val call = currentCall()
        if (call != null) {
            call.terminate()
        } else {
            // Nothing tracked but the UI thinks there's a call - make sure the
            // PBX gets a BYE regardless.
            core.terminateAllCalls()
        }
    }

    fun toggleMute() {
        core.isMicEnabled = !core.isMicEnabled
        _callState.value = _callState.value.copy(isMuted = !core.isMicEnabled)
    }

    fun toggleHold() {
        val call = currentCall() ?: return
        if (_callState.value.isOnHold) call.resume() else call.pause()
    }

    /**
     * Route call audio to the loudspeaker or back to the earpiece.
     *
     * Belt and braces on purpose, because the two obvious approaches each fail
     * on their own: setSpeakerphoneOn is deprecated on API 31+ and often does
     * nothing there, and Linphone's Core manages its own routing and can put
     * the audio back. So this sets the platform's communication device (the
     * modern API that actually works) *and* Linphone's output device, and logs
     * both device lists so a failure is diagnosable from logcat.
     */
    fun toggleSpeaker() {
        android.util.Log.i("SipManager", "toggleSpeaker() entered")
        val turnOn = !_callState.value.isSpeakerOn
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

        // The audio session must be in communication mode for either routing API
        // to take effect. It's normally set when the call connects, but a toggle
        // during ringing would otherwise be ignored.
        if (audioManager.mode != AudioManager.MODE_IN_COMMUNICATION) {
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        }

        // Android 12 (API 31) deprecated setSpeakerphoneOn and it frequently has
        // no effect there - setCommunicationDevice is the API that actually
        // moves the audio on modern devices.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val wantedType =
                if (turnOn) AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                else AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
            val available = audioManager.availableCommunicationDevices
            android.util.Log.i(
                "SipManager",
                "Communication devices: " + available.joinToString { "${it.productName}/${it.type}" },
            )
            val target = available.firstOrNull { it.type == wantedType }
            if (target != null) {
                val applied = audioManager.setCommunicationDevice(target)
                android.util.Log.i("SipManager", "setCommunicationDevice(${target.type}) -> $applied")
            } else {
                android.util.Log.w("SipManager", "No communication device of type $wantedType")
            }
        } else {
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn = turnOn
        }

        // Also tell Linphone, which manages its own routing and can otherwise
        // put the audio back where it wants it.
        val wantedLinphoneType = if (turnOn) AudioDevice.Type.Speaker else AudioDevice.Type.Earpiece
        val devices = core.audioDevices
        android.util.Log.i(
            "SipManager",
            "Linphone devices: " + devices.joinToString { "${it.deviceName}/${it.type}" },
        )
        devices.firstOrNull { it.type == wantedLinphoneType }?.let { device ->
            currentCall()?.outputAudioDevice = device
            android.util.Log.i("SipManager", "Linphone output -> ${device.deviceName}")
        }

        _callState.value = _callState.value.copy(isSpeakerOn = turnOn)
    }

    fun sendDtmf(digit: Char) {
        currentCall()?.sendDtmf(digit)
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
    if (!inCall) {
        // Don't strand the device in speakerphone once the call ends, and start
        // the next one on the earpiece.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.clearCommunicationDevice()
        } else {
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn = false
        }
    }
}
