# TP-Link Kasa plugs and switches

Tier **L** (local) on older firmware; newer firmware is **LS-with-account** and is not supported yet.

## Transport

- **Legacy protocol:** JSON on TCP 9999 and UDP 9999.
  - TCP frame: 4-byte big-endian length, then the JSON obfuscated with an XOR autokey stream, initial key 171
    (each output byte becomes the next key byte). UDP datagrams carry the same stream with no length prefix.
  - This is obfuscation, not encryption, and there is no authentication. Anyone on the LAN can switch the plug.
- **Discovery:** UDP broadcast of the scrambled `{"system":{"get_sysinfo":{}}}` to 255.255.255.255:9999. Every plug answers
  the sender's address and port with its sysinfo.
- **KLAP (newer firmware):** HTTP on port 80 with a handshake keyed from the owner's TP-Link **cloud account** credentials.
  Plugs that have only ever been set up this way don't answer on 9999. Supporting KLAP means storing a hash of the
  TP-Link login on the device. That touches invariant 3 and needs an ADR; until then such plugs are not found by the Kasa
  adapter and show up in the census as unidentified or "TP-Link".

## Messages used

| Purpose | Request | Reply |
|---|---|---|
| Identity and state | `{"system":{"get_sysinfo":{}}}` | `{"system":{"get_sysinfo":{"err_code":0,"model":…,"type":"IOT.SMARTPLUGSWITCH","alias":…,"relay_state":0|1,"deviceId":…,"sw_ver":…}}}` |
| Switch | `{"system":{"set_relay_state":{"state":0|1}}}` | `{"system":{"set_relay_state":{"err_code":0}}}` |

## Scope

- Accepted: single-outlet plugs and switches (`type` = `IOT.SMARTPLUGSWITCH`). Capability: Power only.
- Skipped: bulbs and light strips (a different message set), and multi-outlet strips (HS300; outlets need child ids). These
  answer discovery and are ignored, not treated as malformed.
- Not used: energy monitoring, schedules, countdown rules, cloud binding, firmware update, Wi-Fi settings.

## Trust

- The device address is the network source address; the `deviceId` in the payload is only an identifier (40 hex characters,
  or the MAC as a fallback).
- Parser: replies are size-capped at 4096 bytes, JSON-bounded, `relay_state` must be the integer 0 or 1, and text fields
  are length-bounded. Out-of-spec replies are rejected, never clamped.

## Sources

- Public reverse-engineering write-ups of the 9999 protocol (the XOR autokey scheme) and the `python-kasa` documentation,
  which describes legacy versus KLAP devices and notes that KLAP devices need TP-Link account credentials. No code was copied.
