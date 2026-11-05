# 0001 — Kotlin Multiplatform end-to-end, module layout, manual DI

Status: Proposed · Date: 2026-11-05

## Context

Patchbay ships on Android and Windows. Both are JVM-family runtimes, and every device protocol we need
(UDP/TCP sockets, TLS, WebSocket framing, MQTT, protobuf, Noise, SRP) can be written once on the JVM.
Phase 3 adds a headless hub on a mini PC or Pi. The brief asks for KMP with `androidTarget + jvm`,
and no native FFI.

## Decision

1. **Kotlin Multiplatform for all shared code; Compose Multiplatform for UI.**
2. **Modules**
   - `core` — KMP with `jvm()` and `android {}` targets.
     - `commonMain` holds pure Kotlin: the domain model, the `NetGuard` address policy, parsers, the
       automation engine and sync logic. Pure code is easy to test, fuzz and reason about.
     - JVM APIs (sockets, JCA) go into an intermediate source set shared by `jvmMain` and
       `androidMain` (Phase 1). Kotlin calls JVM+Android sharing "best effort", so CI must prove it. The
       fallback is to compile the same directory into both targets.
     - Platform specifics sit behind small interfaces in `core`, implemented in the app shells:
       `KeyWrapper` (Keystore / DPAPI), `SecureDriverFactory` (SQLCipher / sqlite-jdbc-crypt),
       `LocalNetworkAccess` (permission + multicast lock), `LanIdentity` (home detection without location).
   - `simulators` — KMP, same targets as `core`. Fake devices (loopback only) plus the demo home.
   - `ui` — Compose Multiplatform (`android {}` + `jvm("desktop")`). Holds the design system and screens.
     It doesn't depend on `simulators`; the apps inject data.
   - `app-android` — AGP application module. It's a separate module because AGP 9 doesn't allow
     `com.android.application` in a KMP module.
   - `app-windows` — Compose Desktop application, MSI packaging.
   - `hub` — plain Kotlin/JVM application; builds without the Android SDK.
   - `app-wear` — Phase 3; not in the build until then.
3. **JVM-only build mode.** `-Ppatchbay.jvmOnly=true` makes `settings.gradle.kts` include only
   `core`, `simulators` and `hub`, using alternate build files (`build.jvm-only.gradle.kts`) with no
   Android target. The Android Gradle Plugin sits on the root buildscript classpath only in full builds,
   so JVM-only builds never resolve anything from Google Maven. This serves the hub host and restricted
   CI/sandboxes; full CI always builds everything. The two build files must declare the same
   dependencies, and the `jvm` CI job keeps the JVM-only path honest.
4. **Dependency injection: manual constructor injection.** A single `Bootstrap` builds the object graph
   in the order the brief requires: paths → encrypted storage → keystore-wrapped keys → NetGuard → logger →
   adapter registry. The graph is small (around 20 long-lived services), and making the order explicit is
   a requirement, not a convenience. Koin would add a runtime service locator, reflection-free but still
   late-bound, so a missing binding fails at runtime instead of at compile time. That's a dependency
   with no privacy benefit.

## Alternatives considered

- **`core` as a single-`jvm` KMP module or plain JVM library, consumed by Android.** Simpler build, and
  Android can consume JVM jars. But `ui`'s `commonMain` (android + desktop) could only see it through
  the same best-effort JVM/Android sharing, and we'd lose Android API checking of `core`. Rejected in
  favour of the brief's layout.
- **Flutter / React Native / native per platform.** Rejected: the brief mandates KMP. Native per platform
  would also mean writing every protocol twice.
- **Koin.** See above.

## Consequences

- Compose Multiplatform 1.12 pulls `androidx.*` artifacts from Google Maven **even for desktop**, so
  Google Maven is a required build-time repository. Repositories are content-filtered so Google's repo
  can only serve `androidx.*`, `com.android.*` and `com.google.*` groups.
- `core` must avoid JDK-only APIs that Android lacks (for example `java.net.http`). Android Lint
  (`InvalidPackage`, `NewApi` as errors) and R8's missing-class errors catch this in the `android` CI job.
- AGP 9 removed `com.android.library` for KMP. We use `com.android.kotlin.multiplatform.library` and the
  `kotlin { android { … } }` DSL.

## Verification / follow-ups

- [ ] Phase 0 CI: `android` and `screens` jobs compile `core`/`ui` for Android and desktop. These were
      **not compiled in the Phase 0 sandbox**, because Google Maven was unreachable there.
- [ ] Phase 1: add the JVM+Android shared source set with the first socket code, and confirm the IDE and CI accept it.
