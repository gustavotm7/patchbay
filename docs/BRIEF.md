# Build "Patchbay" — a local-only, privacy-first home control app (Android + Windows)

> Working name: **Patchbay** (rename freely). Paste this whole file as the first message of a Claude Code cloud session, and also commit it to the repo as `docs/BRIEF.md`.

---

## 0. Before you write any code

1. Read this entire brief.
2. Create `CLAUDE.md` at the repo root that restates the **Privacy Invariants (section 2)** and **Engineering Conventions (section 8)** in compact form, so every future session inherits them.
3. Produce Phase 0 deliverables (section 10) and **stop for my review** before starting Phase 1.
4. Protocol details below reflect my best knowledge but firmware changes often. For every adapter, verify current behaviour against primary sources (vendor docs, maintained open-source integrations such as Home Assistant's) and record findings + links in `docs/protocols/<device>.md`. If something here is wrong, say so and correct course rather than following it blindly.

### Environment reality (important)
This cloud session **cannot reach my home LAN**. Therefore:
- Every adapter must ship with a **protocol simulator** (fake device) used by tests and by a "demo home" mode in the app.
- Windows builds must be produced by **GitHub Actions on `windows-latest`**; Android APKs on `ubuntu-latest`. Set up CI in Phase 0.
- Every phase ends with `docs/HARDWARE_TEST.md`: a step-by-step checklist I run against real devices, with expected results.

---

## 1. Mission

One app, on my Android phone and my Windows laptop, that discovers and controls my home devices **directly over the local network**. No accounts, no cloud relays, no telemetry, no vendor SDKs that phone home. If a device can only be controlled through a vendor cloud, the app says so plainly instead of quietly sending my data there.

Secondary goal: help me *see* my home's privacy exposure — which devices are local, which depend on a cloud, and which unknown things are on my network.

---

## 2. Privacy Invariants (non-negotiable)

These are hard requirements. Any change that weakens one needs an ADR in `docs/adr/` and my explicit approval.

1. **No outbound internet by default.** All networking goes through a single `NetGuard` module. It allows only: RFC 1918 (10/8, 172.16/12, 192.168/16), link-local (169.254/16, fe80::/10), IPv6 ULA (fc00::/7), loopback, and the multicast groups needed for discovery. Everything else is refused *in code*, with a unit-test suite proving it. A CI check fails the build if any module opens sockets or HTTP clients outside `NetGuard`.
2. **No third-party SDKs that transmit data.** No Firebase, Google Play Services, Crashlytics, Sentry, analytics, ads, remote config, or update checkers. The Android app must run on a de-Googled phone (e.g. GrapheneOS without sandboxed Play). No runtime font/CDN fetches; all assets are bundled.
3. **Cloud-only devices are quarantined.** Vendor-cloud adapters are *not built in v1*. If added later, each lives in a separate opt-in module, disabled by default, with a per-vendor host allowlist in `NetGuard`, a persistent on-screen indicator while active, and a one-tap kill switch.
4. **Secrets at rest are encrypted.** A data-encryption key is wrapped by the OS keystore (Android Keystore, StrongBox when available; Windows DPAPI via JNA). The local database is encrypted (SQLCipher or equivalent — verify a maintained option for both Android and desktop JVM).
5. **Device trust is explicit.** Most devices use self-signed TLS. Use trust-on-first-use with certificate pinning: show the fingerprint at pairing, store it, and raise a clear alert if it ever changes.
6. **App lock.** Biometric/PIN lock on Android, Windows Hello (or PIN fallback) on Windows; configurable auto-lock; pairing codes and access codes are masked and the screens are marked secure (`FLAG_SECURE` on Android).
7. **Logs stay local and redacted.** No secrets, tokens, access codes, or full serials in logs. Export only by explicit user action.
8. **Minimal permissions.** Android: only what's needed for LAN sockets, multicast, notifications, and any local-network permission required by the current target SDK (check current Android requirements for local network access). No location permission unless strictly required by the OS for a specific feature, and justify it if so. `allowBackup=false`. No exported components beyond what widgets/tiles require.
9. **Supply chain hygiene.** Pinned dependency versions, Gradle dependency verification (checksums), license + network-behaviour entry for every dependency in `docs/DEPENDENCIES.md`, CycloneDX SBOM generated in CI, aim for reproducible builds.

---

## 3. Architecture

Use **Kotlin Multiplatform end-to-end** (both targets are JVM-family, so no native FFI is needed):

```
patchbay/
├─ core/            # KMP module (androidTarget + jvm desktop): domain model, NetGuard,
│                   #   discovery, adapters, automation engine, storage, crypto, sync
├─ ui/              # Compose Multiplatform shared UI + design system
├─ app-android/     # Android shell: widgets (Glance), Quick Settings tiles, app lock
├─ app-windows/     # Compose Desktop shell: window chrome, tray, hotkeys, MSI packaging
├─ hub/             # (Phase 3) headless JVM daemon using core/, runnable on a mini PC / Pi
├─ app-wear/        # (Phase 3, optional) Wear OS tile for quick scenes
├─ simulators/      # fake devices for every adapter, used by tests and demo mode
└─ docs/            # BRIEF, ADRs, protocols, threat model, design spec, hardware tests
```

Key decisions:
- **Each client is self-sufficient.** The phone and the laptop each run the full core and talk to devices directly. The laptop is not always on, so nothing may depend on it.
- **Peer sync, LAN only.** Android ↔ Windows pair once via a QR code (ephemeral key exchange, then pinned mutual TLS or a Noise-protocol channel — pick one, justify in an ADR). They sync the device registry, rooms, scenes, automations, and credentials end-to-end encrypted, only when both are on the LAN. Conflict resolution: last-writer-wins per field with a vector clock or HLC; document it.
- **Optional hub (Phase 3).** Same core as a headless service for always-on automations. Clients prefer direct control and treat the hub as a peer, never as a required relay.
- **Adapter contract.** Each adapter implements a common interface: `discover()`, `pair()`, `capabilities()`, `observe(): Flow<State>`, `command(cmd)`, `health()`. Capabilities are typed (power, brightness, colour temperature in K, colour, setpoint in °C, fan level, media transport, volume, input source, print job, etc.) so the UI renders controls from capabilities, not from vendor names.
- **Untrusted input.** Everything a device sends is untrusted. Parsers are strict, bounded, and fuzzed (Jazzer) in CI.

---

## 4. Devices and how to reach them locally

Fill in / correct the **MY DEVICES** block (section 12) with exact models before Phase 1. Tiers: **L** = local, works out of the box; **LS** = local after a one-time setting on the device/app; **C** = cloud-only (not built in v1).

| Device | Tier | Local path (verify all details) | Notes |
|---|---|---|---|
| Govee lights | LS | Govee LAN API: UDP multicast scan to 239.255.255.250:4001, replies on 4002, commands to device IP:4003 (JSON: scan, turn, brightness, colorwc, devStatus). "LAN Control" must be enabled per device in the Govee app. | Not every model supports LAN; detect and report. Newer models may also support Matter (Phase 3). |
| LG TV (webOS) | L | SSAP over WebSocket (wss on 3001 for current firmware; ws 3000 legacy). One-time on-screen pairing returns a client key. Power-on via Wake-on-LAN (TV setting "Turn on via Wi-Fi" must be enabled). | Discover via SSDP. Support power, volume, mute, input, app launch, media keys, toast notification. |
| Chromecast / Google TV | L | Cast v2 over TLS on 8009 (protobuf CastMessage; connection/heartbeat/receiver/media namespaces), discovered via mDNS `_googlecast._tcp`. For Google TV devices, also the Android TV Remote protocol v2 (pairing on 6467 with code shown on TV, control on 6466). | Playback control, volume, now-playing; remote keys for Google TV. |
| Bambu Lab H2D printer | LS | MQTT over TLS on 8883, user `bblp`, password = LAN access code, topics `device/<serial>/report` and `device/<serial>/request` (`pushall` for full state). FTPS (implicit TLS, 990) for file listing. Camera: verify H2D stream (likely RTSPS like X1-series). Requires **LAN Only Mode + Developer Mode** on the printer. | Discovery via the printer's SSDP-style broadcast (verify port). **Safety:** v1 is monitoring + pause/resume/stop + lights only. Heating, motion, and raw G-code are out of scope; if added later they need a two-step confirm. Show HMS error codes. |
| ecobee thermostat | LS | **HomeKit Accessory Protocol over IP** (mDNS `_hap._tcp`; pair-setup SRP-6a with the setup code shown on the thermostat, Ed25519 long-term keys, pair-verify X25519, ChaCha20-Poly1305 framed sessions). Use `aiohomekit` (Python) as the reference implementation and test fixture. ecobee's cloud developer API is closed to new keys — do not build it. | Thermostat must be removed from Apple Home first if paired there. Expose mode, setpoints (°C), current temp, humidity, fan, occupancy sensors if exposed. |
| Kidde smart smoke/CO | C | No local API known. | **Life-safety device. Do not integrate in any phase without an ADR.** Show it in the Exposure Map as "cloud-only, untouched." The app must never present itself as an alarm path. |
| VeSync / Levoit purifier | C | Cloud-only stock. Some Levoit models can be reflashed with ESPHome by the owner. | Ship a generic **ESPHome native API adapter** (port 6053, protobuf, Noise encryption) so a reflashed unit becomes local automatically. |
| LG ThinQ appliances | C | ThinQ is cloud-based. Check per model whether Matter is exposed. | Exposure Map entry; Matter path in Phase 3. |
| Google Home / Nest speakers | C/L | Google Home control is cloud. Cast-capable speakers are reachable via Cast v2 for media only. | Treat as Cast targets. |
| DIY devices | L | Generic adapters: ESPHome native API, and plain MQTT to a **local** broker the user points at. | For microcontroller projects. |
| Unknown devices | — | See Inventory (section 5). | |

---

## 5. Features

**Phase 1 core**
- **Inventory / network census.** Discovers everything it can on the LAN: mDNS/DNS-SD (browse all common service types incl. `_googlecast`, `_hap`, `_esphomelib`, `_matter`, `_matterc`, `_ipp`, `_airplay`), SSDP M-SEARCH, Govee scan, Bambu broadcast, plus an opt-in polite ping/port probe of the local subnet. Vendor guess from a **bundled offline OUI database** (snapshot at build time). Lists unknown devices with what's known about them. This is how I find the devices I've forgotten.
- **Exposure Map.** Per device: control path (local / local-after-setup / cloud-only), what it was observed talking to locally, last seen, firmware if exposed. Exportable as a report. Optionally generates suggested router firewall rules (block internet for devices that work fully locally) as text — never applies them.
- **Device control** rendered from typed capabilities.
- **Rooms** and a simple user-drawn floor plan (rectangles are enough) used as primary navigation.
- **Demo home** mode backed by the simulators.

**Phase 2**
- **Scenes** (one-tap multi-device states) and a **local automation engine**: triggers (time, sunrise/sunset computed offline from a user-entered location, device state, presence of the phone on home Wi-Fi), conditions, actions. Runs on whichever client is awake; hub takes over in Phase 3. Every automation run is logged.
- **State ledger.** A timeline of every state change and command, filterable, stored locally, with CSV export.
- Android: home-screen widgets (Glance), Quick Settings tiles for favourite scenes, notifications for printer done / HMS errors / device offline.
- Windows: system tray, global hotkey, **command bar** (Ctrl+K) with fuzzy search over devices, scenes, and actions.
- Peer sync Android ↔ Windows.

**Phase 3**
- Matter controller spike (evaluate realistic options for commissioning via multi-admin from a JVM/Android app or the hub; write an ADR with a go/no-go).
- Headless hub.
- Wear OS tile for scenes and printer progress.
- Quarantined cloud adapters framework (still off by default; only if I approve per vendor).

---

## 6. Design direction — do NOT make it look like a generic AI-generated app

### Banned (these read as cliché)
Warm cream/beige backgrounds with terracotta/orange accents; serif display headings; soft pastel rounded cards everywhere; purple-to-blue gradients; glassmorphism/blur panels; emoji as icons; a uniform grid of identical device tiles with a toggle on each; stock Material 3 look with default colour roles; bouncy, decorative animations; "Good evening 👋" greetings.

### Concept: "Control Room"
Borrow from **industrial high-performance HMI design (ISA-101 principles)** — the discipline used for plant control screens — and make it beautiful for a home:
- **Neutral baseline.** Normal state is calm greys with high legibility. **Colour is reserved for meaning**: attention, abnormal, active output, or a device's actual emitted colour (a Govee light shows its real colour; nothing else is decorative-coloured). Colour is never the only signal — pair it with shape/position/text.
- **Values are first-class.** Numbers in tabular figures, units always shown (°C, %, K, metric throughout). Small inline trend sparklines next to values (temperature, humidity, printer temps).
- **Controls mirror the physics.** Thermostat: a vertical setpoint scale with current temperature marker and the heat/cool deadband shaded, dragged directly. Lights: a 2D pad (brightness × colour temperature), colour wheel only when the device supports RGB. Purifier: fan level as discrete detents. Printer: layer-stack progress visual + nozzle/bed/chamber temps + AMS slots as filament swatches. TV/Cast: transport bar + volume as a physical-feeling fader.
- **Spatial navigation.** The floor plan is home base; devices sit in their rooms; tapping a room zooms into its panel.
- **Density with hierarchy.** Information-dense like an instrument panel, but with a strict typographic hierarchy so it never feels cluttered. Large touch targets on Android, keyboard-first on Windows.
- **Motion** is fast (≤200 ms), physics-based, and only communicates state change or spatial transition.
- **Typography.** One bundled OFL typeface family with true tabular figures and a matching mono for codes/IDs. Do not use Inter or the platform default. Propose 2–3 candidates with rationale.
- **Themes.** Dark-first with a proper light theme (not an inversion). Optional "night" mode: very low luminance, warm-shifted, for use in a dark bedroom.
- **Accessibility.** WCAG 2.2 AA contrast, full TalkBack/Narrator labels, scalable text, reduced-motion support.
- **Platform respect.** Shared design system, but Windows gets a proper desktop layout (multi-pane, custom title bar, keyboard focus states) and Android gets a proper mobile layout — not a stretched phone UI on desktop.

Deliver the design as code: a `ui/designsystem` module (tokens: colour roles, type scale, spacing, shape, motion) plus **screenshot tests (Roborazzi or equivalent, JVM)** that render key screens in light/dark/night at phone and desktop sizes, committed as PNGs in `docs/design/screens/` so I can review visuals without hardware.

---

## 7. Security engineering

- `docs/THREAT_MODEL.md` (Phase 0): assets (device credentials, pairing keys, home layout, presence data), adversaries (malicious device on LAN, compromised dependency, lost/stolen phone, curious vendor cloud), and mitigations mapped to sections 2–3.
- Constant-time comparisons for secrets; zeroise key material where the platform allows.
- Rate-limit and back off on device connections; never hammer a device.
- No `TrustAllCerts`, no hostname-verification bypass outside the pinned-TOFU path, and a CI grep that fails if such patterns appear.
- Windows installer: MSI via Compose Desktop native distribution, per-user install, **no auto-updater**. Document an optional Windows Firewall rule restricting the app to the Private profile + local subnet.
- Android: release builds with R8, no debug flags, network security config denying cleartext except to local ranges where a device protocol requires it (document each exception).

---

## 8. Engineering conventions

- **Structured design with early initialisation.** A single `Bootstrap` runs at process start and initialises, in order: paths, encrypted storage, keystore-wrapped keys, `NetGuard`, logger, adapter registry. Resources are created once and reused; nothing lazily creates files mid-run.
- **Session log file** created at startup with the format `patchbay_<platform>_<YYYYMMDD-HHMMSS>.log` and used for the whole session (rotated by size, retention configurable). The state-ledger CSV export uses `patchbay_ledger_<YYYYMMDD-HHMMSS>.csv`.
- Units: SI/metric everywhere in the UI and model (°C, %, K, mm, m). Convert at the adapter boundary if a device speaks °F.
- Kotlin: coroutines + Flow, explicit error types (no swallowed exceptions), `kotlinx.serialization`, dependency injection kept simple (manual or Koin — justify).
- Tests: unit tests for core logic, contract tests for every adapter against its simulator, fuzz tests for every parser, screenshot tests for UI. CI must be green before a phase is "done".
- Small, focused commits with conventional-commit messages. ADRs for every non-obvious decision.
- No new dependency without a `DEPENDENCIES.md` entry (purpose, licence, does it make network calls?).

---

## 9. Out of scope (unless I approve an ADR)

Any vendor cloud integration; voice assistants; remote access from outside the home (a future option could be my own WireGuard VPN — never a relay service); controlling the Kidde smoke/CO alarm; raw G-code / heater / motion control of the printer.

---

## 10. Phases and deliverables

**Phase 0 — foundation (then stop for review)**
`CLAUDE.md`, repo scaffold for all modules, Gradle setup with dependency verification, CI (Android APK + Windows MSI artifacts, tests, SBOM, NetGuard/trust-all checks), `THREAT_MODEL.md`, ADRs for: KMP-only architecture, peer-sync channel, encrypted DB choice, typeface choice. `docs/design/DESIGN_SPEC.md` + first screenshot-test renders of the design system and 3 key screens (Home floor plan, Thermostat panel, Printer panel) using simulator data.

**Phase 1** — NetGuard, Bootstrap, storage, Inventory + Exposure Map, adapters + simulators for Govee, LG webOS TV, Cast/Google TV, Bambu H2D (monitor-only scope), ESPHome; rooms + floor plan; app lock; Android + Windows apps usable end-to-end in demo mode. `HARDWARE_TEST.md`.

**Phase 2** — ecobee via HAP, scenes, automation engine, state ledger + CSV export, widgets/tiles, Windows tray + command bar, peer sync. `HARDWARE_TEST.md`.

**Phase 3** — Matter spike + ADR, hub, Wear OS tile, quarantine framework.

At the end of each phase, give me: what was built, what's verified only against simulators, known gaps, and what you need from me (model numbers, captures, settings to toggle).

---

## 11. How to work with me

- Ask clarifying questions in a batch at phase boundaries, not mid-task, unless blocked.
- If a requirement here conflicts with reality (a protocol changed, a library is unmaintained, a design rule hurts usability), tell me directly and propose an alternative.
- Prefer boring, well-maintained libraries over clever ones. Prefer fewer dependencies.

---

## 12. MY DEVICES (I'll complete before Phase 1)

```
Govee:        [models]            LAN Control enabled? [y/n]
LG TV:        [model / year]      webOS version: [ ]
Chromecast:   [Chromecast gen / Chromecast with Google TV / Google TV Streamer]
Bambu Lab:    H2D                 Firmware: [ ]   OK to lose Bambu Handy/cloud for Developer Mode? [y/n]
ecobee:       [model]             Currently in Apple Home? [y/n]
Kidde:        [model]             (cloud-only, display only)
VeSync:       [Levoit model]      Willing to reflash with ESPHome? [y/n]
LG ThinQ:     [appliances + models]
Google/Nest speakers/displays: [ ]
Other / unknown: let the Inventory find them
Router:       [brand/model]       Can create VLANs/firewall rules? [y/n]
Phone:        Android [version], Google Play services present? [y/n]
Windows:      [version]
```
