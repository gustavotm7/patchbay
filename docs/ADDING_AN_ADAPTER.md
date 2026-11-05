# Adding a new device system

Govee is the reference implementation. Copy its shape. Every adapter goes through the same steps, so new
systems (eufy RTSP, webOS, Cast, Bambu, ESPHome, …) land the same way, with the same guarantees.

## 1. Research (before code)

Write `docs/protocols/<system>.md`. It covers:
- transport and ports
- messages
- authentication and pairing
- what's local vs cloud (tier **L**, **LS** or **C**)
- primary sources (vendor docs, maintained open-source integrations)
- safety scope (what we deliberately won't control)

**Cloud-only systems stop here.** They are never a local adapter (invariant 3).

## 2. Model

- Map the device to existing `Capability` types. Add a new capability only if nothing fits, and keep it
  typed and SI (`Celsius`, `Percent`, `Kelvin`).
- Dangerous operations get **no `Command` type at all** until an ADR approves them (printer heating,
  lock/unlock).
- Add a `DeviceKind` if needed, and give it a geometric glyph in `ui/designsystem/Glyphs.kt`.

## 3. Codec (pure, `core/src/commonMain/.../adapter/<system>/`)

- A strict parser: size cap, depth cap, type and range checks. Reject out-of-spec values; never clamp.
  Ignore unknown fields.
- Exact-bytes encoder tests, plus rejection tests, plus a seeded mutation test (≥ 20 000 inputs) that
  must never throw.

## 4. Adapter (`core/src/jvmShared/.../adapter/<system>/`)

- Implement `DeviceAdapter`: `discover`, `pair`, `deviceId`, `capabilities`, `restore`, `observe`,
  `command`, `health`.
- Use **only** `NetGuard` for sockets: `connectTcp`, `connectTls` (TOFU pinning), `openUdp`,
  `openMulticast`. `tools/check-policy.sh` enforces this.
- Trust the network source address, never addresses inside payloads.
- Pace commands, poll politely, back off exponentially when a device is unreachable.
- Secrets go in `Pairing.secret` (stored encrypted), never in logs.

## 5. Simulator and contract tests (`simulators/`)

- A fake device bound to its own loopback address (127.0.0.x) that behaves like the firmware, with
  fault injection: offline, malformed replies.
- Contract tests: the real adapter against the simulator, covering:
  - discovery
  - pairing
  - every command
  - offline and back-off
  - malformed replies
  - forged replies from another address
  - pacing

## 6. Register

Add a factory to `AdapterRegistry.LOCAL` in `core/src/jvmShared/.../adapter/AdapterRegistry.kt`. Bootstrap
creates it at startup and the UI renders it from its capabilities. No screen code is needed for standard
capabilities.

## 7. Ship

- `docs/DEPENDENCIES.md` entry for any new library, with verification metadata regenerated (push to a
  `ci/verification-bootstrap*` branch).
- A `docs/HARDWARE_TEST.md` section with steps and expected results on real hardware.
- An update to `docs/STATUS.md`.
