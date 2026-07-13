# 002 — CTB inline voice transport for Android Assistant

**Status:** accepted for implementation  
**Target repository:** `matsei-ruka/elliot-assistant-android`  
**Android baseline:** `feat/ctb-text-foundation` at `7b5dcf0`  
**Server contract:** `matsei-ruka/completion-telegram-bridge` v0.2.0 at `4e618f0`  
**Depends on:** Spec 001 text foundation

## Purpose

Make the restricted fork usable as the Android default Assistant while preserving
the user's voice note end to end. For an `OPENCLAW_HTTP`/CTB Assistant session,
the app records OGG/Opus and posts those original encoded bytes directly to CTB.
It does not invoke `SpeechRecognizer`, perform STT, or synthesize the primary
reply locally. A valid inline CTB voice reply is played before any text fallback
is considered.

The existing text pipeline remains available to Chat, Hermes, and OpenClaw
Gateway routes. This specification replaces the obsolete multipart/media-URL
roadmap at the end of Spec 001: deployed CTB uses the OpenAI chat-completions
inline audio shape on the existing endpoint.

## Production decisions

1. Raise `minSdk` from 26 to **29**. Android 10 is the first supported release
   where platform `MediaRecorder.OutputFormat.OGG` plus
   `MediaRecorder.AudioEncoder.OPUS` is a clean production capture path. The fork
   will not add a native codec solely for Android 8/9.
2. Use `MediaRecorder` with microphone input, mono 48 kHz Opus in an OGG
   container. CTB forwards the resulting file without transcription or
   transcoding.
3. Use the configured `POST /v1/chat/completions` only. There is no second voice
   endpoint, multipart upload, signed media URL, or server deployment in scope.
4. Keep playback owned by the active `VoiceInteractionSession`, backed by a
   platform `MediaSession`, explicit audio focus, a partial wake lock, and the
   existing session foreground service. The service declares both `microphone`
   and `mediaPlayback` types so capture, the long network wait, and playback
   remain viable with the overlay hidden or the screen off.

## User interaction and capture stop semantics

Default Assistant invocation exposes an activation gesture, not reliable
hardware press/release events. The app therefore does **not** claim press-to-talk
release semantics.

Invocation starts recording after the foreground session is established. The
overlay shows a recording state and makes the microphone/sphere an explicit
**Stop and send** control. Recording also ends on either:

- local, amplitude-only end-of-speech detection after speech has first been
  observed and at least 1.2 seconds of sustained silence follows; or
- the first hard guard reached: 60 seconds or 8 MiB.

Amplitude sampling is local and produces no transcript. Initial silence does not
send an empty request; the user may stop explicitly, and the 60-second guard
remains authoritative. A recorder stop failure, too-short/empty file, size
violation, or invalid OGG/Opus file is a visible error and the file is deleted.

Hardware/media interruption during recording cancels the current turn; it does
not implicitly upload a partial file. A new turn begins only after cancellation
and recorder teardown complete.

## State machine

The CTB voice turn has one authoritative generation and these states:

```text
IDLE
  -> RECORDING
  -> WAITING_FOR_REPLY
  -> PREPARING_PLAYBACK
  -> PLAYING_REMOTE_AUDIO
  -> IDLE

WAITING_FOR_REPLY
  -> PREPARING_LOCAL_TTS -> PLAYING_LOCAL_TTS -> IDLE   (text-only fallback)

any active state -> CANCELLED -> IDLE or a new RECORDING generation
any active state -> ERROR -> IDLE on retry
```

The existing overlay maps `RECORDING` to `LISTENING`, `WAITING_FOR_REPLY` to
`THINKING`, preparation to `PREPARING_SPEECH`, and either player to `SPEAKING`.

Only valid transitions for the current generation may change UI or start
playback. Starting a replacement turn cancels and joins the previous turn first.
Cancellation stops the recorder, OkHttp `Call`, remote player and TTS before a
replacement can acquire them. A response for an old/cancelled generation is
discarded and its cache file deleted; it can never start playback.

## Request contract

After validating the private input file, the client sends exactly one bounded
audio part. `stream` is always false.

```json
{
  "model": "telegram-agent",
  "user": "<stable random install session id>",
  "stream": false,
  "modalities": ["text", "audio"],
  "messages": [{
    "role": "user",
    "content": [{
      "type": "input_audio",
      "input_audio": {
        "data": "<base64 OGG/Opus>",
        "format": "ogg"
      }
    }]
  }]
}
```

CTB accepts `format` values `ogg` and `opus`; this client emits `ogg` because it
describes the container produced by `MediaRecorder`. The optional OpenAI `audio`
output configuration is omitted: CTB accepts but ignores it and always returns
the Telegram OGG/Opus voice note. No text caption is added, so no local
transcription is needed.

Authentication, endpoint validation, timeouts and health checks remain those of
Spec 001: Bearer token, exact HTTPS `/v1/chat/completions`, 30-second
connect/write and 320-second read/call budget, and origin-only `GET /healthz`.

## Response contract and fallback

The preferred successful response is:

```json
{
  "choices": [{
    "message": {
      "role": "assistant",
      "content": null,
      "audio": {
        "id": "audio_...",
        "data": "<base64 OGG/Opus>",
        "transcript": "optional agent text",
        "expires_at": 1752403200
      }
    }
  }]
}
```

CTB v0.2.0 at `4e618f0` does not emit `audio.format`; its server contract fixes
reply bytes to Telegram OGG/Opus. The parser accepts an optional future
`audio.format`, requires `ogg` or `opus` when present, otherwise infers `ogg`,
and always verifies the actual bytes before playback. It never trusts a filename,
MIME string, or the inference alone.

The parser prefers `choices[0].message.audio.data`. If no audio object is present
and non-empty `message.content` exists, the app invokes the existing local TTS
path exactly once. An empty completion, invalid Base64, unsupported declared
format, oversized body/audio, bad OGG magic, missing `OpusHead`, decode failure,
or player preparation failure is a visible error. Raw JSON and server response
bodies are never spoken.

## Limits and bounded memory

CTB's decoded input limit is 10 MiB and production nginx accepts a 20 MB encoded
request body. The app deliberately leaves headroom:

| Item | App limit | Rationale |
|---|---:|---|
| Capture duration | 60 seconds | Bounded Assistant turn and battery use |
| Encoded input file | 8 MiB | Below CTB's 10 MiB decoded limit |
| Input Base64 | 11,184,812 characters | Exact ceiling for an 8 MiB input |
| Request JSON | 12 MiB | Bounds serialization below nginx's 20 MB body |
| HTTP response JSON | 12 MiB | Bounds unknown/content-length and buffered reads |
| Decoded reply audio | 8 MiB | Bounds allocation, disk use and playback cache |
| OGG validation scan | first 64 KiB | Requires `OggS` at byte zero and `OpusHead` |

Input size is checked before Base64 encoding. Encoded length is checked before
request construction. Response content length is rejected when known and the
body is counted while reading when unknown. Audio Base64 length is checked before
decode, decoded bytes are counted while streaming into a private cache file, and
partial files are removed on every failure.

## Playback, foreground lifetime and interruption

Remote audio is prepared from the validated private cache file by a
lifecycle-owned player with speech audio attributes. Playback starts only after
transient audio focus is granted. A platform `MediaSession` publishes preparing,
playing, paused/stopped and completed state for system/media controls. Focus loss,
Assistant close, barge-in, a replacement turn, session destruction, or player
error stops and releases the player.

The session foreground notification remains visible for recording, the CTB wait,
preparation and playback. The service uses the declared
`microphone|mediaPlayback` type and the app therefore adds
`FOREGROUND_SERVICE_MEDIA_PLAYBACK` to Spec 001's seven-permission baseline.
`WAKE_LOCK` remains required for the bounded network wait and screen-off
playback. The notification is not a store for content and contains no transcript,
endpoint or request details.

## Terminal cleanup and file lifetime

One idempotent terminal cleanup path is used for success, configuration error,
empty/invalid response, network failure, recorder failure, TTS failure, player
failure, close and `onDestroy`. It:

1. cancels active capture/request/playback jobs;
2. stops filler audio, thinking tones, TTS, remote player and recorder;
3. abandons audio focus and releases both session/service wake locks;
4. stops the session foreground service;
5. deletes current and stale CTB input/output files; and
6. restores hotword state when appropriate.

Barge-in uses the same component stop operations but deliberately keeps the
foreground session and wake lock while it cancels and joins the old generation,
then starts a new capture. Cleanup operations and file deletion are idempotent.

## Security and privacy

- Input audio, reply audio, transcripts, Base64, bearer tokens, full endpoints,
  request JSON and response bodies are never logged.
- Safe HTTP logs contain only a local random request id, status, elapsed time,
  body length and error class.
- The token remains write-only in encrypted `BackendRepository` storage. A 401,
  failed health check or voice request failure never clears it.
- Input and output files live only in the app-private cache. Input is deleted as
  soon as upload completes or fails. Output exists only through preparation and
  playback. Session startup/close removes abandoned CTB cache files.
- No conversation audio is placed in media storage, backups, notifications,
  crash reporting, fixtures or build artifacts.
- Release continues to deny all cleartext. Debug permits HTTP only to loopback.

## Test plan

Host unit tests cover:

- exact request wire shape (`stream:false`, modalities and one `input_audio`);
- response audio preference and text-only fallback selection;
- optional/fixed response format handling;
- OGG magic plus `OpusHead` validation and malformed rejection;
- input/output and Base64 size ceilings, counted decode and partial-file cleanup;
- bounded HTTP response reads and cancellable OkHttp calls with MockWebServer;
- CTB HTTP routing while Gateway is offline, with Hermes/Gateway unchanged;
- endpoint validation and redacted metadata logging;
- state transitions, cancellation generation changes and stale-reply rejection;
- idempotent recorder/player/file cleanup.

Build gates are `testStandardDebugUnitTest`, `lintStandardDebug`,
`assembleStandardDebug`, merged-manifest inspection and `git diff --check`.

## Device acceptance matrix

Physical-device execution remains required and must record only device/API,
network kind, elapsed time and pass/fail—never content.

| Device class | API | Wi-Fi | Mobile | Screen off during wait | Overlay hidden | Cancel recording/wait/playback | Media controls |
|---|---:|---|---|---|---|---|---|
| Pixel / AOSP | 29 | Pending | Pending | Pending | Pending | Pending | Pending |
| Pixel / current Android | 34+ | Pending | Pending | Pending | Pending | Pending | Pending |
| Samsung One UI | 29+ | Pending | Pending | Pending | Pending | Pending | Pending |

Acceptance additionally verifies audible OGG/Opus capture quality, explicit stop,
silence stop, 60-second/file caps, text fallback exactly once, no late playback
after cancellation, no residual notification/wake lock/cache file, and the
merged permission list.

## Non-goals

- reliable hardware press/release or true push-to-talk semantics;
- Android 8/9 support or a custom Opus codec;
- on-device or Android `SpeechRecognizer` transcription for CTB voice turns;
- client-side transcoding, denoising, ASR or primary TTS;
- streaming requests or streamed audio playback;
- multipart upload, a second endpoint, signed media URLs or CTB deployment;
- retaining conversation audio after playback;
- multiple overlapping turns or queued voice requests;
- wake-word reliability work;
- restoring phone-control, accessibility, notification-listener, updater,
  install-package, boot, media-projection or other removed capabilities.
