# eufy (Anker) cameras and smart lock

Status: 2026-11-06. **RTSP camera adapter shipped** (`core/.../adapter/rtsp/`): add by address, pair with the RTSP
username and password, status check (DESCRIBE with Digest or Basic auth), view-only. **No video playback yet.** The lock
is still untouched. The demo home includes a eufy indoor camera and front-door lock so the Exposure Map and design cover them.

How the adapter behaves: cameras don't advertise RTSP, so they're added through "Add by address" (IP + a name); pairing
asks for the RTSP login and proves it with a real `DESCRIBE rtsp://<ip>:554/live0`. Credentials are sent only after the
camera issues a challenge, only to its LAN address, stored encrypted, never logged or shown. RTSP is cleartext on the LAN:
the adapter prefers Digest; a camera that only offers Basic exposes the password to anyone sniffing that network.

## What's local and what isn't

| Device / path | Tier | What we know | Plan |
|---|---|---|---|
| Camera **RTSP stream** (eufyCam 2/2C, indoor cams, Solo cams; per-camera toggle "RTSP stream" / NAS in the eufy app) | **LS** | A plain RTSP server on the camera or HomeBase. The URL carries a username/password you set in the app. Not every model has it. | **RTSP adapter** (Phase 2): view-only live stream. Credentials in the encrypted DB. NetGuard already confines it to LAN addresses; no extra exception was needed. |
| HomeBase **P2P** protocol (what `eufy-security-client` / Home Assistant use) | **C** | Needs your eufy **cloud login** to fetch station keys, then talks P2P (often locally). A newer library claims local-only operation after a cloud key fetch (beta). | Not in v1 (invariant 3). If ever added: a quarantined, opt-in module with a per-vendor host allowlist, an on-screen indicator and a kill switch. Needs an ADR. |
| **Smart lock** (Wi-Fi/BLE models) | **C** | Control is through the eufy cloud or app (BLE nearby). No documented local LAN API found. | Exposure Map entry "cloud-only, untouched". |
| Smart lock **with Matter** (if your model has it) | L (via Matter) | Matter locks expose lock state over the local network. | Phase 3 Matter spike. **Read-only state first.** See below. |

## Lock safety rule (proposed; needs your approval)

A door lock is a physical-security device. Like the printer's heater and motion controls:

- The model has `Capability.LockStatus` and `CapabilityState.LockState` (locked/unlocked/jammed, battery) and
  **no lock/unlock command type**, so no code path can send one.
- Adding *lock* (relatively safe) or *unlock* (security-critical) needs an ADR covering:
  - re-authentication (biometric) per action,
  - a two-step confirm,
  - never in automations,
  - never from a widget or tile,
  - a ledger entry for every attempt.

## Sources

- `eufy-security-client` (npm): cloud login + P2P; RTSP toggle only for camera2, indoor and solo products.
  https://npmjs.com/package/eufy-security-client
- `eufy-home-security` (PyPI, beta): local HomeBase access after a cloud key fetch. https://pypi.org/project/eufy-home-security/

## What I need from you

- Exact camera models, and whether they sit behind a HomeBase (and which: 2 or 3).
- The lock model, and whether the box or app mentions Matter.
- Whether you're OK enabling RTSP. It's a local password-protected stream, but RTSP itself is unencrypted on the LAN.
