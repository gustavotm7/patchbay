# Troubleshooting: a device doesn't show up

Open **Network → Last scan**: it shows what each system found and what to try. Then **Network → Copy log** copies the redacted log if you want to send it.

## Govee lights are never found (Windows)

Govee lights answer on UDP port **4002**, which Windows Firewall blocks by default for new programs.

1. In the Govee Home app, turn on **LAN Control** for each light (Settings → LAN Control). Not every model has it.
2. Make your Wi-Fi a **Private** network: Settings → Network & internet → Wi-Fi → your network → **Private network**. A new Wi-Fi is often "Public", and the firewall prompt's "Private networks" choice then doesn't apply.
3. Allow Patchbay through the firewall. The first time Patchbay listens, Windows asks; choose **Private networks**. If you dismissed it, open *Windows Security → Firewall & network protection → Allow an app through firewall* and tick Patchbay for Private. Or, in an **administrator** PowerShell, allow just that port:

   ```powershell
   New-NetFirewallRule -DisplayName "Patchbay Govee" -Direction Inbound -Protocol UDP -LocalPort 4002 -Profile Private -Action Allow
   ```
4. Scan again. If the Network tab still says none found, a VPN or a guest/"AP isolation" Wi-Fi may be in the way: the phone, PC and lights must be on the same normal network.

## A Kasa plug is missing

Use **Devices → Add by address → TP-Link Kasa plug** with its IP. It will appear, or say it has newer firmware (needs a TP-Link login, not supported yet), or that nothing answered at that address.

## The TV or printer shows "Tap to pair"

That's normal: tap it. The TV asks you to confirm a fingerprint and then to accept a prompt on the TV with its remote. The printer asks you to confirm a fingerprint and then for its 8-character access code (printer screen → Settings → LAN Only Mode). Cancelling is fine and leaves nothing behind.

## Cameras and Tuya bulbs never appear in a scan

They don't announce themselves. Use **Add by address**. For Tuya bulbs see `docs/TUYA_KEYS.md`.
