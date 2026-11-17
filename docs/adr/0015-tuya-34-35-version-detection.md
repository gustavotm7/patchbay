# 0015 — Tuya local protocols 3.4 and 3.5, and finding the version at pairing

Status: Accepted (feature backlog, built on simulators) · Date: 2026-11-17

## Context

The Tuya adapter spoke only protocol 3.3. Which version a device uses depends on its firmware, and newer devices (and firmware updates to older ones) use 3.4 or 3.5. The owner's Lepro bulbs and
Lefant vacuum are Tuya devices whose versions are unknown, and a version mismatch looks like a dead device: the device simply does not answer a frame it does not understand. This was the most
likely reason for a first real-device test to fail without a useful message.

## Decision

- **Speak 3.3, 3.4 and 3.5** with JCA only (AES-ECB, AES-GCM, HMAC-SHA256: no new dependency, all on Android 12 and the JVM). The formats are in `docs/protocols/tuya.md`.
- **One job per connection**, as before: connect, agree a session key if the version has one (three short messages that also prove the device holds the local key), do the query or the control,
  hang up. Slightly more traffic than a long-lived session, but bulbs accept very few connections and this keeps a failure from leaving a half-open session.
- **Find the version when pairing**, by trying 3.3, 3.4 and 3.5 in that order and keeping the first that answers; store it as a third line of the pairing secret. Older secrets without it are
  3.3. The owner is not asked, because most people do not know and cannot easily find out.
- **Say what probably happened when nothing answers.** A device that closed the connection or stayed silent in every version almost always has a different local key (it changes whenever the device
  is removed from the Tuya app and re-added), so that is what the message says; refusals and non-Tuya replies keep their own messages.
- **Every frame from the device is untrusted**: prefix, length (bounded), suffix and HMAC or GCM tag are checked before anything inside is read, and a wrong key never gets as far as the data.
- The vacuum's controls stay unbuilt: they need the model and its data-point list (ADR 0007).

## Verification

The simulators have their own 3.4 and 3.5 device sides. Frames and session keys produced by the tinytuya library are pinned as known answers in a unit test, every damaged byte of a frame is
rejected, and tinytuya as a client completes the handshake, reads and writes against our simulators. None of this involves a real device.

## Consequences

- Pairing a device that is not reachable, or whose key is wrong, takes up to three connection timeouts instead of one.
- A firmware update that changes the version makes a paired device stop answering; forgetting and adding it again detects the new version. Re-detecting automatically after repeated failures is a possible later addition.
- 3.4 uses AES-ECB inside an HMAC: the protocol's design, accepted because this only runs on the LAN behind `NetGuard`. 3.5 is the better scheme of the two.

## Alternatives considered

- Asking the owner for the version: most owners would not know it.
- Reading the version from Tuya's UDP announcements (6666/6667, and 7000 for 3.5): needs listening sockets and broadcast handling for a one-time step. Left out for now.
- A third-party Tuya library: a large dependency with its own network behaviour (invariant 2); the protocol is small.
