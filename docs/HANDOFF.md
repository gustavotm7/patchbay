# Handoff — continue Patchbay in a new Claude session

Written 2026-11-05 at the end of the first long session. Read this first, then `docs/STATUS.md`.

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
  `441b015` (Windows PIN lock) was building when this was written. **Check it first.** If it's red, the
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

## Next steps (in order)

1. **Android app lock.**
   1. Add `androidx.biometric:biometric` (stable) to `gradle/libs.versions.toml` and `app-android`.
   2. Make `MainActivity` a `FragmentActivity`.
   3. Implement `LockController` with `BiometricPrompt` (`BIOMETRIC_STRONG or DEVICE_CREDENTIAL`). Keep an
      `AppLock` in `PatchbayApplication`; call `onBackground`/`onForeground` from the activity's `onStop`/`onStart`.
   4. Wrap `PhoneApp` in `LockGate`.

   **New dependency → refresh checksums:** push to the branch; the `verification-bootstrap` workflow prints
   the new metadata (gzip+base64 between `PB-METADATA-BEGIN/END`) in its job log. Decode it, diff it, and
   commit `gradle/verification-metadata.xml`. Also add a `docs/DEPENDENCIES.md` row.
2. **Remember the TV's MAC** across restarts, so Wake-on-LAN works right after launch. Options: put it in
   `Pairing.secret` as JSON, or add an adapter hook for extra persisted state.
3. **Bambu manual add** (IP + serial), for printers that don't answer SSDP. Also add the camera (RTSPS 322)
   later.
4. **eufy RTSP cameras** (see `docs/protocols/eufy.md`). Viewing needs a player: Media3 on Android; desktop TBD.
5. **Cast / Google TV.** Research first whether Chromecast TLS certificates rotate; if they do, TOFU pinning
   needs a different rule (ADR 0005 amendment).
6. Rooms for live devices (the schema has `room_id`), the floor-plan editor, Jazzer fuzzing in CI, then
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
  can decode and look at them. They aren't committed as goldens yet. Record them with
  `./gradlew :ui:recordScreens` (in CI) when you're happy with the design.
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
