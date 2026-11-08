# HomeKit Accessory Protocol over IP (ecobee thermostat)

Status: 2026-11-08. **Built** (`core/.../adapter/homekit/`), tested against a simulator whose cryptography is independent of the adapter. **Not yet tried on a real ecobee.**
Sources: aiohomekit (Jc2k, Apache-2.0) and HAP-python (ikalchev) source and tests, read 2026-11-08; Apple's public spec was not read.

| Piece | Detail |
|---|---|
| Discovery | mDNS `_hap._tcp`; TXT `id` (accessory id), `ci` (category, 9 = thermostat), `sf` (status flags, bit 0 = unpaired), `md` (model); SRV gives the port |
| Transport | HTTP/1.1 over TCP. `Content-Type: application/pairing+tlv8` for pairing, `application/hap+json` for the rest |
| Pair-setup | `POST /pair-setup` (path unconfirmed in the references; standard HAP), TLV8 M1..M6: SRP-6a (3072-bit group, g = 5, SHA-512, fixed k, user `Pair-Setup`, password = setup code `XXX-XX-XXX`), then Ed25519 identities exchanged inside ChaCha20-Poly1305 (nonces `PS-Msg05`/`PS-Msg06`) |
| Pair-verify | `POST /pair-verify`: X25519, HKDF-SHA512 (`Pair-Verify-Encrypt-*`), signatures checked against the pinned long-term key, nonces `PV-Msg02`/`PV-Msg03` |
| Session | Frames of up to 1024 bytes: 2-byte little-endian length (also the AAD), ciphertext, 16-byte tag. Nonce = 4 zero bytes + 64-bit LE counter per direction. Keys from HKDF(`Control-Salt`, `Control-Write-Encryption-Key` / `Control-Read-Encryption-Key`) |
| Read/write | `GET /accessories`, `GET /characteristics?id=aid.iid,…`, `PUT /characteristics` (204 = ok, 207 = per-item status) |
| Not used | Event subscriptions (we poll every 5 s), session resume, MFi "pair-setup with auth" |

## What the thermostat gives us (HAP-python's characteristic tables)

Thermostat service `4A`: current/target heating-cooling state (`0F`, `33`; off/heat/cool/auto), current/target temperature (`11`, `35`, °C),
cooling/heating thresholds (`0D`, `12`, used in auto), humidity (`10`), display units (`36`; ignored, we are SI). Occupancy sensors `86` (`71`).
Ranges come from each characteristic's own min/max, not from constants. Heat mode edits the single target temperature; cool mode too; auto edits both thresholds.

## Not available over HomeKit

ecobee's Home/Away/Sleep comfort modes, schedules, air-quality (Premium sensors) and runtime reports. That is a HomeKit limit, not ours.

## Unverified until a real ecobee

1. `/pair-setup` path and `Method = 0`.
2. Whether the ecobee wants a Flags TLV or the "with auth" method.
3. Whether the minimum setpoint gap (we assume 1 °C) matches the thermostat.
4. Whether sensors appear as extra accessories (we read occupancy services from any accessory).
