# CTB text spike — device test results

Acceptance run for [Spec 001 — CTB text foundation](../specs/001-ctb-text-foundation.md).
Record Android version, device model, network kind, elapsed time and outcome;
never record prompt or reply content.

## Automated verification (this commit)

| Check | Result |
|---|---|
| Unit tests (`:app:testStandardDebugUnitTest`) | pass |
| CTB endpoint validation (`CtbHttpConfigTest`) | pass |
| 320 s read/call budget, `stream: false`, bearer header (`OpenClawClientCtbTest`) | pass |
| Empty completion → visible failure, never TTS input | pass |
| `GET /healthz` verification, no chat-endpoint ping | pass |
| In-flight call cancelled immediately on coroutine cancel | pass |
| Redacted log format (`CtbLogTest`) | pass |

## Device acceptance matrix

Fresh install (or clear data) first on Pixel, then on Samsung; each on Wi-Fi
and mobile data. Status: `pass` / `fail` / `pending`.

| # | Test | Pixel (Wi-Fi) | Pixel (mobile) | Samsung (Wi-Fi) | Samsung (mobile) |
|---|---|---|---|---|---|
| 1 | CTB onboarding completes without QR or Gateway | pending | pending | pending | pending |
| 2 | Only microphone/notification permissions requested | pending | pending | pending | pending |
| 3 | App can be set as the system Digital Assistant | pending | pending | pending | pending |
| 4 | Gateway offline + Assistant gesture → listening starts | pending | pending | pending | pending |
| 5 | Short request → CTB → Telegram reply → local TTS exactly once | pending | pending | pending | pending |
| 6 | Reply still played with overlay hidden and screen locked | pending | pending | pending | pending |
| 7 | Reply held > 120 s succeeds before 320 s | pending | pending | pending | pending |
| 8 | Cancel during THINKING → no late TTS | pending | pending | pending | pending |
| 9 | Wrong token → 401 shown, saved token NOT cleared | pending | pending | pending | pending |
| 10 | Network lost and restored → clear recoverable error | pending | pending | pending | pending |
| 11 | After success/error: no foreground service or wake lock left | pending | pending | pending | pending |

Record device rows as: `Android <version>, <model>, <network>, <elapsed>, <outcome>`.

## Known deviations and notes

- **Loopback HTTP escape (debug only).** `CtbHttpConfig.validateEndpoint`
  accepts plain HTTP only for `localhost`/`127.0.0.1`/`::1` and only in debug
  builds (`allowLoopbackHttp = BuildConfig.DEBUG`), so the transport can be
  exercised against MockWebServer. Release refuses all cleartext both in the
  validator and in `network_security_config`. Every non-loopback endpoint
  must be an HTTPS URL with the exact `/v1/chat/completions` path and no
  userinfo, query, or fragment.
- **Routing.** The session resolves its backend exactly once at open via
  `VoiceSessionRouter` (pure, unit-tested): OPENCLAW_HTTP works with the
  gateway offline; gateway health/session management only run for a
  OPENCLAW_GATEWAY route; Hermes is unchanged.
- **Invoke gate.** `BuildConfig.CTB_RESTRICTED` makes `InvokeDispatcher`
  refuse every device/tool invoke with the stable `CAPABILITY_DISABLED`
  error before touching any handler.
- **Health check is unauthenticated.** `GET /healthz` is sent without the
  bearer token: a healthz endpoint that ignored auth would otherwise report
  "verified" for a wrong token. Token validity is exercised by the first real
  request (test 8 covers the 401 path).
- **Update checker disabled.** The upstream GitHub release check fed an APK
  install flow whose `REQUEST_INSTALL_PACKAGES` permission was removed; it
  now returns "no update" unconditionally.
- **Notification listener removed.** Not explicitly listed in Spec 001 §C,
  but notification access is a privileged upstream capability and was removed
  from the manifest under the "not silently retained" rule. Flagged for
  review.
- **Interrupt during THINKING.** The overlay sphere is now tappable during
  the 320-second wait and cancels the in-flight OkHttp call (previously only
  SPEAKING/PREPARING_SPEECH were interruptible and the HTTP job survived the
  interrupt).

## Known failures

None recorded yet — populate during the device runs.
