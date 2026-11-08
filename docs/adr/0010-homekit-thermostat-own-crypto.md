# 0010 — HomeKit (HAP) thermostats: local pairing, own Curve25519

Status: Accepted (owner asked for the ecobee over HomeKit: "premium, go build all") · Date: 2026-11-08

## Context

The ecobee Premium has no usable local API except HomeKit Accessory Protocol (HAP) over IP; ecobee's cloud developer API is closed to new keys (BRIEF §5).
HAP needs SRP-6a (3072-bit, SHA-512), HKDF-SHA512, Ed25519, X25519 and ChaCha20-Poly1305. Android's JCA offers Ed25519 and X25519 only from API 33
and the app supports API 31+. ChaCha20-Poly1305, SHA-512 and HMAC are available on both platforms.

## Decision

- Implement SRP-6a, HKDF, TLV8, X25519 and Ed25519 in `core/.../adapter/homekit/` on `java.math.BigInteger`; use the platform provider only for
  ChaCha20-Poly1305. **No new dependency** (BouncyCastle was the alternative).
- Verify them with published vectors (RFC 7748, RFC 8032, RFC 7539, the aiohomekit HKDF and TLV vectors), by checking that the SRP group is a 3072-bit safe prime
  and that k computed as H(N | g) equals the fixed value HAP uses, and by cross-checking Ed25519/X25519 against the JDK's own implementations on random inputs.
- The simulator (`HomeKitSimulator`) uses the JDK's Ed25519, X25519 and ChaCha20 and its own SRP server, so the adapter is tested against crypto it did not write.
- Pairing uses the accessory's setup code (SRP), then shows the SHA-256 fingerprint of the accessory's long-term key (TOFU, invariant 5). Declining at the fingerprint
  removes the pairing from the thermostat again. Every later connection is pair-verify against the pinned key and accessory id; a mismatch is `TrustChanged`
  and no command is sent.
- Polling (every 5 s) over one verified, encrypted connection. HAP event subscriptions are not used yet.
- Only accessory category 9 (thermostat) is offered. Other HomeKit devices are ignored.

## Alternatives considered

- BouncyCastle: well-reviewed, but a ~4 MB dependency with its own verification and R8 work, for four primitives.
- Android-only `Cipher`/`KeyPairGenerator` for Ed25519/X25519: not available on API 31-32.
- Libsodium/Tink: native or large; same objection.

## Consequences

- The curve code is **not constant-time** (BigInteger). An attacker would need to time operations on the same device; that is outside the threat model.
  Replace with a reviewed library if the model changes.
- The pair-setup path (`/pair-setup`), method value 0 (plain PairSetup, not "with auth") and the absence of a Flags TLV follow the HAP specification and common controllers
  but were **not confirmed against a real ecobee**; HARDWARE_TEST E2.x is the first check.
- ecobee's Home/Away/Sleep comfort modes and air-quality readings are not HomeKit characteristics, so they are not available.
- The thermostat can be paired to one controller at a time: it must be removed from Apple Home first (Home app, remove accessory). Apple Home then stops controlling it.
