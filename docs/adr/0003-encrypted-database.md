# 0003 — Encrypted database: SQLDelight + SQLCipher (Android) / sqlite-jdbc-crypt (desktop)

Status: Proposed · Date: 2026-11-05

## Context

Invariant 4 requires the local database to be encrypted at rest, with a data-encryption key (DEK)
wrapped by the OS keystore. We need one schema and query layer for Android and the desktop JVM (and later
the hub), and a **maintained** encryption option on both.

## Decision

- **Query layer:** SQLDelight 2.x (`app.cash.sqldelight`, Apache-2.0). It generates typesafe Kotlin from
  SQL, supports KMP, uses no reflection and no annotation processing, and has drivers for Android and JDBC.
- **Android driver:** `AndroidSqliteDriver` with `net.zetetic:sqlcipher-android` (Zetetic's maintained
  SQLCipher for Android, on Maven Central; 4.20.0 when this ADR was written) via its
  `SupportOpenHelperFactory`.
- **Desktop/hub driver:** `JdbcSqliteDriver` over `io.github.willena:sqlite-jdbc` ("sqlite-jdbc-crypt",
  Apache-2.0, 3.53.x when this ADR was written). It's a drop-in fork of xerial's driver built on
  SQLite3MultipleCiphers, with Windows/Linux/macOS natives bundled.
  - Configure `cipher=sqlcipher&legacy=4` so desktop files use the same SQLCipher 4 format as Android.
    That way one tool (the `sqlcipher` CLI) can open either for debugging or backups.
- **Keys**
  - The DEK is 256 random bits from `SecureRandom`, passed as a raw key (`x'…'`), so there's no PBKDF2
    cost on open.
  - **Android:** the DEK is wrapped with an AES-256-GCM key in Android Keystore. StrongBox is used when
    `FEATURE_STRONGBOX_KEYSTORE` is present, falling back to TEE, with `setUnlockedDeviceRequired(true)`.
  - **Windows:** `CryptProtectData` (DPAPI, CurrentUser scope) via JNA's `Crypt32Util`, with
    app-specific entropy.
  - **Hub:** a key file readable only by the service user (documented weaker posture; a TPM option in
    Phase 3).
- **What goes in the DB:** device registry, pairings (secrets in a separate table, still encrypted
  by the DB), rooms and floor plan, scenes, automations, and the state ledger. Logs are files, not
  DB rows.

## Alternatives considered

- **Room KMP + bundled SQLite:** good on Android. On the desktop JVM, encryption needs a custom driver
  anyway, and Room needs KSP plus more Google artifacts.
- **App-level encryption over plain SQLite:** leaks schema, row counts, sizes and timing metadata, and
  makes queries awkward.
- **Realm Kotlin:** deprecated by MongoDB.
- **H2/HSQLDB encrypted:** no Android story.
- **SQLCipher Commercial for JVM:** paid, and adds licensing complexity for a personal project.

## Consequences

- Two native SQLite builds, one per platform. Both are maintained and both publish to Maven Central.
- `androidx.sqlite` (from Google Maven) comes in as a dependency of the Android driver.
- Backups are deliberately unsupported in v1 (`allowBackup=false`). Moving to a new phone means pairing
  peers again and syncing (ADR 0002).

## Verification / follow-ups (Phase 1)

- [ ] Contract test: the same SQLDelight schema migrates and round-trips on both drivers.
- [ ] Test: a file opened without the key fails, and the DB header isn't the plaintext `SQLite format 3`.
- [ ] Confirm current licences and versions at integration time, and add `DEPENDENCIES.md` entries.
- [ ] Confirm sqlite-jdbc-crypt's bundled natives cover `windows-x86_64` and `linux-aarch64` (hub on a Pi).
