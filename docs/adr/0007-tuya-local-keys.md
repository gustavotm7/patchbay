# 0007 — Tuya-based devices: user-supplied local keys

Status: Accepted (owner approved 2026-11-06) · Date: 2026-11-06

## Context

Lepro LampUX bulbs and Lefant robot vacuums appear to be Tuya-platform devices (`docs/protocols/tuya.md`). Tuya's local protocol
(TCP 6668) needs a per-device local key that can only be obtained from Tuya's cloud. Invariants 1 and 3 forbid Patchbay from
talking to vendor clouds; the key is, however, a one-time fact the owner can look up themselves.

## Decision (proposed)

- Patchbay **never contacts Tuya**. There is no cloud login in the app and no Tuya host in any code or allowlist.
- An optional `tuya-local` adapter takes, per device: LAN IP, device ID, local key, and (for vacuums) a model profile of data points.
  The owner enters them in the app; they are stored encrypted like other pairing secrets (invariant 4) and redacted in logs (invariant 7).
- Device profiles are **explicit allowlists** of data points per model, shipped in the app. Unknown data points are ignored, never written.
- Scope for vacuums: start/pause/return-to-dock and read-only battery and status. No remote driving, no map, no camera or audio, no firmware.
- Bulbs: Power, Brightness, Colour temperature and RGB only.
- The adapter is off by default and only appears once the user adds a device.

## Consequences

- The owner has to fetch keys once, outside the app, and re-fetch them if they re-add a device to the vendor app.
- Models whose firmware blocks local control stay in the Exposure Map as cloud-only.
- The bulb adapter exists (protocol 3.3). Vacuum support waits for a model number and its data-point list, because the profiles are model-specific.
