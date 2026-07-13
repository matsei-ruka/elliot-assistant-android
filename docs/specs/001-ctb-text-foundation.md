# 001 — CTB text foundation and safety baseline

**Status:** proposed  
**Target repository:** `matsei-ruka/elliot-assistant-android`  
**Upstream baseline:** `yuga-hashimoto/openclaw-assistant` at `05a3bcb8180c52ae6c969cce74723e19cc0d9531`  
**Depends on:** existing production `completion-telegram-bridge` text endpoint  
**Blocks:** 002 bridge voice endpoint; 003 Android raw-audio transport

## Purpose

Validate the fork as a reliable Android default assistant against the existing
text-only Completion Telegram Bridge (CTB), while reducing the inherited
permission and telemetry surface before the APK is used on a personal phone.

This is a transport and Android-lifecycle spike. It deliberately uses device
speech recognition and a configured local TTS provider. It does **not** claim
voice-note preservation; that begins only in Spec 003 after Spec 002 exists.

## User-visible flow

```text
Assistant gesture / in-app mic
  -> Android speech recognition
  -> POST https://bridge.italia.ae/v1/chat/completions
  -> CTB -> Telegram agent
  -> completed text reply
  -> existing TTS provider plays the reply
```

The app must work after the assistant overlay is hidden or the screen locks.
The user may still close it explicitly or interrupt playback.

## Fixed bridge configuration

The app configuration must accept these values without URL rewriting:

| Field | Value |
|---|---|
| Backend kind | existing `OpenClaw HTTP` backend |
| Base URL | `https://bridge.italia.ae/v1/chat/completions` |
| Model | `telegram-agent` (or the model returned by CTB `/v1/models`) |
| Authentication | `Authorization: Bearer <CTB token>` |
| Streaming | disabled |
| Client connect timeout | 30 seconds |
| Client write timeout | 30 seconds |
| Client read/call timeout | **320 seconds** |

The CTB endpoint already receives standard non-streaming OpenAI Chat
Completions JSON and returns `choices[0].message.content`. Do not add an
OpenClaw Gateway, WebSocket, pairing flow, or a second server for this phase.

The API token is never committed, placed in test fixtures, logged, sent to
crash reporting, or displayed after entry. Store it using Android Keystore
backed encrypted storage. Existing plaintext settings must not be used for the
CTB token.

## Scope

### A. Preserve the minimum assistant surface

Keep and test:

- `VoiceInteractionService` / default Assistant role;
- long-press Assistant gesture;
- in-app voice activation;
- system speech recognition for this temporary text-mode path;
- existing TTS playback, interruption and audio focus;
- foreground session lifecycle while listening, waiting, or speaking;
- chat UI only insofar as it uses the same HTTP backend.

Do not enable or test wake word in this phase. It is not needed to validate
Assistant role or the CTB transport and permanently consumes the microphone.

### B. CTB HTTP reliability

1. Change `OpenClawClient` so the request which waits for a completed CTB reply
   has a 320-second read/call budget. The 120-second inherited limit is invalid
   because CTB is configured to wait up to 300 seconds for a Telegram reply.
2. Preserve cancellation: closing the Assistant overlay or pressing interrupt
   must cancel the active OkHttp call and stop the thinking/filler sound.
3. Send `stream: false` explicitly in every CTB request. CTB rejects streaming.
4. Treat an empty completion as a visible failure, never as a successful TTS
   input.
5. Do not use the generic connection-test fallback which POSTs `ping` to the
   chat endpoint: that would create a real Telegram message. CTB connection
   verification must call `GET /healthz`, and configuration validation must
   derive only the origin (`https://bridge.italia.ae`) from the configured
   completion endpoint. If the endpoint is not an HTTPS URL ending in
   `/v1/chat/completions`, show a configuration error rather than guessing.
6. Logs may include request ID, status, elapsed time, and body length. They
   must not contain prompt text, reply text, bearer values, or the full URL
   query string.

### C. Permission and telemetry baseline

The upstream app declares device-control permissions far beyond this product.
Do not ship or install that surface by default.

For this phase the release manifest may retain only:

- `RECORD_AUDIO`
- `INTERNET`, `ACCESS_NETWORK_STATE`
- `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MICROPHONE`
- `POST_NOTIFICATIONS`
- `WAKE_LOCK`

`FOREGROUND_SERVICE_MEDIA_PLAYBACK` is added in Spec 003 when the app plays
remote voice files through a media playback service.

Remove from the release variant, and ensure there is no reachable UI/action
requiring: camera, SMS, contacts, calendar, location, Wi-Fi discovery,
external-storage/media read, activity recognition, install-packages,
write-settings, media projection, boot-start, or accessibility automation.

Disable Firebase Crashlytics/analytics for the CTB build. A voice assistant
must not export prompts, replies, endpoint details, or device identifiers to a
third-party telemetry provider. Local redacted diagnostic logging is enough.

This is a deliberately restricted product fork. The upstream phone-control
features remain source material only; they are not silently retained as
privileged capabilities.

### D. Foreground lifecycle

While the session state is `LISTENING`, `THINKING`, `PREPARING_SPEECH`, or
`SPEAKING`:

- a visible foreground notification exists;
- the process remains alive when the overlay hides or the display locks;
- audio focus is held only while needed and released on success, error,
  explicit close, cancellation, and process cleanup;
- the partial wake lock has a bounded lifetime and is released on every
  terminal path;
- only one assistant request may be in flight.

The user must be able to cancel a request during the full 320-second wait. A
cancelled request must not start TTS when a late HTTP response arrives.

## Non-goals

- OGG/Opus recording or upload;
- sending a Telegram voice note;
- receiving or playing an agent-generated audio file;
- server-side transcription or TTS;
- streaming;
- multiple agents/routing;
- phone tools (SMS, camera, screen, terminal, contacts, accessibility);
- public distribution or Play Store compliance work;
- wake-word reliability work.

## Implementation notes

- Keep the established `VoiceInteractionService`, session overlay, and
  foreground-session architecture. Do not replace it with an activity-only
  assistant.
- Isolate CTB-specific URL validation, health check, timeout and secure token
  storage behind a small `CtbHttpConfig`/client layer. Do not make the generic
  OpenClaw Gateway code aware of CTB internals.
- Use a stable per-install random session ID, not a personal identifier, in
  the OpenAI `user` field. It permits CTB/Telegram conversation continuity
  without sending a device ID.
- A response must go through the current TTS path only after its complete text
  has been received. No sentence chunking/streaming is introduced here.
- Maintain the upstream MIT copyright and license notices in all derived
  source/distribution artifacts.

## Acceptance tests

Run on at least one Pixel and one Samsung device, over Wi-Fi and mobile data.
Record Android version, device model, network kind, elapsed time and outcome;
do not record content.

1. Build a signed debug APK from a clean checkout. Unit tests and lint pass.
2. Fresh install requests only the minimum permissions above; Android Settings
   shows no camera/SMS/location/contacts/calendar permission declaration.
3. Configure the endpoint and token. CTB health verification succeeds without
   sending a Telegram `ping` message.
4. Set the app as the system Digital Assistant. Invoke through the system
   gesture, speak a short request, receive a Telegram-agent reply and hear it
   via TTS.
5. Repeat with the overlay hidden and with the screen locked before the reply.
   The reply is still played exactly once.
6. Hold an agent response for more than 120 seconds; the app remains waiting
   and succeeds before 320 seconds.
7. Cancel during wait, then cause the agent to reply. Nothing is spoken after
   cancellation and no session service/wake lock remains active.
8. Force a 401, 429/409, network loss and 300+ second server wait. Each gives
   a clear recoverable error; none crash or leak token/content to logs.
9. Verify the in-app chat path sends the same completed text and TTS behavior.

## Definition of done

One reviewable commit/PR contains only this baseline. It includes:

- the code and manifest changes above;
- tests for URL validation, request timeout/cancellation and redacted logging;
- a short `docs/testing/ctb-text-spike.md` with device results and known
  failures;
- no token, APK signing material, audio, conversation text, or generated build
  output committed.

Suggested commit title:

```text
feat(ctb): add hardened text-mode assistant transport
```

## Next work

After the acceptance tests pass, implement in order:

1. **Spec 002 — CTB voice endpoint:** authenticated multipart OGG/Opus input,
   `send_file(..., voice_note=True)`, voice-reply capture, signed temporary
   media URLs, expiry cleanup, MIME/size validation and redacted logs.
2. **Spec 003 — Android CTB voice backend:** press-to-talk OGG/Opus recorder,
   cancellable 320-second multipart request, typed `text`/`audio` response,
   Media3 + MediaSession autoplay and `microphone|mediaPlayback` foreground
   lifecycle.

The voice path will preserve the original uploaded OGG/Opus bytes; it will not
transcribe, synthesize, or transcode on the app or bridge.
