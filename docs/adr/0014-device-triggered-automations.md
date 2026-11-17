# 0014 — Device-triggered automations: "when a device does X, run a scene"

Status: Accepted (feature backlog, built on simulators) · Date: 2026-11-17

## Context

Automations ran a scene at a clock time or at sunrise or sunset. The owner's devices report things worth reacting to: a printer finishing, a TV starting to play, a lamp or plug switching
on or off. Reacting to them is the most requested kind of automation in any home system, and it is also where automations go wrong: a scene changes devices, those changes are events, and
the events start more scenes.

## Decision

- An automation has either a schedule or a **trigger**: one device and one event from a fixed list: turns on, turns off, print finishes, print fails, starts playing, stops playing.
  A device offers only the events its capabilities can produce (`DeviceEvent.availableFor`). The action is still a scene, so everything a scene may not do (printer heating, G-code, the
  Kidde alarm, anything outside lights, plugs, thermostat and TV volume) is still impossible here.
- Events come from **comparing two consecutive reports** from the same device (`EventDetector`), never from a device's own claim of "an event happened":
  - the first report after Patchbay starts is a baseline, never an event (a printer that was already finished is not news);
  - only reports from a device that is answering count, and a device that was out of reach is compared with what it last said while reachable, so a plug that lost power and came back
    does not look like "turned on";
  - buffering is not a playback change, so a stalled stream resuming is not "started playing";
  - a print counts as finished or failed only after it was seen preparing, printing or paused (the same rule the notification uses).
- **Automations never start each other.** While an automation's scene runs, and for 30 seconds after it ends, device events start nothing and Activity says "Not run: … right after an
  automation ran". This is one rule instead of loop detection: two automations that undo each other, or a scene that changes the device that triggers it, cannot run away. A scene run
  by hand can still start automations. Automations waiting for the same event all run (the window is checked once per event).
- A trigger names its device by id. If the device is forgotten the automation stays in the list, marked "a removed device", and does nothing; it is dropped only if its saved event name is
  one this version does not know (a damaged or newer file).
- Triggers are saved in the same encrypted `automations` setting, so backups (ADR 0013) carry them with no format change. Older files read as time automations.
- They run only while Patchbay runs (open, in the tray, or watching in the background on Android), like every other automation.

## Consequences

- Latency is the device's polling interval (a few seconds for lights and the thermostat) plus the scene's own pacing. Nothing here is instantaneous, and nothing is meant to be: this is not
  a safety system.
- HomeKit event subscriptions would make the thermostat's events faster but are not needed for any event listed here (it offers none yet).
- The 30-second window can swallow a legitimate second trigger (a print finishing 20 seconds after a TV automation ran). It is visible in Activity, and the safe failure is "did not run".

## Alternatives considered

- Per-automation cooldowns and loop detection by dependency graph: more code and more states to explain than one global rule. The global window is the same rule a person would apply
  ("don't react to what the house just did to itself").
- Conditions (only after sunset, only when away): a worthwhile later addition. Left out so the first version has one thing to test on real devices.
- Letting a trigger run any command, not only a scene: would bypass the scene allowlist.
