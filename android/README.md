# UCM Telnyx - Android app

A native Android companion app (Kotlin + Jetpack Compose) for the same
`ucm-telnyx-app` backend. It reuses the Flask REST/WebSocket API for SMS
messaging unchanged, but calling is completely different from the browser
app: instead of SIP.js/WebRTC over a WSS connection, this registers as a
**real SIP extension** on your UCM6301 using the [Linphone
SDK](https://gitlab.linphone.org/BC/public/linphone-sdk) - the same engine
behind the Linphone softphone. That's a native UDP/TCP/TLS SIP registration
on the PBX's normal SIP port, not the browser's WebRTC/8089 path.

The web app is untouched; this is an additional client, not a replacement.

## Before you start: what I could and couldn't verify

I wrote and hand-reviewed every file here, but **I could not compile this
project**. Two hard blockers, both specific to the sandboxed environment I
built it in, not to the code itself:

1. No Android SDK is installed there.
2. Its network policy blocks `dl.google.com` outright, which is what backs
   Gradle's `google()` repository - so even the Android Gradle Plugin itself
   can't be resolved, let alone Compose or the Linphone SDK.

Android Studio on your machine won't have either restriction, and it's a far
better tool for this than I am for surfacing the exact error if something's
off - real-time redlines, autocomplete against the actual SDK you pull down,
one-click fixes for version mismatches. The one place I'd specifically watch
for a mismatch: `SipManager.kt`'s Linphone listener callback name
(`onAccountRegistrationStateChanged`) and the exact `linphone-sdk-android`
version pinned in `app/build.gradle.kts` - the Linphone API surface has
shifted across versions and I couldn't check it against a real compiler. If
Android Studio redlines it, use its autocomplete on `CoreListenerStub` to
find the right name for whatever version actually resolves.

## Opening and building

1. Install [Android Studio](https://developer.android.com/studio) (it bundles
   a JDK and can install the Android SDK/platform tools on first run).
2. **File > Open**, pick the `android/` folder in this repo.
3. Let Gradle sync - first sync downloads the Android Gradle Plugin, Compose
   libraries, and the Linphone SDK, so it'll take a few minutes.
4. If the Linphone SDK dependency fails to resolve, open
   `app/build.gradle.kts` and check
   [the Linphone Maven repository listing](https://download.linphone.org/maven_repository/org/linphone/linphone-sdk-android/)
   for the current version, then update the version string there.
5. Plug in an Android phone (USB debugging enabled) or start an emulator,
   and hit Run. For a standalone APK instead:
   ```bash
   ./gradlew assembleDebug
   ```
   Output lands at `app/build/outputs/apk/debug/app-debug.apk` - copy that to
   a phone and open it (enable "install unknown apps" for whatever app you
   transferred it through) to sideload without USB debugging.

### Building a signed release APK

A debug APK works fine for your own phone. For something you'd install more
permanently or share:

```bash
keytool -genkeypair -v -keystore release.keystore -alias ucm-telnyx \
  -keyalg RSA -keysize 2048 -validity 10000
```

That generates a local keystore file - keep it and its password safe, you
need the same one for every future update of this app.

Add a signing config to `app/build.gradle.kts`'s `android {}` block:
```kotlin
signingConfigs {
    create("release") {
        storeFile = file("/path/to/release.keystore")
        storePassword = "..."
        keyAlias = "ucm-telnyx"
        keyPassword = "..."
    }
}
buildTypes {
    release {
        signingConfig = signingConfigs.getByName("release")
        // ...
    }
}
```
Then `./gradlew assembleRelease` → `app/build/outputs/apk/release/app-release.apk`.
(Don't commit the keystore or its passwords to git.)

## Configuring it

**Settings tab, "Server URL"**: your backend's public URL (the same one you
gave Telnyx for the webhook), e.g. `https://voip.example.com`. Unlike the
browser app this isn't same-origin, so it has to be entered once.

**Settings tab, SIP fields**: this is a *different* configuration surface
from the web app's WSS/8089 settings, because this app isn't using WebRTC:

- **SIP domain**: your UCM6301's hostname/IP - same value as the web app's
  SIP domain field.
- **Port / transport**: typically **5061 / TLS** (encrypted, recommended) or
  **5060 / UDP or TCP** (plaintext, LAN-only). Confirm the port UCM6301 has
  that transport listening on under **PBX Settings → SIP Settings**.
- **Extension / password**: the same extension number and SIP secret you'd
  use in the web app's Settings tab (**PBX Settings → Extensions →
  (extension) → SIP/IAX Settings → Password**). You do *not* need to enable
  the extension's WebRTC toggle for this app - that's only for the browser
  softphone. A normal SIP extension works as-is.
- If you're off the UCM's LAN, you need the SIP port reachable the same way
  the web app's WSS port would need to be (port-forward or VPN) - this is a
  separate concern from the Telnyx messaging side, which just needs your
  phone to have internet access to reach your backend's public URL.

## Known gaps / follow-ups

This is a first working version of core calling + messaging, not a finished
product. Flagging rather than silently shipping:

- **No speaker/earpiece toggle** - calls use whatever the OS's default audio
  routing picks. Worth adding via Linphone's `AudioDevice` API once you've
  confirmed basic calling works.
- **Incoming calls only work while the app process is alive** (backgrounded
  is fine via the foreground service; force-killed is not). A production
  softphone normally solves this with push notifications waking the app for
  an incoming INVITE - that needs either Linphone's own push-relay service or
  a custom push integration on your PBX side, neither of which is set up
  here.
- **DTMF UI**: `SipManager.sendDtmf()` and the `onDtmf` callback exist, but
  there's no on-screen keypad during an active call yet (the web app has
  one; this doesn't yet).
- Codec selection, ICE/NAT tuning, and call history/logging are all
  Linphone-default behavior, unreviewed.

## Architecture at a glance

```
app/src/main/java/com/ucmtelnyx/app/
  MainActivity.kt      - permissions, service binding, top-level screen state
  SipManager.kt        - Linphone Core wrapper (register/call/answer/hangup/mute/hold/DTMF)
  CallService.kt        - foreground service + call notifications, owns SipManager
  BackendClient.kt      - REST + WebSocket client for the Flask backend
  Models.kt              - JSON models matching the backend's response shapes
  Prefs.kt                - local settings storage (SharedPreferences)
  ui/                      - Compose screens (Login, Phone, Messages, Settings, theme)
```
