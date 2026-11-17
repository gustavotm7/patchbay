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

## Desk-check against independent accessory code (2026-11-17)

Read through a fetch agent (so the quotes were reported, not re-fetched by the author): HAP-python's `pyhap/hap_handler.py`, `hap_server.py`, `hap_protocol.py`, `hap_crypto.py`; HAP-NodeJS's `src/lib/HAPServer.ts`, `util/eventedhttp.ts`, `util/hapCrypto.ts`; and aiohomekit's `protocol/__init__.py` and `controller/ip/{controller,pairing,connection}.py`. HAP-python is Apache-2.0, HAP-NodeJS ISC, aiohomekit Apache-2.0. All of these are *software* accessories or controllers, not Apple-certified firmware like the ecobee's.

| Item | Result |
|---|---|
| Paths and methods: `POST /pair-setup`, `POST /pair-verify`, `POST /pairings`, `GET /accessories`, `GET /characteristics?id=aid.iid,...`, `PUT /characteristics` | Confirmed by both accessory implementations' routing tables |
| Request Content-Type, Host, Accept, User-Agent | Neither accessory checks them; we send the standard values anyway |
| HKDF salts/infos (`Pair-Setup-*`, `Pair-Verify-Encrypt-*`, `Control-Salt`, `Control-Read/Write-Encryption-Key`), nonce labels (`PS-Msg05/06`, `PV-Msg02/03`), signed byte layouts, Read = accessory to controller, Write = controller to accessory | All match our client |
| Encrypted frames: 2-byte little-endian length (also the AAD), at most 1024 bytes of plaintext, 16-byte tag, 12-byte nonce = 4 zero bytes + 8-byte little-endian counter, one counter per direction; encryption starts with the controller's first *encrypted* request (the M4 reply is plaintext) | Identical in HAP-python, HAP-NodeJS and aiohomekit; matches ours |
| Controller id must be an **uppercase UUID** (HAP-python: iOS 16+ unpairs the accessory otherwise) | Ours is; now asserted in a test |
| Error codes: wrong proof = M4 error 2; already paired = M2 error 6; HAP-python lockout = M2 error 7; HAP-NodeJS lockout after 100 failures = error 5; some accessories answer a bad M5 in M4, others in M6 | We read an error TLV in any state; mapped |
| `GET /characteristics`: 200, or 207 with a per-characteristic `status` when any fails (HAP-python strips `status` on 200); `PUT`: 204, or 207 with statuses | Our parsers handle both shapes |
| HTTP response framing | HAP-python forces `Content-Length` ("iOS can sometimes stall if it gets chunked encoding"). HAP-NodeJS never sets it, so Node's HTTP server sends **chunked** replies (our inference from the code, not a quoted fact). Chunked support was therefore necessary and stays |
| Request framing | aiohomekit: "It is vital that each request is sent in one call". Ours now writes head and body in one call |
| Timeouts | aiohomekit allows 30 s per request (including pair-setup) and 10 s to connect; the accessory computes a 3072-bit SRP step. We had 6 s. Now 30 s for pairing, 15 s otherwise, 8 s to connect |
| Pair-setup **Method** | Neither accessory reads it. aiohomekit's default is "Pair Setup with Auth" (1). Apple firmware with an authentication chip may insist on one value. Now handled: pick by the mDNS `ff` flag (bit 0 = auth chip: try 1 first, else 0 first) and, if M2 refuses, retry once with the other. Extra TLVs in M4 (the chip's EncryptedData) are tolerated |

Not confirmed by these files: how Apple-certified firmware (the ecobee) behaves for any of the above; the Flags TLV (0x13); where aiohomekit's pairing driver chooses `with_auth`.

## Unverified until a real ecobee

1. Which pair-setup method the ecobee accepts (both are now tried; HARDWARE_TEST E2.x asks you to report which one worked via the diagnostics report).
2. Whether it needs the Flags TLV (not implemented).
3. Whether the minimum setpoint gap (we assume 1 °C) matches the thermostat.
4. Whether sensors appear as extra accessories (we read occupancy services from any accessory).
