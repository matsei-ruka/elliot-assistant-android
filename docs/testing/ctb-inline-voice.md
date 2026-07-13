# CTB inline voice — verification and device acceptance

Verification record for [Spec 002 — CTB inline voice transport](../specs/002-ctb-inline-voice.md).
No prompt, reply, transcript, audio, Base64, token or endpoint value belongs in
this record.

## Host verification (2026-07-13)

The host was bootstrapped without system changes using Temurin JDK 17.0.19+10
at `/home/jc/elliot_alderson/.local/toolchains/jdk-17.0.19+10` and Android SDK
platform/build-tools 35 at `/home/jc/elliot_alderson/.android-sdk`. Gradle used
the repository wrapper, one worker, in-process Kotlin compilation and no Gradle
instrumentation agent.

| Gate | Result |
|---|---|
| `:app:testStandardDebugUnitTest` | Pass: 274 tests, 0 failures/errors, 2 existing skips |
| `:app:lintStandardDebug` | Pass: 0 errors; 754 existing warnings and 13 hints reported |
| `:app:assembleStandardDebug` | Pass |
| `git diff --check` | Pass |
| Standard-debug merged-manifest inspection | Pass |

Focused coverage includes the exact CTB wire shape, response audio preference,
invalid-audio text fallback, empty/invalid response failure, OGG/Opus magic,
bounded Base64/file operations, response-body caps, cancellable MockWebServer
calls, endpoint validation, HTTP/Gateway/Hermes routing, write-only credentials,
generation-based stale response suppression and idempotent cache cleanup.

The app permissions in the standard-debug merged manifest are:

- `android.permission.RECORD_AUDIO`
- `android.permission.INTERNET`
- `android.permission.ACCESS_NETWORK_STATE`
- `android.permission.FOREGROUND_SERVICE`
- `android.permission.FOREGROUND_SERVICE_MICROPHONE`
- `android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK`
- `android.permission.POST_NOTIFICATIONS`
- `android.permission.WAKE_LOCK`

AndroidX also contributes the signature-protected debug-only
`com.openclaw.assistant.debug.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`; it is
not a platform/user-granted capability. No Firebase, updater/install, boot,
notification-listener, accessibility, media-projection, scanner, mobile-bridge
or debug-probe component is present in the merged manifest.

## Physical-device acceptance

No Android device was attached to the implementation host. Every row below is
therefore pending; a host build is not evidence of microphone, Assistant-role,
OEM background or media-control behavior.

Record only Android/API, model, network kind, elapsed time and pass/fail.

| Test | API 29 Pixel/AOSP | API 34+ Pixel | Samsung One UI 29+ |
|---|---|---|---|
| Fresh install defaults to CTB and accepts write-only token | Pending | Pending | Pending |
| Set as default Digital Assistant and invoke by system gesture | Pending | Pending | Pending |
| Raw OGG/Opus capture bypasses `SpeechRecognizer` | Pending | Pending | Pending |
| Explicit stop sends once | Pending | Pending | Pending |
| Local amplitude-only silence stop sends once | Pending | Pending | Pending |
| Duration/file caps terminate safely | Pending | Pending | Pending |
| Inline OGG/Opus reply plays instead of local TTS | Pending | Pending | Pending |
| Text-only response invokes local TTS exactly once | Pending | Pending | Pending |
| Screen off / overlay hidden during CTB wait and playback | Pending | Pending | Pending |
| Cancel during capture, wait, prepare and playback suppresses late audio | Pending | Pending | Pending |
| Media controls and audio-focus interruption release playback | Pending | Pending | Pending |
| Success/error/cancel leaves no cache, focus, wake lock or notification | Pending | Pending | Pending |
| Wi-Fi and mobile-data turns both complete | Pending | Pending | Pending |
| 401 and failed health preserve the stored token | Pending | Pending | Pending |

Device acceptance should additionally inspect the installed-package permission
and component surface and verify that the foreground notification contains no
conversation or configuration data.
