# Handoff — continue Patchbay in a new Claude session

Written 2026-11-05 at the end of the first long session. Read this first, then `docs/STATUS.md`.

## Final state at end of session (07:4x UTC, 2026-11-05)

- Work branch `ci/verification-bootstrap` holds everything. The last commits are the Android app lock (androidx.biometric added,
  verification metadata bootstrapped) and permission-allowlist updates.
- **CI: all 7 jobs green at `fac8424`** (the latest; includes the first Windows test fixes, portable Windows build, Settings screen). Since `898e727` it adds: camera playback (Android), Tuya bulbs, Cast research, and the v2 dark-first redesign with 29 re-recorded goldens. Download links are in `docs/NEXT_STEPS.md`. (`5df65a6` failed once in the SBOM job on a transient plugin-resolution error; a re-run was green.) That run's APK/MSI artifacts are the MVP
  builds to install.
- Next for the MVP: the owner tests on real devices (`docs/HARDWARE_TEST.md`: G1, T1, B1, L1, plus K1 Kasa and E1 eufy), then fix what breaks.
- Waiting on the owner: Lepro LampUX and Lefant vacuum model numbers + approval of ADR 0007 (`docs/protocols/tuya.md`).

## Paste this to start a new session

> Continue the Patchbay project in https://github.com/gustavotm7/patchbay. Work on the branch
> `ci/verification-bootstrap` (it's ahead of `main`). First read `CLAUDE.md` (rules and privacy invariants),
> then `docs/HANDOFF.md` and `docs/STATUS.md`. Check that the latest CI run on that branch is green before
> changing anything. Then continue with "Next steps" in HANDOFF.md, item 1 first. Commit small, push often,
> and wait for CI after each push.

## Where things are

- **Repo:** `gustavotm7/patchbay`
  - Branch `ci/verification-bootstrap`: all work so far, ~50 commits.
  - `main`: Phase 0 baseline only. No PR is open; open one when you want to review or merge.
- **CI** (GitHub Actions `ci`, 7 jobs): every push to the branch ran green up to `560fc88` (Bambu).
  `441b015` (Windows PIN lock) is green too. If a later commit is red, the
  likely cause is the new UI in `ui/.../screens/Lock.kt` or `app-windows/.../Main.kt`; read the job log and
  fix.
- **Installable builds:** Actions → ci → latest green run → Artifacts `patchbay-android` (APK) and
  `patchbay-windows-msi`.

## What works (simulator-tested; not yet tried on real devices)

| Device / feature | Code | Tests |
|---|---|---|
| Govee lights (LAN UDP) | `core/.../adapter/govee/` | codec + contract + LanHome + persistence |
| LG webOS TV (pinned WSS 3001, pairing, volume/mute/inputs/media, power off + Wake-on-LAN on) | `core/.../adapter/webos/` | SSAP codec + 7 contract tests |
| Bambu H2D (pinned MQTT/TLS 8883 + access code; monitor, pause/resume/stop, lights) | `core/.../adapter/bambu/` | codec + 5 contract tests |
| Network census (mDNS + SSDP) and exposure report with router-rule suggestions | `core/.../discovery/`, `core/.../home/ExposureReport.kt` | codec + loopback tests |
| NetGuard (LAN-only sockets), TOFU TLS pinning, redacted session log | `core/.../net/`, `core/.../log/` | unit + loopback TLS tests |
| Encrypted storage (SQLCipher / sqlite-jdbc-crypt; Keystore / DPAPI key) | `core/.../store/`, `app-android/.../AndroidStorage.kt` | desktop tests; Android builds in CI |
| App lock: Windows PIN (PBKDF2 hash in the encrypted DB, auto-lock on unfocus) | `core/.../lock/`, `app-windows/.../DesktopLock.kt`, `ui/.../Lock.kt` | logic tests; UI builds in CI |
| UI: Control Room design system, demo home, live network screen, light / TV / printer panels, pairing panel | `ui/` | CI screenshot job (25 renders) |

## Priority: MVP first (owner's direction, 2026-11-05)

Don't add features yet. The MVP is: **the owner's real Govee lights, LG TV and Bambu H2D work end to end on the
phone and the laptop**, with scan, encrypted storage and app lock. Everything else (Cast, eufy, ESPHome,
ecobee, Phase 2) waits until the MVP is solid on real hardware.

MVP checklist:
- [x] Govee, LG webOS and Bambu adapters plus pairing UI (simulator-tested; CI green at `560fc88`)
- [x] Encrypted storage, census, exposure report
- [x] Windows PIN lock (CI green at `441b015`)
- [x] Android app lock: BiometricPrompt (strong biometric or device PIN), auto-lock after 1 min in background (`AndroidLock.kt`)
- [ ] Owner runs `docs/HARDWARE_TEST.md` G1.x / T1.x / B1.x on real devices; fix what breaks (step 2)
- [x] Bambu manual add ("Add by address"); test it only if SSDP discovery fails on the real H2D (B1.8)
- [ ] Merge `ci/verification-bootstrap` → `main` via a PR once the above are green

Keep it easy to build on: every new system follows `docs/ADDING_AN_ADAPTER.md`, and the UI renders from
capabilities, so most additions need no new screens.

## Next steps (in order)

1. ~~Android app lock~~ done (see the checklist). The original plan, kept for reference:
   1. Add `androidx.biometric:biometric` (stable) to `gradle/libs.versions.toml` and `app-android`.
   2. Make `MainActivity` a `FragmentActivity`.
   3. Implement `LockController` with `BiometricPrompt` (`BIOMETRIC_STRONG or DEVICE_CREDENTIAL`). Keep an
      `AppLock` in `PatchbayApplication`; call `onBackground`/`onForeground` from the activity's `onStop`/`onStart`.
   4. Wrap `PhoneApp` in `LockGate`.

   **New dependency → refresh checksums:** push to the branch; the `verification-bootstrap` workflow prints
   the new metadata (gzip+base64 between `PB-METADATA-BEGIN/END`) in its job log. Decode it, diff it, and
   commit `gradle/verification-metadata.xml`. Also add a `docs/DEPENDENCIES.md` row.
2. **Fix whatever the owner's hardware tests reveal.** Firmware differences are the main risk: webOS power
   and volume shapes, Bambu discovery, Govee model quirks. Ask for the session log
   (`%LOCALAPPDATA%\Patchbay\logs\` on Windows) and add the failing case to the simulator *first*, then fix.
3. ~~Bambu manual add~~ done ("Add by address" on the network screen; HARDWARE_TEST B1.8).
4. ~~Remember the TV's MAC across restarts~~ done: it's learned at pairing and stored encrypted with the client key. TVs paired before this change learn it on the first connection of each session.

**After the MVP:**

5. **eufy RTSP cameras**: status adapter done (HARDWARE_TEST E1.x). Still to do: in-app viewing, which needs a player and ADR 0008 (proposed: guarded sockets) (Media3 on Android; desktop TBD). TP-Link Kasa plugs are done too (K1.x). Tuya: ADR 0007 accepted; the bulb adapter (Lepro, protocol 3.3) is built (HARDWARE_TEST U1.x, key steps in `docs/TUYA_KEYS.md`). The Lefant vacuum needs its model + data-point list; protocol 3.4/3.5 is a follow-up if the bulbs use it.
6. ~~Cast / Google TV~~ adapter built (HARDWARE_TEST C1.x); the Android TV Remote v2 part (remote keys) is still open.
7. ~~Rooms for live devices~~ done (grouped tiles, Room picker; S1.x), scenes done (lights and plugs only). Next: the floor-plan editor, Jazzer fuzzing in CI, then
   Phase 2 (ecobee HAP, scenes, automations, ledger, widgets, tray/command bar, peer sync).

## How to work in the cloud sandbox

- Google Maven and `downloads.gradle.org` are blocked, so **Android and UI code only compile in CI**.
  JVM code builds locally with Gradle 9.8.2 from GitHub's mirror (sha256 `21bd5da2…9607`):

  ```bash
  curl -sSL -o g.zip https://github.com/gradle/gradle-distributions/releases/download/v9.8.2/gradle-9.8.2-bin.zip && sha256sum g.zip
  ```

  ```bash
  unzip -q g.zip && ./gradle-9.8.2/bin/gradle -Ppatchbay.jvmOnly=true :core:jvmTest :simulators:jvmTest :hub:build
  ```

- CI status and logs are readable without a token through the public GitHub API:

  ```bash
  curl -s "https://api.github.com/repos/gustavotm7/patchbay/actions/runs?head_sha=<sha>"
  ```

  For a job's log, use `…/actions/jobs/<id>/logs`.
- Screenshots: the bootstrap workflow also prints all PNGs (a tarball between `PB-SCREENS-BEGIN/END`), so you
  can decode and look at them. The 25 goldens are committed in `docs/design/screens/` (recorded 2026-11-06); CI fails on drift. To re-record after an intentional UI
  change: push to a `ci/verification-bootstrap*` branch, decode the PNGs from the `export-screens` job log, check the sha256 the job prints, review the images, commit them.
  Note `ci` ignores docs-only pushes, so touch a non-doc file (or the workflow) to get CI to check a docs-only change.
- Gotchas we hit:
  - Kotlin KDoc containing `/*` (e.g. `font/*.ttf`) opens a nested comment.
  - `fun test() = runBlocking { … }` is silently skipped by JUnit; write `: Unit =`.
  - Keep JVM-only code out of `commonMain` (it goes in `jvmShared`).
  - Tests opening raw sockets belong in `simulators/` (policy NET001).
  - Load test keystores by file path, not as classpath resources.

## Decisions already made

- `applicationId` is `dev.patchbay`.
- Native Windows title bar.
- Whole-app `FLAG_SECURE` for now.
- The eufy lock stays read-only (no lock/unlock command) until an ADR approves one.

**Deferred by you:** ADRs 0001–0006 approval and the repo licence.

**Still needed from you:** the MY DEVICES details (brief §12), eufy camera and lock models, and results from
`docs/HARDWARE_TEST.md` on your real lights, TV and printer.

## Notes on the two unbuilt Phase 1 adapters (2026-11-08)

- **Google TV remote keys (Android TV Remote v2):** needs a generated client certificate (the JDK has no public API for X.509 creation: a small hand-written DER builder or BouncyCastle with a dependency entry), the pairing handshake (code shown on the TV, hash over both public keys), and protobuf messages for key presses. Get the exact `.proto` field numbers from the reference projects before writing code; do not write them from memory.
- **ESPHome native API:** plaintext framing is simple, but most devices use Noise (`Noise_NNpsk0_25519_ChaChaPoly_SHA256`): X25519 and ChaCha20-Poly1305 are in the JDK; check Android's minimum API for both. Entity list and state messages need the reference `api.proto`.
- Both can only be properly verified against real devices; a simulator built from the same reading would share any mistake.
