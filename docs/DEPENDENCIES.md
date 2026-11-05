# Dependencies

Every dependency needs an entry here **before** it's added (CLAUDE.md). Columns: what it's for, licence,
and whether it makes network calls **at runtime in our app**. Versions live in `gradle/libs.versions.toml`.
Checksums live in `gradle/verification-metadata.xml`. The CycloneDX SBOM (CI `sbom` job) is the
authoritative transitive list.

## Runtime (shipped)

| Dependency | Version | Used by | Purpose | Licence | Network at runtime? |
|---|---|---|---|---|---|
| `org.jetbrains.kotlin:kotlin-stdlib` | 2.4.21 | all | Kotlin standard library | Apache-2.0 | No |
| `org.jetbrains.kotlinx:kotlinx-coroutines-core` | 1.11.0 | core (api) | Coroutines, Flow | Apache-2.0 | No |
| `org.jetbrains.kotlinx:kotlinx-coroutines-swing` | 1.11.0 | app-windows | Main dispatcher on AWT/Swing | Apache-2.0 | No |
| `org.jetbrains.kotlinx:kotlinx-serialization-json` | 1.12.0 | core | JSON for Govee/webOS/Bambu payloads (strict mode, Phase 1) | Apache-2.0 | No |
| `org.jetbrains:annotations` | 23.0.0 | transitive (stdlib) | Nullability annotations | Apache-2.0 | No |
| `org.jetbrains.compose.runtime:runtime` | 1.12.2 | ui | Compose runtime (desktop artifacts; maps to `androidx.compose.runtime` on Android) | Apache-2.0 | No |
| `org.jetbrains.compose.foundation:foundation` | 1.12.2 | ui | Layout, text, gestures, Canvas. **No material/material3.** | Apache-2.0 | No |
| `org.jetbrains.compose.ui:ui` | 1.12.2 | ui | Compose UI core | Apache-2.0 | No |
| `org.jetbrains.skiko:skiko-awt-runtime-*` | (via Compose) | app-windows, ui tests | Skia rendering natives | Apache-2.0 | No |
| `androidx.*` (collection, annotation, lifecycle, savedstate, compose.* on Android) | (via Compose) | ui, app-android | Transitive Jetpack libraries; from Google Maven | Apache-2.0 | No |
| `androidx.activity:activity-compose` | 1.11.0 | app-android | `setContent` for `ComponentActivity` | Apache-2.0 | No |
| `androidx.biometric:biometric` (+ `androidx.fragment`) | 1.1.0 | app-android | App lock via BiometricPrompt (strong biometric or device credential) | Apache-2.0 | No |
| `app.cash.sqldelight:runtime` | 2.4.1 | core | Typesafe SQL (ADR 0003) | Apache-2.0 | No |
| `app.cash.sqldelight:sqlite-driver` (xerial `sqlite-jdbc` **excluded**) | 2.4.1 | core (jvm) | JDBC driver glue | Apache-2.0 | No |
| `io.github.willena:sqlite-jdbc` (sqlite-jdbc-crypt) | 3.53.4.0 | core (jvm) | Encrypted SQLite (SQLite3MultipleCiphers, SQLCipher-4 format) with bundled natives | Apache-2.0 | No |
| `net.java.dev.jna:jna-platform` | 5.19.1 | core (jvm) | Windows DPAPI (`Crypt32Util`) for key wrapping | Apache-2.0 / LGPL-2.1 (dual) | No |
| `app.cash.sqldelight:android-driver` (+ `androidx.sqlite`) | 2.4.1 | app-android | SQLDelight on Android | Apache-2.0 | No |
| `net.zetetic:sqlcipher-android` | 4.20.0 | app-android | SQLCipher (encrypted SQLite) | BSD-style (Zetetic) | No |
| IBM Plex Sans / Mono (TTF, vendored) | upstream commit `48e8676` | ui, app-android | Typeface (ADR 0004) | OFL-1.1 (RFN "Plex") | No |

Planned, not yet added. Each needs this table updated and verification metadata regenerated:
`org.bouncycastle:bcprov-jdk18on` (X25519, Ed25519, ChaCha20-Poly1305, HKDF, SRP-6a for HAP and Noise),
`androidx.biometric`, `androidx.glance`, Jazzer (test).

Build-only transitive tooling seen in the verification metadata, not shipped (absent from the runtime SBOM):
`io.ktor:ktor-server-*` and `io.modelcontextprotocol:kotlin-sdk-*` (Compose hot-reload / IDE tooling plugins),
`org.tensorflow:tensorflow-lite-metadata` (AGP's ML model binding).

## Test only

| Dependency | Version | Purpose | Licence |
|---|---|---|---|
| `org.jetbrains.kotlin:kotlin-test(-junit5)` | 2.4.21 | Assertions + JUnit 5 integration | Apache-2.0 |
| `org.junit.jupiter:*`, `org.junit.platform:junit-platform-launcher` | 5.10.1 / 1.10.1 | Test engine (via kotlin-test-junit5) | EPL-2.0 |
| `org.jetbrains.kotlinx:kotlinx-coroutines-test` | 1.11.0 | Virtual time for Flow tests | Apache-2.0 |

## Build tooling (not shipped)

| Tool | Version | Purpose | Licence | Network during build? |
|---|---|---|---|---|
| Gradle (wrapper) | 9.8.2, sha256 pinned | Build | Apache-2.0 | Downloads the distribution once (checksum-verified) |
| Kotlin Gradle plugin + compiler | 2.4.21 | Compile | Apache-2.0 | Via Gradle repos only |
| Android Gradle Plugin | 9.4.1 | Android build, R8, Lint | Apache-2.0 | Via Google Maven; may download missing SDK platforms on CI |
| Compose Multiplatform Gradle plugin | 1.12.2 | Compose compiler wiring, desktop packaging (jpackage) | Apache-2.0 | **Would download WiX from GitHub without a checksum.** CI fetches WiX itself with a pinned SHA-256 and disables the plugin's download (`ci.yml`) |
| ↳ `compose.hot-reload` Gradle plugin | 1.2.0 (transitive) | Dev-time hot reload (unused) | Apache-2.0 | Not invoked by our tasks |
| WiX Toolset | 3.11.2 (`wix311-binaries.zip`, sha256 `2c1888d5…9e2e`) | MSI creation via jpackage | MS-RL | CI download, checksum verified. WiX 3 is legacy; revisit when jpackage on our JDK supports WiX 4/5 |
| CycloneDX Gradle plugin | 3.5.1 | SBOM | Apache-2.0 | No |
| GitHub Actions: checkout v7.0.1, setup-java v6.2.0, gradle/actions v6.4.1, upload-artifact v7.0.2 | pinned by SHA in workflows | CI | MIT | CI only |
| fontTools (Python, local only) | — | Inspected font features for ADR 0004 | MIT | Not part of the build |
