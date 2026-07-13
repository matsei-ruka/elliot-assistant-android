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

## Device acceptance matrix (Spec 001 §Acceptance tests)

Fill one row per device/network combination. Status: `pass` / `fail` / `pending`.

| # | Test | Pixel (Wi-Fi) | Pixel (mobile) | Samsung (Wi-Fi) | Samsung (mobile) |
|---|---|---|---|---|---|
| 1 | Signed debug APK from clean checkout; unit tests and lint pass | pending | pending | pending | pending |
| 2 | Fresh install requests only minimum permissions; Settings shows no camera/SMS/location/contacts/calendar declaration | pending | pending | pending | pending |
| 3 | Endpoint+token configured; health verification succeeds without a Telegram `ping` | pending | pending | pending | pending |
| 4 | Default Assistant gesture → speak → Telegram-agent reply via TTS | pending | pending | pending | pending |
| 5 | Reply played exactly once with overlay hidden / screen locked | pending | pending | pending | pending |
| 6 | Reply held > 120 s: app keeps waiting and succeeds before 320 s | pending | pending | pending | pending |
| 7 | Cancel during wait, then agent replies: nothing spoken, no session service/wake lock left | pending | pending | pending | pending |
| 8 | 401 / 429 / network loss / 300+ s wait: clear recoverable errors, no crash, no token/content in logs | pending | pending | pending | pending |
| 9 | In-app chat sends the same completed text with the same TTS behavior | pending | pending | pending | pending |

Record device rows as: `Android <version>, <model>, <network>, <elapsed>, <outcome>`.

## Known deviations and notes

- **Loopback HTTP escape.** `CtbHttpConfig.validateEndpoint` accepts plain
  HTTP only for `localhost`/`127.0.0.1`/`::1`, so the transport can be
  exercised against MockWebServer in unit tests. Every non-loopback endpoint
  must be HTTPS ending in `/v1/chat/completions`, exactly as Spec 001 §B.5
  requires.
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
