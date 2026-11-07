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
| W0.7 | Uninstall via Settings → Apps | Removes the app cleanly. No leftover service, scheduled task or updater. Your data stays in `%LOCALAPPDATA%\Patchbay` until you delete that folder |
| W0.8 | Portable zip (`patchbay-windows-portable`): right-click the zip → Properties → Unblock, extract, run `Patchbay\Patchbay.exe` | Runs with no install and no admin prompt. A `PatchbayData` folder appears beside the exe. Delete the whole `Patchbay` folder to remove everything |

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
| T1.6 | Turn off | The TV goes to standby. Patchbay shows "No reply", then "Offline" |
| T1.6b | Tap **Turn on** (with "Turn on via Wi-Fi" enabled on the TV) | The TV wakes within a few seconds and Patchbay shows "On" again |
| T1.7 | Restart Patchbay with the TV on | The TV reconnects **without** a new prompt (stored key, same pin) |
| T1.8 | (If the TV's firmware updates and its key changes) | Patchbay shows a trust warning rather than silently reconnecting. Re-pair to accept the new key |

Please report: the TV model and webOS version; whether T1.3's prompt appeared; any step that failed, with the
time it happened (for the log).

## Phase 1 — Bambu H2D

Prerequisite: LAN Only Mode + Developer Mode on (see `docs/protocols/bambu.md`), with the access code at hand.

| # | Step | Expected |
|---|---|---|
| B1.1 | Scan | "Bambu Lab H2D" appears with **Pair**. If it doesn't, check the census list for "Bambu printer: local in LAN mode", and tell me (manual add is a follow-up) |
| B1.2 | Pair → **Trust** the fingerprint → enter the 8-character access code (masked) | Pairing completes. A wrong code says "the device rejected the access code" |
| B1.3 | Open the printer during a print | Layer stack, %, layer x / total, time left; both nozzles, bed and chamber temperatures; AMS slots with real filament colours |
| B1.4 | Pause, then Resume | The printer pauses and resumes. The status tag follows within about 1 s |
| B1.5 | Chamber light toggle | Both chamber lights switch |
| B1.6 | Stop… → Confirm stop (only on a print you're willing to cancel) | The print is cancelled |
| B1.7 | Restart Patchbay | The printer reconnects without asking for the code again |
| B1.8 | Only if B1.1 failed: **Add by address** (on the network screen) → the printer's IP and serial number → Add → Pair | The printer appears with a Pair button and pairs as in B1.2 |

## Phase 1 — App lock

| # | Step | Expected |
|---|---|---|
| L1.1 | Android: open Patchbay | "Locked", then the system prompt (fingerprint/face or your PIN). Unlock → the app |
| L1.2 | Android: switch away for more than 1 min, come back | Locked again. Under a minute: still unlocked |
| L1.3 | Windows: first start | "Create a PIN (4–12 digits)", then confirm it |
| L1.4 | Windows: restart, or leave the window unfocused for more than 1 min | Enter your PIN. Three wrong attempts → a growing wait |

## Phase 1 — TP-Link Kasa plugs

Prerequisite: the plug is set up in the Kasa app and on the same Wi-Fi. Plugs on newer firmware (KLAP) don't answer the
legacy protocol and won't appear. That's expected for now; tell me the model if so.

| # | Step | Expected |
|---|---|---|
| K1.1 | Scan | Each legacy-firmware plug appears as "TP-Link <model>" (kind: plug) with its Kasa name |
| K1.2 | Open a plug → **Turn on**, then **Turn off** | The relay clicks within about 1 s. The tag follows within 5 s |
| K1.3 | Switch the plug with its physical button or the Kasa app | Patchbay shows the new state within about 5 s |
| K1.4 | Unplug it, wait about 20 s | "No reply", then "Offline". Replug: it recovers by itself |
| K1.5 | Restart Patchbay without scanning | The plug is listed immediately and shows its state within about 5 s |
| K1.6 | A plug missing from the scan | Network tab → "Last scan" shows what each system found. Then Devices → **Add by address** → "TP-Link Kasa plug" → the plug's IP. It either appears, or says it has newer firmware (needs a TP-Link login), or that nothing answered at that address |

Please report: plug models and firmware versions (Kasa app → device settings → device info), and which ones didn't appear.

## Phase 1 — Tuya bulbs (Lepro LampUX and others)

Prerequisite: the bulb's device ID, local key, IP and protocol version (3.3 only): follow `docs/TUYA_KEYS.md`.

| # | Step | Expected |
|---|---|---|
| U1.1 | Network screen → **Add by address** → "Tuya bulb" → **Bulb (standard)** → IP + device ID → Add | A "Tuya bulb-v2" entry with a Pair button |
| U1.2 | Open it → **Pair** → paste the local key | Pairing completes; within about 6 s the bulb shows its on/off state |
| U1.3 | Wrong key (one character off) | "the device didn't accept the local key" |
| U1.4 | Toggle power, drag brightness | The bulb follows within about 1 s |
| U1.5 | Colour temperature pad: warmest and coolest ends | The bulb goes from warm to cool white. Tell me the real range if it looks different from 2700 to 6500 K |
| U1.6 | Colour: pick red, green, blue | The bulb shows them. If not, say what happens (and whether "Bulb (older)" behaves differently) |
| U1.7 | Change the bulb from its own app | Patchbay follows within about 6 s |
| U1.8 | Restart Patchbay | The bulb is listed and works without asking for the key again |

Please report: bulb model, protocol version (from `tinytuya scan`), and which steps differed.

## Phase 1 — eufy cameras (RTSP)

Prerequisite: in the eufy app, open the camera → Settings → turn on **RTSP stream** (also called NAS/RTSP) and set a
username and password. Note the camera's IP address (your router's device list, or the eufy app's device info).
Not every model has RTSP; HomeBase-connected cameras expose it on the HomeBase's address instead.

| # | Step | Expected |
|---|---|---|
| E1.1 | Network screen → **Add by address** → "RTSP camera (eufy)" → the camera's IP and a name → Add | A camera entry with a "Needs login" tag |
| E1.2 | Open it → **Enter login** → username, then password | Pairing completes and the tag becomes "Stream up" |
| E1.3 | Enter a wrong password | "the camera rejected the username or password" |
| E1.4 | Open `rtsp://<ip>/live0` in VLC and sign in | The camera's video plays (this checks the path and login outside Patchbay) |
| E1.5 | Unplug the camera, wait about 1 min | "No reply", then "Offline". Replug: it recovers by itself |
| E1.6 | Restart Patchbay | The camera is listed with its status and no new login prompt |
| E1.7 | Android: open the camera → **Watch** | Live video appears in the panel within a few seconds. **Stop** ends it. Windows has no in-app video yet |
| E1.8 | While watching, open Settings → Apps → Patchbay → data usage, or look at your router's client list | Traffic goes only to the camera's LAN address, over one TCP connection (video is carried inside it) |
| E1.9 | Screenshot, then Recents while video is up | Blocked or blank (whole-app screenshot blocking) |

If Watch shows a black picture or an error, tell me the camera model: a camera that only streams RTSP over UDP won't play (TCP only, ADR 0008).

Please report: camera and HomeBase models, whether the stream path was `live0`, and any error text.
If the path differs, tell me the path VLC needed.

## Phase 1 — Rooms and scenes

| # | Step | Expected |
|---|---|---|
| S1.1 | Open a light or plug (tap › on its tile) → **Room** → type "Living room" → Add room | The device moves under a "Living room" heading on the Devices tab. Other devices stay under "Other devices" |
| S1.2 | Put a second device in the same room, a third in a new room | Tiles group under each room heading, sorted by name |
| S1.3 | Restart Patchbay | Rooms and assignments are still there |
| S1.4 | Set two lights how you like them (on, a brightness, a colour or white). Scenes → **New scene** → name it → tick them → Save | The scene appears with its device count |
| S1.5 | Change those lights, then tap **Run** | Both go back to the saved state within about a second |
| S1.6 | Switch one light off at its switch or app, run the scene | The scene still runs; tell me if it reports a problem |
| S1.7 | Restart, then run the scene | It's still there and still works |
| S1.8 | Delete the scene | It's gone for good |

Scenes only ever hold lights and plugs. A TV, printer, camera or lock can't be put in one.

## Phase 1 — Chromecast, Google TV and Cast speakers

Prerequisite: the device is on, on the same network (not a guest network), and something is set up in the Google Home app as usual.

| # | Step | Expected |
|---|---|---|
| C1.1 | Scan | Each Cast device appears as a tile (Chromecast, Google TV, speakers) named as in the Google Home app, with "Tap to pair". Network → Last scan shows "Cast and Google TV: N found" |
| C1.2 | Tap the tile → check the fingerprint → Trust | No code and no prompt on the TV. Within a few seconds the tile shows its state ("Idle", or "Playing · title") |
| C1.3 | Open it → volume −5 / +5 / mute | The device's volume follows within about a second, and the readout updates |
| C1.4 | Start a video or song from your phone to the device | The tile and panel show "Playing" with the title; progress isn't shown yet |
| C1.5 | Play / Pause / Stop in Patchbay | The video or song pauses, resumes and stops |
| C1.6 | With nothing playing, press Play | Patchbay says nothing is playing (no error on the device) |
| C1.7 | Restart Patchbay | The device reconnects without asking to pair again (same fingerprint) |
| C1.8 | (If a firmware update changes its key) | Patchbay shows a trust warning rather than silently reconnecting; re-pair to accept |

Please report: model (Chromecast gen, Google TV, speaker), whether C1.1 found it, and what a Cast device's volume and title looked like.

## Phase 1 — Attention, removal and activity

| # | Step | Expected |
|---|---|---|
| N1.1 | Unplug a paired light or plug, wait about 30 s | The Devices tab shows a "Needs attention" card naming it ("No reply for a while"). Opening it says it's offline, with no controls. Plug it back in: the card and notice go away by themselves |
| N1.2 | Activity tab | "Went offline" and "Back online" lines with local times. Switching a light from Patchbay says "(from Patchbay)"; switching it at its own switch or app says "(at the device or another app)" |
| N1.3 | **Copy as CSV**, paste into a note | `time_utc,device,event` header, then one quoted line per entry, newest first |
| N1.4 | Restart Patchbay | The activity history is still there |
| N1.5 | **All off** (Scenes card) | Every light and plug that was on switches off. It says how many ("Switched off 3") or "Everything is already off" |
| N1.6 | Open a device → **Remove…** → **Confirm remove** | It disappears and its saved pairing is gone. Scan again: it comes back, and a TV, printer or Cast device asks to pair again |
| N1.7 | (Security) A paired TV, printer or Cast device whose key changes, for example after a factory reset | A red "Security alert" page replaces its controls and the Devices tab flags it first. Patchbay doesn't talk to it until you remove it and pair again, comparing the new fingerprint |

## Phase 2 — Tray mode and automations (Windows)

| # | Step | Expected |
|---|---|---|
| R1.1 | Settings → Background → turn on "Keep running in the tray…". Close the window with the X | The window disappears, a teal icon stays in the tray (check the hidden-icons arrow). The app is still running |
| R1.2 | Click the tray icon, or right-click → Open Patchbay | The window returns **locked** (enter your PIN). Devices are still listed and current |
| R1.3 | Tray menu → All off / Lock now | Lights and plugs that were on switch off; Lock now locks the window |
| R1.4 | Tray menu → Quit | The icon disappears and the process exits (Task Manager) |
| R1.5 | With the setting off, close the window | The app quits as before |
| R1.6 | Make a scene, then Automations → New: pick the scene, a time two minutes ahead, all days. Leave the window open | At that minute the scene runs once. Activity shows "Automation: …" |
| R1.7 | Repeat with the window closed to the tray | It still runs |
| R1.8 | Turn the automation's switch off, wait for its time | Nothing runs |
| R1.9 | Quit Patchbay across an automation's time, reopen | It does not run late (by design: no catch-up) |
