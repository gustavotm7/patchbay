# Hardware test checklist

Run these on your real phone and laptop. Each phase appends a section. Report failures with the step number
and, if possible, the session log (`patchbay_<platform>_<timestamp>.log`; logging arrives in Phase 1).

## Phase 0 — shells install and render the demo home

No real devices are contacted in Phase 0. The apps only render simulator data.

### Android (your phone)

| # | Step | Expected |
|---|---|---|
| A0.1 | Download the `patchbay-android` artifact from the latest green CI run. Install `app-android-debug.apk` (or sign the release APK per `docs/RELEASE.md`) | Installs. GrapheneOS shows no Play Services prompt |
| A0.2 | Settings → Apps → Patchbay → Permissions | On Android 17+, only "Nearby devices" (local network) is listed, and it isn't granted yet (requesting it arrives with real discovery in Phase 1). **No location, camera, microphone or contacts** |
| A0.3 | Open the app | Demo home floor plan, "Demo" tag, exposure strip "10 local · 4 cloud-only · 1 unknown". Dark or light follows the system theme |
| A0.4 | Take a screenshot; open Recents | The screenshot is blocked or black, and the Recents thumbnail is blank (FLAG_SECURE) |
| A0.5 | Tap "Workshop" on the plan, then "H2D" | Printer panel: layer stack, 47 %, two nozzle readouts, AMS swatches, Pause / Stop… |
| A0.6 | Tap "Stop…" | It changes to "Confirm stop" with a warning line. Nothing else happens (demo) |
| A0.7 | Back, back; open Hall → Thermostat; drag the heat handle up past the cool handle in Auto | The heat handle stops 2.0 °C below cool (deadband enforced) |
| A0.8 | Enable TalkBack; swipe through the plan | Each device reads as "<name>, on/off, Local…", and readouts include units ("degrees Celsius") |
| A0.9 | Settings → Accessibility → largest font size | Text scales; nothing overlaps badly. Note any clipping with a screenshot from a non-secure screen, or a description |
| A0.10 | Airplane mode with Wi-Fi off, then reopen | The app works identically (no network needed for demo) |

### Windows (your laptop)

| # | Step | Expected |
|---|---|---|
| W0.1 | Download `patchbay-windows-msi` and run the MSI | Per-user install, no admin prompt. SmartScreen may warn (unsigned) |
| W0.2 | Start Menu → Patchbay | Window opens at 1440 × 900 with nav rail, floor plan with device names, and Workshop inspector |
| W0.3 | Press Tab repeatedly | A visible 2 dp focus ring moves through the rail items, rooms and buttons |
| W0.4 | Click Hall → Thermostat in the inspector | Thermostat panel with vertical scale and setpoints |
| W0.5 | Windows Settings → Personalisation → Colours: switch light/dark, then restart the app | The theme follows the system setting |
| W0.6 | (Optional) Resource Monitor → Network | Patchbay.exe has **no** TCP connections |
| W0.7 | Uninstall via Settings → Apps | Removes cleanly. No leftover service, scheduled task or updater |

### Please also tell me

- Do the screens in `docs/design/screens/` (or the `design-screens` CI artifact) match your taste? The
  Control Room direction, the Plex typeface, and the night palette specifically.
- Anything that feels like a generic AI-made app.

## Phase 1 — Govee LAN (first real-device test)

Prerequisite: in the Govee Home app, open each light → Settings → turn on **LAN Control**. Not every model has it.
Phone and laptop must be on the same Wi-Fi/VLAN as the lights. Multicast must not be blocked; "AP/client
isolation" on guest networks breaks it.

| # | Step | Expected |
|---|---|---|
| G1.1 | Android: open Patchbay → "This network" tab | On Android 17+, a "Nearby devices" permission prompt. Allow it |
| G1.2 | Tap **Scan** | Within about 2 s, each LAN-enabled Govee light appears as "Govee H…", with On/Off or % |
| G1.3 | Tap a light → toggle Power | The light switches within about 0.5 s. The list updates within 5 s |
| G1.4 | Drag on the pad (left = warm 2000 K, right = cool 9000 K, top = bright) | The light follows. Readouts show % and K |
| G1.5 | Tap the hue strip (RGB models) | The light shows that colour; the readout switches to RGB |
| G1.6 | Unplug the light, wait about 20 s | Status goes "No reply", then "Offline". Replug: it recovers by itself (polling back-off ≤ 60 s) |
| G1.7 | Windows: open Patchbay (Inventory is the start page) → Scan | Windows Firewall may ask about Patchbay. Allow **Private networks only**. The same lights appear |
| G1.8 | Run Home Assistant's Govee LAN integration on the same PC | Patchbay shows "Govee needs UDP port 4002…" (only one app per machine can listen) |
| G1.9 | Close and reopen the app; **don't** scan | Your lights are listed immediately (from the encrypted DB) and turn "On/Off" within about 5 s |
| G1.10 | After a scan, look at "Everything else that answered" | Chromecast/Google TV, the LG TV, printers and other mDNS/SSDP devices are listed with hints like "Cast: local media control". Unidentified ones are flagged ▲ |
| G1.11 | Exposure → **Copy report**, paste into a note | A Markdown report with your devices, and OpenWrt rules only for lights Patchbay controls. Nothing was changed on your router |
| G1.12 | Windows: look in `%LOCALAPPDATA%\Patchbay\data\` | `patchbay.db` (an encrypted file, opaque in a text editor) and `dek.wrapped` (DPAPI-protected). No plaintext device list anywhere |
| G1.13 | Network screen shows "Encrypted storage unavailable…" | Should **not** happen. If it does, send the log line. The app still works but saves nothing |

Please report:
- Your Govee model numbers, and which ones didn't appear.
- Rough response times.
- Anything that appeared in the log: `Android/data/…` isn't accessible, so on Windows look in
  `%LOCALAPPDATA%\Patchbay\logs\`.

## Phase 1 — LG webOS TV

Prerequisite: the TV is on, on the same network, and Settings → General → Devices → External Devices →
"LG Connect Apps" (or "Mobile TV On") is enabled. Names vary by webOS version.

| # | Step | Expected |
|---|---|---|
| T1.1 | Scan | The TV appears as "LG WebOS/…", with a **Pair** button |
| T1.2 | Tap **Pair** | A "Check LG's key" panel with a fingerprint (8 groups of hex). Tap **Trust** |
| T1.3 | Look at the TV | A prompt asks to allow "Patchbay". Accept with the remote. Patchbay's panel then disappears, and within about 5 s the TV shows "On" with its volume |
| T1.4 | Open the TV → volume ±1 / ±5, Mute | The TV follows immediately |
| T1.5 | Tap an input (e.g. HDMI 2) | The TV switches input |
| T1.6 | Turn off | The TV goes to standby. Patchbay shows "No reply", then "Offline" (turning on from Patchbay isn't supported yet) |
| T1.7 | Restart Patchbay with the TV on | The TV reconnects **without** a new prompt (stored key, same pin) |
| T1.8 | (If the TV's firmware updates and its key changes) | Patchbay shows a trust warning rather than silently reconnecting. Re-pair to accept the new key |

Please report: the TV model and webOS version; whether T1.3's prompt appeared; any step that failed, with the
time it happened (for the log).
