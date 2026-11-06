# CLAUDE.md — Patchbay

Local-only, privacy-first home control app (Android + Windows), Kotlin Multiplatform.
Full brief: `docs/BRIEF.md`. Decisions: `docs/adr/`. Threat model: `docs/THREAT_MODEL.md`.

## Privacy invariants (non-negotiable — weakening any of these needs an ADR + owner approval)

1. **No outbound internet.** All sockets/HTTP go through `NetGuard` (`core/.../net/`). Allowed:
   10/8, 172.16/12, 192.168/16, 169.254/16, fe80::/10, fc00::/7, loopback, and the discovery
   multicast groups. Everything else is refused in code and proven by unit tests.
   `tools/check-policy.sh` (run in CI) fails if any code outside `NetGuard` opens a socket or
   HTTP client.
2. **No data-transmitting third-party SDKs.** No Firebase, Play Services, Crashlytics, Sentry,
   analytics, ads, remote config or update checkers. Must run on de-Googled Android (GrapheneOS
   without Play). All assets (fonts, OUI db) are bundled — no runtime CDN/font fetches.
3. **Cloud-only devices are quarantined.** No vendor-cloud adapters in v1. Any later one is a
   separate opt-in module: off by default, per-vendor host allowlist, persistent on-screen
   indicator, one-tap kill switch.
4. **Secrets at rest are encrypted.** A data-encryption key wrapped by the OS keystore (Android Keystore,
   StrongBox when available; Windows DPAPI via JNA). Encrypted DB (see ADR 0003).
5. **Explicit device trust.** TOFU + certificate/key pinning. Show the fingerprint at pairing, store it,
   and alert loudly if it changes. Never use trust-all managers or bypass hostname verification
   (CI grep enforces this).
6. **App lock.** Biometric/PIN on Android, Windows Hello/PIN on Windows. Configurable auto-lock.
   Codes are masked; Android secret screens use `FLAG_SECURE`.
7. **Logs are local and redacted.** No secrets, tokens, access codes or full serials. Export only on
   explicit user action.
8. **Minimal permissions.** Android: INTERNET, ACCESS_NETWORK_STATE, ACCESS_LOCAL_NETWORK
   (targetSdk 37+), CHANGE_WIFI_MULTICAST_STATE, USE_BIOMETRIC (app lock), POST_NOTIFICATIONS (Phase 2). **No location** — home
   presence is detected without reading the SSID. `allowBackup=false`. Only export what widgets/tiles need.
9. **Supply chain.** Pinned versions in `gradle/libs.versions.toml`. Gradle dependency verification
   (`gradle/verification-metadata.xml`). CI actions pinned by commit SHA. Every dependency has an
   entry in `docs/DEPENDENCIES.md` (purpose, licence, network behaviour). CycloneDX SBOM in CI.

Out of scope without an ADR: vendor clouds, voice assistants, remote access (except a future
user-owned WireGuard), **anything that controls the Kidde smoke/CO alarm**, printer heating/motion/raw G-code.

## Engineering conventions

- **Bootstrap.** A single `Bootstrap` runs at process start and initialises, in this order: paths, encrypted storage,
  keystore-wrapped keys, NetGuard, logger, adapter registry. Create resources once and reuse them.
  Nothing lazily creates files mid-run.
- **Log and export file names.** Session log `patchbay_<platform>_<YYYYMMDD-HHMMSS>.log`, created at startup
  and used for the whole session (size-rotated, configurable retention). Ledger export
  `patchbay_ledger_<YYYYMMDD-HHMMSS>.csv`.
- **Units.** SI/metric everywhere (°C, %, K, mm, m). Convert at the adapter boundary (for example, °F → °C).
- **Kotlin style.** Coroutines + Flow. Explicit error types (`PatchbayResult`/sealed errors) — never swallow
  exceptions. `kotlinx.serialization`. Manual constructor DI (ADR 0001) — no DI framework.
- **Untrusted input.** Treat everything a device sends as hostile. Parsers are strict, size-bounded and
  fuzzed (Jazzer).
- **Tests.** Unit tests for core logic. Contract tests for every adapter against its simulator. A fuzz test
  for every parser. Screenshot tests for UI (`ui` desktopTest → `docs/design/screens/`).
  A phase is done only when CI is green.
- **Coroutine tests.** Write `fun name(): Unit = runBlocking { … }`. A test body that ends in an expression
  returns a value, and JUnit silently skips non-void test methods.
- **Commits.** Small, focused, conventional-commit messages. Write an ADR for every non-obvious decision.
- **No new dependency without a `docs/DEPENDENCIES.md` entry** (purpose, licence, makes network calls?)
  and a verification-metadata update (`./gradlew --write-verification-metadata sha256 help`).
- **Design.** Dark-first modern look ("Control Room" values, visual direction v2: ADR 0009), see `docs/design/DESIGN_SPEC.md`.
  Colour carries meaning only; never decorative. No gradients, glass or emoji. Use only design-system tokens —
  no hard-coded colours or sizes in screens.

## Layout and build

- `core/` KMP (single `jvm` target shared by Android and desktop; platform specifics behind interfaces)
- `simulators/` fake devices + demo home
- `ui/` Compose Multiplatform (android + desktop)
- `app-android/`, `app-windows/` platform shells
- `hub/` headless daemon (Phase 3)
- `docs/` brief, ADRs, protocols, threat model, design spec, hardware tests

Build commands:
- `./gradlew check` — full build; needs the Android SDK and Google Maven.
- `./gradlew -Ppatchbay.jvmOnly=true :core:jvmTest :simulators:test` — core only; no Android SDK
  or Google Maven needed.
- `./gradlew :ui:recordScreens` — re-render the design screenshots into `docs/design/screens/`.
- `tools/check-policy.sh` — the NetGuard / trust-all / banned-SDK policy checks.

## Environment notes

Current status is in `docs/STATUS.md`; how to continue (prompt, next steps, sandbox tips) is in `docs/HANDOFF.md`. Keep it updated at the end of every session.

Cloud sessions cannot reach the home LAN. Every adapter ships a simulator.
After each phase, update `docs/HARDWARE_TEST.md` with checks for the user to run on real devices.
