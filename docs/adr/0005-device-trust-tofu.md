# 0005 — Device trust: TOFU with SPKI pinning, outside platform trust stores

Status: Proposed · Date: 2026-11-05

## Context

LAN devices use self-signed TLS: LG webOS on 3001, Cast on 8009, Bambu MQTT on 8883 and FTPS on 990,
Android TV Remote on 6466/6467. No CA can vouch for them, and we must never fall back to "trust all".

## Decision

- Each TLS adapter uses a **per-device pinned trust manager** in `core/net/tls/`. That's the only package
  allowed to implement `X509TrustManager`; CI rule TLS002 enforces this.
- **First contact (pairing):** we accept the leaf certificate for the handshake, compute
  **SHA-256 over its SubjectPublicKeyInfo**, and show it formatted as 8 groups of 4 hex digits in mono.
  We save it only after the user confirms (`PairingPrompt.confirmFingerprint`).
- **Afterwards:** the handshake succeeds only if the presented leaf's SPKI hash matches the pin. Matching
  uses a constant-time comparison. There's no hostname verification: device certificates carry
  meaningless or vendor-wide names, and the pin is strictly stronger. Validity dates are ignored for the
  same reason (several vendors ship expired certificates).
- **On mismatch:** the connection is refused. The device enters `TRUST_CHANGED` and a persistent alarm
  is shown. The user can re-pair, with the new fingerprint shown next to the old one. Nothing re-pins
  silently.
- **Exception:** Cast devices present a certificate chain to a Google device CA. We still pin the SPKI;
  we don't build a Google CA trust store.

## Consequences

- Firmware updates that rotate keys (known on some LG TVs) trigger a re-pair prompt. That's intended, and
  documented in `HARDWARE_TEST.md`.
- Platform trust stores are never consulted for devices, so a user-installed CA can't intercept
  device traffic.

## Verification (Phase 1)

- [ ] Tests against simulators using self-signed certificates: first-use flow, match, mismatch → `TrustChanged`.
- [ ] CI self-test proves TLS001/TLS002 fire outside `core/net/tls/`.
