# Phase 0 report — foundation

Date: 2026-11-05 · Status: **stopped for your review** (brief §0.3)

## 1. What was built

| Deliverable (brief §10) | Where | State |
|---|---|---|
| `CLAUDE.md` (invariants + conventions) | `/CLAUDE.md` | Done |
| Repo scaffold for all modules | `core`, `simulators`, `ui`, `app-android`, `app-windows`, `hub`; `app-wear` README only (Phase 3) | Done |
| Gradle setup with dependency verification | Version catalog, content-filtered repos, wrapper sha256 pinned, `gradle/verification-metadata.xml`, reproducible archives, JVM-only mode | Done; **metadata partial** (§3) |
| CI: APK + MSI artifacts, tests, SBOM, NetGuard/trust-all checks | `.github/workflows/ci.yml` (7 jobs, SHA-pinned actions), `verification-bootstrap.yml`, `tools/check-policy.sh` with self-test | Written; **never run** (no repo) |
| `THREAT_MODEL.md` | `docs/` | Done (draft) |
| ADRs: KMP architecture, peer-sync channel, encrypted DB, typeface | `docs/adr/0001`–`0004`, plus `0005` TOFU pinning and `0006` screenshot testing | Proposed, awaiting your approval |
| `DESIGN_SPEC.md` | `docs/design/` | Done |
| Screenshot renders: design system + Home floor plan, Thermostat, Printer × light/dark/night × phone/desktop | `ui/.../ScreenshotTest.kt` (22 PNGs) | Test written; **PNGs not rendered** (§3) |

Extra, because later phases depend on it:

- **Typed domain model.** Capabilities, states and commands with SI value types that validate on
  construction. The command set has no printer heating/motion/G-code commands at all.
- **`CommandValidator`.** Range, deadband and capability checks. It refuses cloud-only and life-safety
  devices.
- **NetGuard `AddressPolicy` + strict `IpLiteral` parser.**
  - Allowed: RFC 1918, link-local, ULA, loopback, the four discovery multicast groups, and limited
    broadcast.
  - Refused: CGNAT, NAT64, 6to4, Teredo, IPv4-compatible addresses, and other multicast scopes.
  - IPv4-mapped IPv6 is judged by the embedded IPv4 address.
  - An exhaustive first-octet sweep test.
- **Deterministic demo home** (`simulators/DemoHome.kt`) used by screenshots and both apps.
- Supporting docs: `DEPENDENCIES.md`, `SUPPLY_CHAIN.md`, `RELEASE.md` (signing your APK, optional
  firewall rules), `protocols/README.md` (verification status + corrections), `HARDWARE_TEST.md` (Phase 0
  checklist).

## 2. What's verified, and how

| Verified here | How |
|---|---|
| `core` + `simulators` compile with `explicitApi` and warnings-as-errors | Local Gradle 8.14.3 / JDK 21, `-Ppatchbay.jvmOnly=true` |
| **33 unit tests pass** (address policy, IP parser, validator, units, geometry, demo-home invariants) | `:core:jvmTest :simulators:jvmTest` |
| Strict dependency verification for the JVM-only graph | Clean rebuild with `verification-metadata.xml` present |
| SBOM generation (13 runtime components for the JVM graph) | `cyclonedxBom` |
| Policy checks: all 7 rules fire on planted violations; allowlists and comment handling hold; repo is clean | `tools/check-policy.sh --self-test` and `tools/check-policy.sh` |
| WCAG AA for every text role / 3:1 for outlines in all 3 themes | Computed with a script on the token values; the same check is in `ContrastTest` (CI) |
| Font features (tabular digits, slashed zero) | fontTools inspection of the bundled TTFs |
| WiX 3.11.2 SHA-256 | Downloaded and hashed |

**Not verified (written, never compiled or run):**

- `ui`, `app-android`, `app-windows`, and the Android target of `core` and `simulators`.
- All CI workflows.
- The AGP 9 DSL details (`kotlin { android {} }`, `res.srcDir`, lint DSL).

Expect a short round of compile fixes on the first CI run. I read every UI file carefully against the APIs,
and fixed two real bugs found that way: the drag gesture restarting on every step, and Compose types not
exported to the apps. But no compiler has seen this code.

## 3. Known gaps and why

1. **No GitHub repository in this session.** Everything is committed locally on `main` (small conventional
   commits) but not pushed, so CI has never run.
2. **Google Maven and Android SDK hosts were blocked by this environment's network policy**
   (`dl.google.com`, `maven.google.com`). Compose Multiplatform 1.12 needs `androidx.*` from Google Maven
   even for desktop. So I couldn't compile any UI, render the screenshots, or build Android.
   - Consequence: `verification-metadata.xml` lacks the Android/Compose/AGP checksums. The `android`,
     `screens` and `windows` jobs will fail verification until you run the **verification-bootstrap**
     workflow once and commit its output (`docs/SUPPLY_CHAIN.md`).
3. `downloads.gradle.org` was also blocked. The wrapper is pinned to Gradle 9.8.2 with its official
   checksum, but I built locally with the preinstalled 8.14.3. AGP 9 needs Gradle 9, which CI will use.
4. **AGP version 9.4.1 is from search results, not resolved** (Google Maven unreachable). If it doesn't
   exist, change `agp` in `libs.versions.toml`.
5. **Native window decorations** on Windows for now. The custom title bar decision is in DESIGN_SPEC §7.
6. Not started (they're Phase 1 by the brief): NetGuard sockets, Bootstrap, storage, logging, app lock,
   Inventory/Exposure UI, adapters, OUI snapshot.

## 4. Corrections to the brief (details in `docs/protocols/README.md`)

- **Android 17 (targetSdk 37) requires `ACCESS_LOCAL_NETWORK`**, a runtime permission in the Nearby devices
  group. It's declared; requesting it comes with real discovery in Phase 1.
- **"Phone on home Wi-Fi" would need a location permission** (Android hides the SSID otherwise). The plan
  is to detect home from LAN identity instead (gateway + a pinned device reachable), with no location
  permission.
- **The network security config can't express IP ranges and doesn't govern raw sockets.** Cleartext is
  denied globally there; LAN-only enforcement is NetGuard's job.
- **The Bambu H2D access code changes when LAN Only / Developer Mode is toggled.** Developer Mode is
  officially unsupported by Bambu. The camera is RTSPS on 322 (to verify on your firmware).
- **Compose Desktop depends on Google Maven**, and the Compose plugin downloads WiX without a checksum.
  CI now verifies WiX itself.
- **Windows Hello has no JVM API.** It needs a WinRT bridge; a PIN fallback ships first.
- I used Compose's `ImageComposeScene` instead of Roborazzi, for fewer dependencies (ADR 0006).

## 5. What I need from you

**To unblock (environment):**

1. Create an empty GitHub repo (e.g. `patchbay`), connect GitHub to Claude
   (https://claude.ai/connect-github), and tell me its name. I'll push, watch CI, fix compile errors, run
   the verification bootstrap, and commit the 22 screenshots.
2. In this environment's settings → Network access, allow **`dl.google.com`**, **`maven.google.com`** and
   **`downloads.gradle.org`**. Then I can compile and render locally too. This may need a new session.

**Decisions (please answer in a batch):**

3. Approve or amend ADRs 0001–0006. The notable ones:
   - 0002: Noise over mTLS, plus a 6-digit confirmation code at pairing.
   - 0004: IBM Plex.
4. Should `FLAG_SECURE` apply to the whole Android app (current) or only to secret screens?
5. Windows title bar: native decorations (recommended), or a custom title bar via the JetBrains Runtime?
6. Licence for the repo (none chosen; I won't pick one for you).
7. `applicationId` is `dev.patchbay`. Do you want your own reverse-DNS name?

**Before Phase 1 (brief §12 — MY DEVICES):**

8. Govee models, and whether LAN Control is on for each.
9. LG TV model/year and webOS version.
10. Which Chromecast generation, or Google TV Streamer.
11. H2D firmware, and whether you're OK losing Bambu Handy/cloud for Developer Mode.
12. Android version on your phone, and whether it's GrapheneOS.
13. Router brand/model, and whether you can create VLANs or firewall rules.
