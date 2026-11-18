# 0017 — Automation conditions: a daily window and home or away

Status: Accepted (feature backlog, built on simulators) · Date: 2026-11-18

## Context

Automations start a scene at a clock time or sunrise or sunset (ADR 0014 added "when a device does something"). Real use needs a restriction on top: "when the TV starts playing, run
Movie night, but only after dark", "when the printer finishes, light up the workshop, but only during the day", "the morning scene, but not while we are away".

## Decision

- An automation may carry one **condition** with two optional parts: a **daily window** and **home or away**. Whatever is set must hold at the moment the automation would run.
- The window runs from one point of the day up to (not including) another. A point is a clock time or sunrise or sunset, shifted by up to three hours (`sunset+30`, `sunrise-15`).
  A window that ends before it starts wraps past midnight, so *after dark* is simply sunset until sunrise and *daytime* is sunrise until sunset. Clock times are in the owner's time zone
  (summer time included). Sun times come from the owner's typed home location (the same NOAA maths as sun automations); saving a sun-based window without a location is refused, and
  a sun time that cannot be worked out later (the location was removed, or no sunset near the poles) makes the automation not run and say so, rather than guess.
- **Home or away is the Away mode switch.** Patchbay has no location permission and no presence detection (invariant 8), so "at home" means Away mode is off and "away" means it is on.
- It is checked at the moment of running, for both kinds of automation (the clock's tick and a device event). When it does not hold nothing runs and Activity says why: "Not run: it only
  runs after dark", "Not run: Floor lamp turns on, but it only runs when you are at home".
- It is saved with the automation in the same encrypted setting (so backups carry it); files from before this read as no condition, and an automation whose stored window cannot be read back is switched off (it must never turn into "always"; review finding L4, ADR 0018), while an away flag alone is kept.
- The form offers *Any time, After dark, Daytime, Between* (two text boxes taking `18:00`, `sunset`, `sunset+30`) and *Home or away, Only at home, Only away*.

## Not included

- Conditions on what a device is doing ("only if the TV is on"), several windows, weekday-specific windows, and combining conditions with "or" or "not". The first version has one window and
  one flag so there is little to explain and little to get wrong.
- Presence from the phone's location or Wi-Fi name (no location permission), or from other devices on the network. THREAT_MODEL §5 sketches a LAN-identity way; it is not built.

## Consequences

- Sunrise and sunset are good to a few minutes. A window that wraps midnight uses the same day's sun times for both ends, which is off by a minute or two at most.
- A device that flaps during a restricted period adds a "Not run" line to Activity for each event (the list is bounded).
