# 0011 — Android: opt-in background watching and alerts

Status: Accepted (owner asked for Android notifications and background running, then approved this ADR: "approve ADR 0011, keep going", 2026-11-08) · Date: 2026-11-08

## Context

Automations, away mode, adaptive lighting and device alerts run in-process. On Android the process is killed or frozen soon after the screen goes off,
so none of them work unless the app is on screen, and nothing can notify the owner. Invariant 8 listed POST_NOTIFICATIONS only as "Phase 2" and had no foreground service.

## Decision

- A foreground service (`WatchService`, type `specialUse`) keeps the process alive **only while the owner has switched on "Keep watching in the background"** in Settings.
  It is off by default and does not start after a reboot (no RECEIVE_BOOT_COMPLETED).
- New permissions: `POST_NOTIFICATIONS` (runtime on Android 13+; refusing it only silences alerts), `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE`.
  Nothing else: no wake lock, no location, no new network access. The service does the same LAN polling the app does.
- The persistent notification is minimum importance, hidden on the lock screen, and has a Stop button. Alerts (a device stopped answering, a print finished or failed,
  a device presented a different security key) use a high-importance channel; the lock-screen version is the generic "Patchbay alert", because the text names devices.
- The service is not exported. Notification taps open the main activity (immutable PendingIntents). The app lock still gates the UI.

## Alternatives considered

- WorkManager periodic jobs: 15-minute minimum, no live polling, automations would run late. Rejected.
- A boot receiver to restart watching: useful, but it widens the permission list and starts something after a reboot without the owner opening the app. Left for a later decision.
- `dataSync` service type: capped at six hours per day on Android 15. `specialUse` has no such cap.

## Consequences

- Battery: polling continues with the screen off. Doze may stretch intervals; automations are therefore best-effort at the minute level on a phone left idle. The Windows tray build and a future hub are the reliable hosts.
- Android shows the foreground service in its "active apps" list; that is intended.
- If a store distribution is ever wanted, `specialUse` needs a Play declaration; sideloaded builds don't.
