# 0013 — Encrypted backup: one line of text, a passphrase, AES-GCM

Status: Accepted (part of the pre-hardware plan, W4.2) · Date: 2026-11-17

## Context

Everything Patchbay knows about the home (which devices, their pairings, rooms, scenes, automations) lives in an encrypted database whose key is wrapped by the OS
keystore (invariant 4). That is the right protection at rest, and it also means the data cannot leave the device it was made on: lose the phone and every device must be paired
again, including the ones that need a code the owner may not have to hand. Android backup is switched off (`allowBackup=false`) and must stay off, and there is no cloud.

## Decision

- A backup is made **only when the owner asks** (invariant 7) and is **encrypted with a passphrase typed at that moment** (at least 8 characters, Unicode NFKC-normalised so
  the same passphrase typed on another keyboard opens it). Nothing is sent anywhere (invariant 1).
- Format: `PBBK`, version, KDF id, iteration count, 16-byte salt, 12-byte nonce, then AES-256-GCM of a JSON document, with the header as authenticated data. Key: PBKDF2-HMAC-SHA256,
  600,000 rounds (JCA only: no new dependency, available on Android 12+ and the JVM). A wrong passphrase and a damaged file give the same message, on purpose.
- Contents: devices, their pairings (the secrets), rooms, and the settings `scenes`, `automations`, `adaptive`, `away`, `location`, `favourites`, `quiet`. Not the activity list or the graphs.
- Transport is **text**: one line starting `PATCHBAY-BACKUP-1:` followed by Base64, copied to the clipboard and read back from it. That works the same on Android and Windows with no file
  picker, no storage permission and no path to explain.
- Restore **replaces** what the backup covers (devices not in it are removed with their pairings; settings it does not mention are left alone) and is applied only after the whole file has
  been decrypted, parsed and bounds-checked, so a bad file changes nothing. The restored home is used after the app is closed and opened again.
- Bounds: file at most 4 MB, 500 devices, short strings with no control characters, setting values at most 1 MB, iteration count between 100,000 and 1,200,000 (a hostile file cannot make
  the app spend minutes stretching a key).

## Alternatives considered

- A file with a picker: better for large data, but Android needs the storage access framework and the Windows portable build has no obvious place. The data is small enough for text.
- Argon2 or scrypt: stronger against GPUs, but not in the JCA, so a new dependency (invariant 9) for a backup the owner makes rarely. Revisit if a reviewed library is adopted for other reasons.
- Wrapping with the OS keystore: pointless, the whole purpose is to restore on a *different* device.
- Including the activity history: personal and not needed to get going again.

## Consequences

- The strength of the backup is the strength of the passphrase. The panel says so, and says that a forgotten passphrase cannot be recovered.
- A backup in the clipboard may be kept by a clipboard history. It is encrypted; the passphrase is not on the clipboard.
- Restored addresses may be stale on a new network; rescans and self-healing (PLAN W1) find the devices again with the pairings intact.
