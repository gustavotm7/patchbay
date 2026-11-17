# Read this first

Patchbay is a local-only home control app (Android + Windows). Nothing in it talks to the internet or to any vendor cloud.

1. **What to try:** `docs/STATUS.md` ("Get the builds") has the download links. `docs/HARDWARE_TEST.md` is the checklist, one section per device; start with app lock, Govee, TV, Bambu, Kasa, then ecobee (E2.x) and the vacuum (V1.x).
2. **What works, honestly:** `docs/STATUS.md`. Every adapter is tested against a simulator I wrote; most of the protocols (HomeKit, Tuya 3.3 to 3.5, Kasa, Cast, Google TV, Bambu, Govee, webOS, camera video) are also checked against independent reference code (`docs/protocols/interop.md`). Nothing has run on a real device until you run `docs/HARDWARE_TEST.md`.
3. **Why it is built this way:** `CLAUDE.md` (the privacy rules) and `docs/adr/` (one short page per decision, 0001 to 0016).
4. **If something fails:** `docs/TROUBLESHOOTING.md`, then send me the step number from HARDWARE_TEST and what you saw.
5. **What is being worked on and why:** `docs/PLAN.md`.
6. **Continuing in a new session:** `docs/HANDOFF.md`.

## Decisions taken (2026-11-12)

- Parsers are mutation-tested (tens of thousands of random and damaged inputs each); Jazzer is a possible later addition. `CLAUDE.md` now says so.
- The Windows app lock is a PIN; Windows Hello is a possible later addition. `CLAUDE.md` now says so.
- Still true: every adapter, the Android service and the animations are unit-tested but have never run on a real device.
