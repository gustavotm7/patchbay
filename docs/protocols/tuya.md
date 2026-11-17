# Tuya-based devices (Lepro LampUX bulbs, Lefant robot vacuum — to be confirmed)

Status: 2026-11-17. ADR 0007 **accepted**. **Bulb adapter built** (`core/.../adapter/tuya/`): protocols **3.3, 3.4 and 3.5** (ADR 0015), added by address,
user-supplied local key (`docs/TUYA_KEYS.md`), two allowlisted layouts ("standard" data points 20 to 24, "older" 1 to 5). **Not built:**
the **vacuum's controls**: its data points differ per model, so it needs the model number and its data-point list (from the tinytuya
wizard) before any command is allowed. The Kelvin range (2700 to 6500) is an assumption to calibrate on the real bulb.

## How the three versions differ (as implemented)

| | 3.3 | 3.4 | 3.5 |
|---|---|---|---|
| Frame | `55AA`, CRC-32 | `55AA`, HMAC-SHA256 (32 bytes) instead of the CRC | `6699`, AES-128-GCM; the 14 header bytes after the prefix are authenticated |
| Payload cipher | AES-128-ECB, local key | AES-128-ECB, **session key** | AES-GCM, **session key** (12-byte IV in the frame) |
| Session | none (one request, one reply) | handshake first: our nonce (command 3), the device's nonce and an HMAC of ours (4), an HMAC of theirs (5) | the same handshake inside GCM frames |
| Session key | the local key | AES-ECB of (our nonce XOR theirs) under the local key | AES-GCM of (our nonce XOR theirs), IV = first 12 bytes of our nonce, first 16 ciphertext bytes |
| Query / control | commands `0A` / `07` | `10` with `{}` / `0D` with `{"protocol":5,"t":…,"data":{"dps":{…}}}` after a "3.4" + 12 zero bytes header | the same, header "3.5" |
| Reply | return code, then payload | return code, then the encrypted payload; data points under `data.dps` | the return code is the first 4 bytes of the sealed plaintext |

The handshake proves the device holds the local key (its HMAC covers our nonce), so a device with another key never completes it. Pairing does not know which version a device speaks,
so it tries 3.3, then 3.4, then 3.5 and keeps the first that answers (stored as a third line of the pairing secret; older secrets without it are 3.3). If none answers, the message depends on
what happened: a device that refused the key or sent something that is not Tuya says so; a device that took the connection and then never answered usefully in any version almost always has a
different local key, and the message says that. A firmware update can change a device's version; forget and add it again to detect it afresh.

## Reply habits handled (from tinytuya's documented real-device behaviour)

- **An empty acknowledgement before the data.** Some devices answer a query with a frame that has a return code and no payload and only then send the data (tinytuya calls it a
  "null ack"). A query skips up to three such frames and uses the first that has data; a control command is answered by its first frame, whatever it holds.
- **"device22" devices.** Some protocol 3.3 devices, often with 22-character ids (the vacuum's id in `docs/TUYA_KEYS.md` has 22), answer the normal query with "data unvalid" and only
  answer the control-style command `0D` carrying `"dps":{"1":null,"2":null,...}`, naming the data points wanted. Patchbay notices "data unvalid" in a reply (whatever return code comes
  with it), asks that way from then on, and records it in the pairing (a fourth line, `device22`). A bulb profile asks for its own five data points; the read-only profile tries the usual
  ranges (2-10, 11-20, 21-30, 50-60, 61-70, 100-110, 111-120, 121-130, 131-140, always with 1) once and then asks only for the numbers that answered. Not done for 3.4 and 3.5.
- An error reply's payload is decrypted when it can be (the reason may be in it) and left unread when it is plain text.

Checked against: the tinytuya library's own frames and session keys (`TuyaSecureFramesTest`, vectors from `tools/interop/tuya_vectors.py`) and tinytuya as a client against our simulators
(`docs/protocols/interop.md`). Not checked against any real device.

## Why these two are grouped

Lepro (LE LampUX) bulbs and Lefant robot vacuums are, as far as the public record shows, built on the **Tuya** smart-device
platform and set up through a Tuya-based app. That is an inference from community reports, not something I confirmed for your
exact models. Neither vendor documents a local API of its own.

## What Tuya "local control" involves

| Piece | Detail |
|---|---|
| Transport | TCP 6668 on the device's LAN address, plus UDP broadcast announcements on 6666/6667 |
| Protocol versions | 3.1, 3.3, 3.4, 3.5; the version differs by firmware. 3.4+ negotiates a session key; 3.5 uses AES-GCM |
| Credentials | A per-device **local key** (16 characters) and the device ID |
| Where the key comes from | **Only from Tuya's cloud** (a Tuya IoT developer account linked to the vendor app, or the app's own API). It is not on the LAN and not in the device. It changes if the device is removed and re-added to the app |
| What the commands mean | Numbered "data points" (DPs) whose meaning differs per model. A bulb's power/brightness/colour DPs and a vacuum's start/pause/return DPs must be looked up per model |

Community integrations (LocalTuya, Tuya Local, tinytuya) work this way. Some report newer firmware that blocks simple local control.

## Why this needs your decision (ADR 0007)

- **The local key requires one cloud login**, made by you, once, outside Patchbay. Patchbay would never contact Tuya; you'd paste the
  device ID and local key in. That keeps invariants 1 and 3 intact in the app, but it is an extra manual step that exposes your
  vendor account to a developer-console workflow.
- **The local key is a secret** with the same standing as the Bambu access code: encrypted at rest, never logged.
- **A vacuum's commands are physical motion** in your home. I'd expose **start/pause/dock** only (no map, no remote driving, no camera),
  and treat any camera or microphone on a vacuum as out of scope.
- **Bulbs are easy once the key exists**: Power, Brightness, Colour temperature, RGB map onto the existing capabilities.

## What I need from you

1. The exact model numbers (the sticker on the bulb or vacuum, or the app's device info), and which app you set them up in.
2. Whether you're comfortable fetching the local keys yourself (I'd write step-by-step instructions), or would rather skip these two
   devices and keep them in the "cloud-only, untouched" bucket of the Exposure Map.
3. Approval of ADR 0007 (proposed), or changes to it.
