# Getting a Tuya local key (for Lepro and other Tuya-based bulbs)

Patchbay never contacts Tuya (ADR 0007). The one thing it can't learn from the bulb is its **local key**, which Tuya only hands out through its
cloud. You fetch it once, yourself, outside Patchbay, then type it in. These steps are from community guides and change over time; if a screen
differs, the tool named below (`tinytuya`) has maintained instructions.

## What you need to end up with

1. **Device ID**: 20 to 24 letters and numbers.
2. **Local key**: exactly 16 characters.
3. **The bulb's LAN IP address** (router device list). Give it a fixed address (DHCP reservation) so it doesn't change.
4. **The protocol version**, which must be **3.3**. Patchbay doesn't support 3.4 or 3.5 yet.

## Option A: tinytuya wizard (most common)

1. Set the bulb up in its app (Lepro / LampUX, or Smart Life) as normal.
2. Make a free account at the Tuya IoT Platform (iot.tuya.com), create a **Cloud project**, and link your app account to it (the project's
   "Devices" tab → "Link App Account" → scan the QR code with the app).
3. On any computer with Python: `pip install tinytuya`, then `python -m tinytuya wizard`. It asks for the project's Access ID and Secret and
   writes `devices.json` with each device's **id**, **key** (the local key) and **ip**. Run `python -m tinytuya scan` to see each device's
   protocol **version** on your network.
4. Keep the Access Secret private; delete the cloud project afterwards if you like. The local key stays valid until you remove the bulb from
   the app and add it again (then it changes, and you repeat this).

## Then, in Patchbay

Network screen → **Add by address** → "Tuya bulb" → choose **Bulb (standard)** → the IP and the device ID → Add → open it → **Pair** → paste
the local key. If power isn't detected, try **Bulb (older)**. Neither choice is risky: Patchbay only writes power, brightness, colour
temperature and colour data points.

## If it doesn't work

- "may use a protocol version Patchbay doesn't support": the bulb is 3.4 or 3.5. Tell me; support is a follow-up.
- "didn't accept the local key": the key or device ID is wrong, or the bulb was re-added to the app after you fetched them.
- Only one connection works at a time on many bulbs; close other apps or integrations talking to the same bulb.
