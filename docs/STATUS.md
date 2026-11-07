# Status — where things stand

> Continuing in a new session? Start with `docs/HANDOFF.md`: it has a copy-paste prompt and the next steps.

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
| **Encrypted storage** | SQLDelight; desktop: sqlite-jdbc-crypt (SQLCipher 4) + DPAPI; Android: SQLCipher + Keystore (StrongBox if present). Discovered devices persist and are polled at startup without a scan. If storage can't open, the session runs in memory: never plaintext, never a crash | Desktop: storage, persistence and fallback tests. Android: CI build only |
| **Network census** | mDNS/DNS-SD (legacy-unicast queries, no port 5353 binding) + SSDP M-SEARCH on every scan; "Everything else that answered" list with local-control hints | Strict DNS/SSDP codecs (30 000-input mutation test) + loopback census test |
| **Exposure report** | Local / cloud-only / unidentified counts; "Copy report" exports Markdown with suggested OpenWrt firewall rules (text only, never applied) | `ExposureReportTest` |
| **Device TLS pinning** | TOFU with SPKI SHA-256 pins (ADR 0005), ready for webOS/Cast/Bambu adapters | Loopback TLS tests: first use, match, changed key → TrustChanged |
| **LG webOS TV** | Pinned WSS on 3001 only; pairing = fingerprint in Patchbay + accept on the TV; volume, mute, inputs, media keys, power off, power on (Wake-on-LAN) | 7 SSAP codec tests + 6 contract tests vs a simulated TV (incl. impostor → TrustChanged). CI green at `a6c3f3c`. **Not yet tried on your TV** (HARDWARE_TEST T1.x) |
| **Bambu H2D** | Pinned MQTT/TLS 8883 + access code; dual-nozzle/bed/chamber temps, job progress, AMS swatches, HMS; pause/resume/stop + chamber lights only | 9 codec tests + 5 contract tests vs a simulated H2D (incl. wrong code, impostor). **Not yet tried on your printer** (HARDWARE_TEST B1.x) |
| **App lock** | Windows: PIN (PBKDF2 in encrypted DB), locks at start and after unfocus. Android: BiometricPrompt (biometric or device PIN), auto-lock after 1 min in background | AppLock/PinHash tests |
| **TP-Link Kasa** | Legacy local protocol (TCP/UDP 9999): discover, power on/off, state. KLAP-only firmware isn't found | 10 codec tests (incl. 25 000-input mutation) + 7 contract tests vs a simulated plug. **Not yet tried on your plugs** (HARDWARE_TEST K1.x) |
| **eufy cameras (RTSP)** | Add by address, RTSP login pairing, Digest/Basic status check, view-only. No in-app video yet | 8 codec tests + RFC 2617 digest vector + 12 contract tests vs a simulated camera. **Not yet tried on your cameras** (HARDWARE_TEST E1.x) |
| **Camera playback (Android)** | Watch button plays the RTSP stream in the camera panel through Media3, TCP only, over a NetGuard-backed SocketFactory (ADR 0008). Windows: no player yet | 3 `GuardedSocketFactory` tests (public IPs, hostnames, unresolved addresses refused; LAN counted) + 3 stream-lease tests. APK builds in CI, no new permissions. **Not yet tried on a real camera** (E1.7) |
| **Tuya bulbs (Lepro and others)** | Local protocol 3.3, add by address, user-supplied local key (never sent anywhere), allowlisted data points: power, brightness, colour temperature, colour. 3.4/3.5 and the vacuum are not built | 12 codec tests (independent frame vector, 25 000-input mutation) + 12 contract tests vs a simulated bulb. **Not yet tried on your bulbs** (HARDWARE_TEST U1.x) |
| **Visual redesign (v2)** | Dark-first modern look: rounded cards, pill controls, inverse emphasis, tile home (tap toggles lights and plugs), phone bottom tabs (Devices, Network, Demo), restyled desktop rail, edge-to-edge insets on Android. ADR 0009 | 29 screenshot goldens (light, dark, night; phone and desktop; live tiles) compared in CI; contrast tests on every palette. **Not yet seen on a real phone** |
| **Settings and navigation** | Desktop sections: Devices, Network, Demo home, Settings. Phone tabs: Devices, Network, Demo, Settings. Settings: appearance (System, Dark, Light, Night, saved), lock now, where data lives (portable or installed), about | Screenshot goldens (32). **Portable Windows zip** keeps data beside the exe |
| **Rooms and scenes** | Tiles group by room (a room exists while it has devices); a Room picker on each device page; scenes saved from the current state of lights and plugs, run in one tap. Scenes can never hold a TV, printer, camera or lock command | 3 simulator tests incl. restart persistence; goldens. **Not yet tried on your devices** (HARDWARE_TEST S1.x) |
| **Cast / Google TV** | Cast v2 over pinned TLS 8009; found via the mDNS census; volume, mute, play, pause, stop, current app and now-playing. No casting, no app launching, no Google TV remote keys yet | 8 codec tests (hand-built protobuf vector, 25 000-input mutation) + 9 contract tests vs a simulated Chromecast (pinning, impostor, bad frames, back-off). **Not yet tried on a real device** (HARDWARE_TEST C1.x) |
| **Attention, removal, activity** | Changed security keys now reach the UI as a loud alert and stop all contact (they were silently shown as "No reply" before). Needs-attention card, Offline notice instead of dead controls, Remove device, All off, Activity history (bounded, encrypted, Copy as CSV) | Simulator tests: impostor key through LanHome, remove and re-pair, restart persistence, CSV escaping. **Not yet tried on your devices** (HARDWARE_TEST N1.x) |
| **Tray mode and automations** | Windows: keep running in the tray when the window is closed (locks when hidden; menu: Open, All off, Lock now, Quit). Automations run a scene at a time on chosen days while Patchbay is open or in the tray; no catch-up for missed times | Clock-driven simulator tests (exact minute, once only, weekdays, disabled, deleted scene, restart). **Not yet tried on Windows** (HARDWARE_TEST R1.x). Android doesn't run in the background yet |
| **Adaptive lighting and away mode** | Adaptive: white colour follows a day curve for lights you choose; stops when you change a light, resumes after off and on. Away: all off, optional evening presence imitation (18:00 to 23:00, off at 23:00). Both run only while Patchbay is open or in the tray | Curve tests; clock-driven simulator tests (override, off-and-on, colour mode untouched, imitation schedule). **Not yet tried on real lights** (HARDWARE_TEST D1.x) |
| **Names, favourites, sun automations, notifications** | Rename a device; Favourites row; automations at sunrise or sunset ± up to 3 h from a home location you type (NOAA maths, checked against published times); Windows tray notifications for a changed key or a device going offline (once per device per hour) | Simulator tests incl. exact sunset minute; sun-time reference tests. **Not yet tried on real devices** (HARDWARE_TEST F1.x) |
| Add by address | Network screen panel: Bambu printer (IP + serial), RTSP camera (IP + name) | Bambu contract tests; UI compiles in CI |
| Pairing UI | Inline panel: fingerprint → Trust/Cancel, on-device prompt, masked code entry; "Pair" on devices that need it | Compiles in CI |
| Adapter registry | Adding a system = factory + adapter + simulator + contract tests (`docs/ADDING_AN_ADAPTER.md`) | Used by Bootstrap |

## Get the builds

GitHub → Actions → **ci** → the latest green run on `ci/verification-bootstrap` → Artifacts:

- `patchbay-android` → `app-android-debug.apk`. Install directly, or sign the release APK (`docs/RELEASE.md`).
- `patchbay-windows-msi` → `Patchbay-0.1.0.msi`

Then run `docs/HARDWARE_TEST.md`. Phase 0 checks, then **Phase 1 — Govee** (G1.1–G1.8).

## Waiting on you: Lefant vacuum

ADR 0007 is accepted and the Tuya **bulb** adapter exists (Lepro). The **vacuum** needs its model number and its data-point list (tinytuya wizard)
before any command is allowed; see `docs/protocols/tuya.md` and `docs/TUYA_KEYS.md`.

## Not done yet (Phase 1 remainder, in the order I'd do it)

1. Adapters, each with a simulator and contract tests like Govee:
   - ~~LG webOS~~ done (including power-on after restart)
   - ~~eufy RTSP cameras~~ done for status; in-app video playback (Media3 on Android, TBD on desktop) is next
   - Cast v2 / Android TV Remote v2
   - ~~Bambu H2D~~ done, including manual add; follow-up: camera (RTSPS)
   - ESPHome (Noise NNpsk0)
2. Windows Hello (app lock is done on both platforms: Android biometric, Windows PIN).
3. Rooms and a floor-plan editor for live devices (the schema has `room_id`; the assignment UI is missing).
4. Census extras: OUI vendor lookup (desktop ARP table only; Android hides MACs), and an opt-in polite
   port probe.
5. Jazzer fuzzing in CI. Parsers currently run seeded mutation tests on every build instead.

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

Decided 2026-11-05:
- `applicationId` stays `dev.patchbay`.
- Windows uses the **native title bar**.
- `FLAG_SECURE` applies to the **whole app**, for now.

Deferred by you, to review later: ADRs 0001–0006 (still "Proposed") and the repo licence.

Still open: the MY DEVICES block (brief §12), the eufy camera and lock models, and whether you're OK
enabling RTSP.

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
