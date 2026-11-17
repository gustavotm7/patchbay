# Plan: from "passes its own tests" to "ready for the first real-device pass"

Written 2026-11-17. Inputs: the interrupted independent review (`docs/reviews/2026-11-16-fable-review.partial.md`), the earlier review (`docs/reviews/2026-11-10-review.md`), the market notes (`docs/reviews/2026-11-17-market-notes.md`), and a fresh read of the code. Status of each item is tracked in the table at the end; this file is updated as work lands.

## The problem this plan attacks

Everything is verified only against simulators written by the same author from the same reading of each protocol, and the UI and Android code only compile and screenshot in CI. So the largest risk is not a missing feature: it is the gap between "passes my tests" and "works in the owner's house", and the cost of closing that gap once the owner starts testing. This phase therefore does three things, in this order of value:

1. **Remove failure modes real homes hit that no simulator test exercised** (a device changing IP address, a flapping network, state that only ever updates the screen).
2. **Make the first hardware pass cheap**: when something fails on a real device, one copied report should contain enough (redacted) protocol facts to fix it without a second round of testing.
3. **Replace self-consistency with independent verification** wherever that is possible without a device: third-party test vectors, independent reference implementations, and real behavioural tests of the UI.

## Principles for this phase

- **No new product features** unless they directly serve reliability, diagnosability or the safety of the owner's data. The feature backlog (end of this file) waits for hardware results.
- Every change is verifiable here (JVM tests) or in CI (UI/Android/Windows), and ends with a green CI run. Only the Windows portable build is handed over for testing.
- Prefer an independent source of truth (a published test vector, a reference implementation) over another test written by the author.
- Anything that only real hardware can confirm gets an ID in `docs/HARDWARE_TEST.md`; nothing is claimed as working until then.
- Privacy rules in `CLAUDE.md` are not negotiable: diagnostics are redacted by construction (shapes, ids and counters, never secrets or full serials), and tests prove it.

## Workstreams

### W1. Reliability bugs found by reading the code (do first)
- **W1.1 A device that changes IP never recovers.** `LanHome.add` handles a rediscovered device with a new address by updating only what the screen shows. Adapters that keep their own address map (Cast, HomeKit, LG webOS, Bambu, Google TV remote) are never told, so after a DHCP change the device stays unreachable until the app restarts, and the restart reuses the old saved address. Fix generically in `LanHome` (call `restore` with the stored pairing and the new address; persist it), prove with simulators that move to a new address. Acceptance: a test per pinned adapter in which the device moves and Patchbay reconnects after one rescan with the pinned key still enforced.
- **W1.2 Self-healing rediscovery.** A device that stays offline for several polls triggers a rate-limited rescan of its own adapter (back-off, never faster than every few minutes), so a moved device returns without the user pressing Scan. Acceptance: a test where the simulator moves while Patchbay runs and the device comes back without a manual scan, and a test that the rescan rate is bounded.

### W2. Make hardware testing cheap and high-signal
- **W2.1 Diagnostics report** (Settings, "Copy diagnostics"): version, OS, adapter list, per-device health and age of last contact, rejected-message counters with the last few rejection reasons per adapter, the *shape* of what each device reports (HomeKit service and characteristic types and ranges; Tuya data point numbers and value types; Bambu top-level keys; Cast namespaces and app ids; webOS SSAP URIs), NetGuard counters, and the redacted log tail. Acceptance: contract tests that pair every adapter with a *known secret* (local key, access code, setup code, PIN, RTSP password, serial) and prove none of it appears in the report.
- **W2.2 Per-device "check this device"** (reachable, first reading, readings count, and for lights/plugs an opt-in toggle-and-restore), result included in the report. Only if W2.1 leaves room.

### W3. Independent verification without a device
- **W3.1 Wycheproof vectors** (Google/C2SP) for the hand-written Ed25519 and X25519, including malformed and low-order cases. Acceptance: all vectors pass or each disagreement is analysed and fixed. Cheap, local, truly independent.
- **W3.2 Desk-check the HomeKit client against independent accessory code** (HAP-python and HAP-NodeJS): `/pair-setup` path and method value, TLV details, headers, keep-alive and chunking. Resolves most of the "unconfirmed against a real ecobee" list.
- **W3.3 Interop against reference implementations** (HAP-python as a real HomeKit accessory to pair with; tinytuya, python-kasa, pychromecast and androidtvremote2 as real clients against our simulators). **Blocked on the owner's permission** to install those PyPI packages into an isolated throwaway environment (the sandbox's permission classifier denied it). This is the strongest verification available without hardware.
- **W3.4 UI behaviour tests**: add Compose's UI test library (test-only; entry in `docs/DEPENDENCIES.md`, checksums through the bootstrap workflow) and test what the screenshots cannot: the command bar by keyboard, optimistic tile switching and revert on failure, confirm-to-delete, scene strip, navigation. Acceptance: the tests run in CI and fail when the behaviour is broken (checked by deliberately breaking it once).

### W4. Code health behind the safety net
- **W4.1 Split `LanHome.kt`** (850 lines, many responsibilities) into focused classes (automations, adaptive lighting, away mode, scenes, ledger and notifications). Behaviour-preserving; the existing JVM tests are the net, so this is safe to do now. Acceptance: no test changed except imports/constructors, `LanHome` under about 400 lines.
- **W4.2 Backup and restore**: an encrypted, passphrase-protected export/import of devices, pairings, scenes, automations and settings, so losing the phone does not mean re-pairing everything. Acceptance: round-trip, wrong-passphrase, tamper and size-bound tests; the file contains no plaintext secret.

### W5. Decisions that are the owner's, with evidence prepared
- **Build on Home Assistant, continue, or hybrid.** Evidence so far is in the market notes: the device layer is a solved, maintained problem. The cheap experiment is for the owner to run Home Assistant's own integrations for the three or four devices that matter most and compare. Decision criteria and a recommended experiment are in `docs/reviews/2026-11-17-market-notes.md`. Nothing in this plan depends on the answer, because W1, W2 and W3 make any path safer.
- **Crypto**: keep the hand-written curves (now independently tested) or move to a reviewed library. Decide after W3.1 and W3.3 results.

## Order of work and rough effort
| # | Item | Effort | Verified by |
|---|---|---|---|
| 1 | W3.1 Wycheproof | S | JVM tests |
| 2 | W1.1 IP-change fix | M | JVM tests with moving simulators |
| 3 | W1.2 self-healing | M | JVM tests |
| 4 | W2.1 diagnostics + redaction tests | L | JVM tests, CI |
| 5 | W3.2 HomeKit desk-check | S | reading + tests |
| 6 | W3.4 UI behaviour tests | L (CI round trips) | CI |
| 7 | W4.1 split LanHome | M | existing JVM tests |
| 8 | W4.2 backup/restore | M | JVM tests |
| 9 | W3.3 interop with reference implementations | L | needs owner OK |

## What this plan deliberately does not do
New device families (ESPHome, Matter), peer sync, the headless hub, Windows Hello, Jazzer, more UI polish, and anything that needs a real device. They are in the backlog below and move up only on evidence.

## First hardware pass (for when the owner is ready)
Run `docs/HARDWARE_TEST.md` in this order: app lock, Govee, TV, Kasa, Bambu, ecobee, Google TV remote, cameras. After each device, press **Copy diagnostics** and send it with the step number that failed. W2 exists so that one report is enough.

## Backlog (after hardware results, in rough order)
Automation triggers (device events), HomeKit event subscriptions instead of polling, Android widgets and quick tiles, ESPHome, Tuya 3.4/3.5, peer sync or hub, Windows Hello.

## Status
| Item | Status |
|---|---|
| W3.1 Wycheproof | **done 2026-11-17**: all 210 Ed25519 and all 518 X25519 vectors agree, no code change needed |
| W1.1 IP-change fix | todo |
| W1.2 self-healing | todo |
| W2.1 diagnostics | todo |
| W3.2 HomeKit desk-check | todo |
| W3.4 UI behaviour tests | todo |
| W4.1 split LanHome | todo |
| W4.2 backup/restore | todo |
| W3.3 reference interop | blocked on owner permission |
