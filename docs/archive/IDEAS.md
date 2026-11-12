# Ideas from other smart homes

Researched 2026-11-07 from community posts and product write-ups (practitioner opinion, not standards). What we took, and what's still open.

## Adopted

| Idea | Seen in | In Patchbay |
|---|---|---|
| One "needs attention" summary, one tap from the main screen. Users complained loudly when SmartThings removed its offline-devices tile | [SmartThings community](https://community.smartthings.com/t/smartthings-ios-app-version-1-7-51-30-september-2026/311188), [Selora health spec](https://gitlab.com/groups/selorahomes/products/-/epics/28) | "Needs attention" card on Devices: a changed security key first, then devices that stopped answering |
| No dead buttons: an offline badge instead of controls that do nothing | developer write-ups on unreachable devices | A device that doesn't answer shows an "Offline" notice instead of its controls |
| Wait before calling something offline, to avoid alert fatigue | [SmartThings battery/offline alerts](https://blog.smartthings.com/smartthings-updates/hub-offline-and-low-battery-alerts/), Selora | Already true: three misses before "Offline"; the Activity history only records real changes |
| Scenes for one-tap changes; "kill all the lights when we leave" is among the most useful automations | [Home Assistant "most useful automation" thread](https://community.home-assistant.io/t/what-is-your-most-useful-automation/648543), Apple Home | Scenes (from the current state) and an **All off** button |
| An automation trace / history of what happened and why | Home Assistant traces, Apple Home activity | **Activity** screen: what changed, when, and whether it came from Patchbay or from the device or another app. Copy as CSV |

## Candidates (not built)

| Idea | Why it's interesting | What it needs |
|---|---|---|
| ~~**Adaptive lighting**~~ built: colour temperature follows the day | One of the few features people never turn off ([Apple Home](https://www.macworld.com/article/3563913/homekit-in-ios-14-support-for-facial-recognition-and-adaptive-lighting.html)) | The Windows tray mode now exists, so this can be built on the automation scheduler. Time-of-day curve per light, off by default |
| ~~Away mode~~ built (manual toggle, all off, imitation). Still open: **Home / Away / Sleep modes** driving other things, including fake occupancy while on holiday | Top answers in the Home Assistant thread | Needs an automation engine and presence. No location or SSID by design: presence could be "your phone is on the home Wi-Fi", which is already visible without reading the SSID |
| **Automations: triggers** (when a device changes, sunset) | Time-of-day scenes are built; triggers aren't | Sunset needs a typed home location (never read from the phone) |
| **Suggested automations** when you add a device (Apple Home suggests daily adaptive lighting) | Lowers the barrier for new users | After automations exist |
| **Quiet hours and battery thresholds** for alerts (SmartThings: 15 % and 5 %, daytime only) | Avoids 3 a.m. notifications | Notifications (Phase 2) and battery sensors |
| **Energy per plug** (Kasa emeter plugs report watts) | The Home Assistant energy dashboard is a favourite | An emeter-capable plug; yours (HS103) doesn't report it |
| **Widgets / quick controls** (Control Centre favourites) | Fast access without opening the app | Android tiles and widgets (Phase 2) |
| **Favourites** pinned to the top | Apple Home | Small; wait for real-use feedback |

## Deliberately not copied

- Cloud accounts, remote access, voice assistants, and location-based arrival ("your ETA is announced at home"): outside the privacy rules without an ADR.
