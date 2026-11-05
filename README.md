# Patchbay

A local-only, privacy-first home control app for Android and Windows. It discovers and controls home devices
**directly over the LAN**: no accounts, no cloud relays, no telemetry. It also shows your home's privacy
exposure: which devices are local, which depend on a vendor cloud, and what unknown things are on your network.

> Status: **Phase 0 (foundation)**. The apps render a simulated demo home; no real devices yet.
> See `docs/PHASE0_REPORT.md`.

- Brief: [`docs/BRIEF.md`](docs/BRIEF.md) · Rules for contributors (human or AI): [`CLAUDE.md`](CLAUDE.md)
- Decisions: [`docs/adr/`](docs/adr/) · Threat model: [`docs/THREAT_MODEL.md`](docs/THREAT_MODEL.md)
- Design: [`docs/design/DESIGN_SPEC.md`](docs/design/DESIGN_SPEC.md) · Dependencies: [`docs/DEPENDENCIES.md`](docs/DEPENDENCIES.md)

## Build

Requirements: JDK 21 (Temurin recommended). The Android SDK is needed for the full build.

Full build (both apps):

```bash
./gradlew check :app-android:assembleDebug
```

Windows installer (on Windows):

```bash
./gradlew :app-windows:packageMsi
```

Core only (no Android SDK or Google Maven needed):

```bash
./gradlew -Ppatchbay.jvmOnly=true :core:jvmTest :simulators:jvmTest
```

Re-render the design screenshots:

```bash
./gradlew :ui:recordScreens
```

Policy checks (NetGuard bypass, trust-all TLS, banned SDKs):

```bash
tools/check-policy.sh
```

CI (GitHub Actions) builds the APKs on `ubuntu-latest` and the MSI on `windows-latest`, runs every test and
policy check, and publishes a CycloneDX SBOM. See [`docs/RELEASE.md`](docs/RELEASE.md) for installing and signing.

## Modules

| Module | What |
|---|---|
| `core` | Domain model, NetGuard, adapters, automation, storage, crypto, sync (KMP: jvm + android) |
| `simulators` | Fake devices and the demo home |
| `ui` | Compose Multiplatform UI and the `designsystem` |
| `app-android` | Android shell |
| `app-windows` | Windows shell + MSI |
| `hub` | Headless always-on peer (Phase 3) |
| `app-wear` | Wear OS tile (Phase 3, not yet in the build) |
