# Status — where things stand

Last updated: 2026-11-05, autonomous session. Work branch: **`ci/verification-bootstrap`** (ahead of `main`).
`main` holds the Phase 0 baseline. Open a PR from the branch when you're ready to review. I didn't open one.

## Works today

| Area | State | Verified how |
|---|---|---|
| CI | **All 7 jobs green:** policy, JVM tests, screenshots, Android APK (R8 + lint + permission allowlist), Windows MSI, SBOM | GitHub Actions on `ci/verification-bootstrap` |
| Dependency verification | Strict; covers Android/Compose/AGP 9.4.1 (bootstrapped by CI, reviewed) | Every build |
| Demo home UI | Floor plan, thermostat, printer, design system, in dark/light/night, phone and desktop | Screenshot renders (`docs/design/screens/`) |
| **NetGuard** | LAN-only TCP/UDP/multicast; no DNS, no proxies; non-LAN inbound dropped | 18 unit tests (policy + sockets) |
| **Session log** | `patchbay_<platform>_<YYYYMMDD-HHMMSS>.log`, redacted, size-rotated, retention | 8 tests |
| **Govee LAN** | Discover, power, brightness, colour temperature, RGB; strict parser; anti-spoofing; pacing and back-off | 10 codec tests + 8 contract tests vs simulator + 2 LanHome tests |
| **Live control in the apps** | Phone: "This network" tab. Desktop: Inventory section. Scan → list → light panel (2D brightness × K pad, hue strip) | Compiles in CI. **Not yet tried on your real lights** |
| Bootstrap | paths → wrapped key → encrypted DB → NetGuard → logger → adapters | `BootstrapTest` |
| **Encrypted storage** | SQLDelight; desktop: sqlite-jdbc-crypt (SQLCipher 4) + DPAPI; Android: SQLCipher + Keystore (StrongBox if present). Discovered devices persist and are polled at startup without a scan | Desktop: 3 storage tests + persistence test. Android: CI compile only |

## Get the builds

GitHub → Actions → **ci** → the latest green run on `ci/verification-bootstrap` → Artifacts:

- `patchbay-android` → `app-android-debug.apk`. Install directly, or sign the release APK (`docs/RELEASE.md`).
- `patchbay-windows-msi` → `Patchbay-0.1.0.msi`

Then run `docs/HARDWARE_TEST.md`. Phase 0 checks, then **Phase 1 — Govee** (G1.1–G1.8).

## Not done yet (Phase 1 remainder, in the order I'd do it)

1. ~~Encrypted storage~~ done. Next: rooms for live devices (the schema has `room_id`; the UI to assign is missing).
2. App lock (BiometricPrompt / Windows Hello or PIN).
3. Inventory census: mDNS/SSDP browsing, OUI vendor lookup, polite port probe (opt-in). Then the Exposure Map.
4. Adapters: LG webOS (SSAP/WSS + TOFU pin), Cast v2 / Android TV Remote v2, Bambu H2D (MQTT/TLS,
   monitor-only), ESPHome (Noise NNpsk0). Each with a simulator and contract tests, like Govee.
5. Rooms and floor-plan editor for live devices.
6. Jazzer fuzzing in CI. Parsers currently run a seeded 20 000-input mutation test on every build instead.

## Adding systems (eufy and later ones)

- `docs/ADDING_AN_ADAPTER.md` is the step-by-step path every new system follows.
- `docs/protocols/eufy.md`:
  - **eufy cameras:** local via RTSP once you enable it per camera. An RTSP adapter is planned.
  - **HomeBase P2P:** needs a cloud login, so not in v1.
  - **eufy lock:** cloud-only unless your model has Matter, and **read-only** until an ADR approves
    lock/unlock.
- The model already has `Capability.CameraStream` and a read-only `Capability.LockStatus`. The demo home
  shows a eufy camera and lock.

## Decisions still waiting on you (from `docs/PHASE0_REPORT.md` §5)

ADR approvals (0001–0006), FLAG_SECURE scope, Windows title bar, repo licence, `applicationId`, and the
MY DEVICES block (brief §12).

## Notes for the next session

- This sandbox can't reach Google Maven or `downloads.gradle.org`. JVM-only work builds locally with
  Gradle 9.8.2, downloaded from `github.com/gradle/gradle-distributions` and checked against the official
  sha256:

  ```bash
  gradle -Ppatchbay.jvmOnly=true :core:jvmTest :simulators:jvmTest
  ```

  Everything else is verified by CI.
- Pushing to a `ci/verification-bootstrap*` branch makes the bootstrap workflow:
  - print fresh verification metadata, gzip+base64 between `PB-METADATA-BEGIN/END`;
  - print the rendered screenshots, a tarball between `PB-SCREENS-BEGIN/END`.

  Both can be read from public job logs without a token.
- Gotcha: a test written as `fun x() = runBlocking { … assertIs(…) }` returns a value, and JUnit
  **silently skips it**. Always write `fun x(): Unit = runBlocking { … }`.
