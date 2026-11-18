# 0018 — Follow-ups from the independent review (2026-11-18)

Status: Accepted · Date: 2026-11-18

## Context

`docs/reviews/2026-11-18-opus-review.md` is an independent whole-project review. It found no path to the internet but several places where what the docs claim and what the code does
differ. This ADR records the decisions taken in response; the finding numbers (F-H1 and so on) are the review's.

## Decisions (done)

- **Nothing a background job throws may end the process or silently stop the job (F-H4).** The root scope has an exception handler that logs the class name only; the 15-second tick runs
  each job separately and carries on after a failure; a device observer that throws is logged and started again with back-off.
- **A restored backup is not overwritten (F-H5).** The session writes through a store that is frozen once a restore succeeds (writes are dropped and the home says so) until Patchbay is really
  restarted. The restore message says to quit completely (tray menu, Quit; on Android also stop background watching).
- **A changed key stays an alert (F-M1).** For Cast, webOS, Bambu and Android TV, as for HomeKit, a TrustChanged result latches: no connection is attempted until the device is removed, or paired
  again with a different key. Before, a later handshake that happened to match cleared the alert by itself.
- **Scheduled automations catch up after sleep (F-H3).** A time that fell in the gap since the automation was last looked at runs once, late, if it is at most 10 minutes old, and Activity says how
  late. A time before the automation was created, enabled or Patchbay started never runs. This is the best that can be done without alarms; whether Android needs exact alarms (a new permission,
  so an ADR of its own under invariant 8) is decided after the hardware test N2.10.
- **A device-triggered automation is capped at 6 runs in 10 minutes (F-M6)**, as a second line of defence behind the 30-second chain window.
- **A condition that cannot be read switches its automation off (L4).**
- **Android restarted while locked does not run on an empty home (F-H6).** If the phone is locked the application does not boot (the database key is unusable); the service stops itself and is not
  sticky; opening the app after unlock boots and, if background watching was on, starts it again.
- **The log redactor also hides URL credentials, `Basic`/`Bearer` values, "access code 12345678" style text and HomeKit setup codes (F-M2).**
- **Platform cryptography is tried at start and shown in diagnostics (F-M3, X1).** HomeKit uses whichever ChaCha20-Poly1305 name the platform provides.
- **A Tuya data point we cannot use is skipped alone (F-M5).** The payload cap is 16 KiB, a string data point may be 1024 characters.
- **The policy script covers every shipped source set and the bypasses the review planted (F-H2, short term).** Starting another program is allowed only in the ffmpeg launcher (PROC001).
- **The Windows PIN back-off survives a restart (L3).** A failed HomeKit unpair is reported (F-M4). The interop tests report skipped, not passed, when their outside program is absent (F-M7).

## Open (decided later, with the hardware results)

- **Cast pinning (F-H1).** Chromium rejects Cast receiver certificates valid for more than four days, so a receiver's TLS key may be renewed often and the pinned-leaf approach may raise false alerts.
  Hardware check C1.9 shows whether it does. If it does: pin the device-authentication certificate (the stable platform key that signs the TLS certificate) instead.
- **A compiled-code network check (F-H2 proper)**, signature verification of dependencies (F-M10), a CI interop job, running the Windows runtime in CI (L13): after the hardware pass.
- **RTSP Basic-auth downgrade (F-M9)** and **ffmpeg verification (F-M8):** next; neither changes anything visible to the owner.
- **Home Assistant versus continuing (PLAN W5):** the review's advice is a hybrid if the owner will run an always-on box, otherwise continue and cut scope to the devices in the house. Owner decision.

## Consequences

- Features are frozen until the first hardware pass is done and its findings are fixed (the plan said so; two features were added anyway).
- Docs no longer say "mutation-tested" for random-input tests.
