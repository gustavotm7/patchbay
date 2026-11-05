# 0002 — Peer-sync channel: Noise (XXpsk3 for pairing, KK for sessions)

Status: Proposed · Date: 2026-11-05

## Context

The phone and the laptop sync the device registry, rooms, scenes, automations and **device credentials**,
only when both are on the LAN, and never through a relay. They pair once by QR code. The brief asks us to
choose between pinned mutual TLS and a Noise channel.

Constraints:

- Both ends are ours. There's no interoperability requirement with third-party software.
- The QR code is the root of trust. Its secret should be cryptographically bound into the first handshake,
  not just compared afterwards.
- We already need Noise for ESPHome's native API (`Noise_NNpsk0_25519_ChaChaPoly_SHA256`), and X25519 /
  ChaCha20-Poly1305 / HKDF for HomeKit (HAP).
- The brief bans trust-all managers and hostname-verification bypasses, and CI greps for them.

## Decision

Use the **Noise Protocol Framework** over TCP on the LAN, implemented once in `core/net/noise` on
BouncyCastle's lightweight primitives (`bcprov-jdk18on`), so behaviour is identical on every Android API
level and on desktop.

**Pairing**

1. The device showing the QR code (either one) generates a long-term X25519 static key (stored wrapped
   by the OS keystore).
2. It also generates a one-time 32-byte PSK and listens on an ephemeral TCP port.
3. QR payload, versioned and CBOR-encoded: `v=1`, LAN endpoint (IP:port), SHA-256 of the shower's static
   public key, the PSK, and an expiry 120 s ahead.
4. The scanner connects and runs **`Noise_XXpsk3_25519_ChaChaPoly_SHA256`**. Both parties learn each
   other's static key. The PSK proves the scanner saw the QR code. The scanner checks that the
   responder's static key matches the QR fingerprint.
5. Both sides show a 6-digit **short authentication string** derived from the handshake hash. The user
   confirms they match. This defends against a camera-less attacker who reads the QR off-screen.
6. Each side pins the other's static key. The PSK is discarded.

**Sessions:** **`Noise_KK_25519_ChaChaPoly_SHA256`**. Both statics are known, so a session takes one round
trip, has forward secrecy, and fails immediately if a key has changed (surfaced as `TrustChanged`).
Peers find each other via mDNS `_patchbay._tcp`. The TXT record carries only a key-fingerprint prefix,
never names or secrets.

**Sync protocol (sketched here, specified in Phase 2)**

- Records are field-level last-writer-wins registers, ordered by a **hybrid logical clock**
  `(wallMillis, counter, nodeId)`. Ties break on `nodeId`. Deletes are tombstones with an HLC.
- Credentials travel inside the Noise channel and are re-encrypted at rest on arrival with the receiver's
  own data-encryption key.
- Every frame is length-prefixed (≤ 64 KiB) and parsed strictly. Unknown record types are rejected,
  not ignored.

## Alternatives considered

**Pinned mutual TLS 1.3 with self-signed certificates.** Rejected, because:

- We'd need X.509 generation, which means BouncyCastle `bcpkix`, or writing DER by hand.
- We'd add an ASN.1 parsing surface that a peer controls.
- We'd have to write the custom `X509TrustManager` code our policy otherwise bans, and handle
  certificate expiry.
- JSSE and Conscrypt behave differently.
- JSSE doesn't expose TLS 1.3 external PSKs, so the QR secret couldn't be bound into the handshake.
  We'd be comparing fingerprints after the fact.

Advantages we give up: TLS is ubiquitous and has mature implementations.

**Other options:**

- **noise-java (rweather)** — unmaintained for years. Using it means owning a fork anyway.
- **WireGuard-style static tunnel** — out of scope, and needs privileges.
- **libsodium / Tink via JNI** — the brief prefers no native FFI. Tink also pulls Google dependencies.

## Consequences

- We own a Noise implementation. Mitigations:
  - Pass the published **cacophony** and **snow** test vectors for our three patterns (NNpsk0, XXpsk3, KK).
  - Fuzz the handshake and transport parsers with Jazzer.
  - Constant-time MAC comparison.
  - Zero ephemeral keys after the handshake.
  - Keep it under roughly 600 lines.
- The ESPHome adapter and peer sync share and exercise the same code.

## Verification / follow-ups

- [ ] Phase 2: implement; vendor the test vectors under `core/src/commonTest/resources/noise/` with
      their licence.
- [ ] Interop test against a second, independent implementation (Python `noiseprotocol`, CI-only) for
      every pattern.
- [ ] Owner decision: is the 6-digit SAS confirmation acceptable UX? (Recommended.)
