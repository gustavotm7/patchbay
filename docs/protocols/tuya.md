# Tuya-based devices (Lepro LampUX bulbs, Lefant robot vacuum — to be confirmed)

Status: 2026-11-06. ADR 0007 **accepted**. **Bulb adapter built** (`core/.../adapter/tuya/`): protocol **3.3** only, added by address,
user-supplied local key (`docs/TUYA_KEYS.md`), two allowlisted layouts ("standard" data points 20 to 24, "older" 1 to 5). **Not built:**
protocol 3.4/3.5, and the **vacuum**: its data points differ per model, so it needs the model number and its data-point list (from the tinytuya
wizard) before any command is allowed. The Kelvin range (2700 to 6500) is an assumption to calibrate on the real bulb.

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
