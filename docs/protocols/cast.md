# Cast (Chromecast) and Android TV Remote v2

Status: 2026-11-07. **Cast v2 adapter built** (`core/.../adapter/cast/`): volume, mute, play, pause, stop, current app and now-playing, over
TLS 8009 with a trust-on-first-use key pin; devices are found through the mDNS census. Not built: Android TV Remote v2 (remote keys for Google TV),
anything that launches apps or casts media. Not yet tried on a real device (HARDWARE_TEST C1.x).

**Android TV Remote v2 is built too** (`core/.../adapter/androidtv/`, HARDWARE_TEST A1.x): field numbers, framing (varint length), the pairing secret (SHA-256 over the client and TV RSA modulus and exponent plus the last two bytes of the 6-character code; the first digest byte must equal the first code byte), the feature bits and the key codes come from louis49/androidtv-remote (MIT) and tronikos/androidtvremote2 (Apache-2.0), read on 2026-11-07. The client certificate is RSA 2048, self-signed, SHA-256. Both protocols are tier **LS** (local, with a one-time trust step).
Findings come from web search of community sources, not from vendor documentation; Google doesn't publish the protocols.

## Cast v2 (Chromecast, Google TV Streamer, Cast-enabled TVs and speakers)

| Piece | Detail |
|---|---|
| Discovery | mDNS `_googlecast._tcp` (already seen by the census) |
| Transport | TLS on TCP **8009**; messages are a 4-byte big-endian length then a protobuf `CastMessage` |
| Channels | JSON over named namespaces: connection, heartbeat (PING/PONG), receiver (volume, launch/stop apps, status), media |
| Authentication | Optional for senders. The device can prove itself on the `deviceauth` namespace with a signature and a certificate chain to a Google device CA. Official SDKs check it; community senders usually skip it |
| Local control | Volume, mute, app launch, media play/pause/seek/stop. No cloud needed once the device is on the LAN |

### Does the certificate rotate? (the question ADR 0005 left open)

- The **TLS leaf certificate on 8009** is per-device and self-issued; I found no report that it rotates in normal operation.
  I can't prove that from public sources, so the first hardware test of a Cast device will show it.
- The **device-auth chain** is a different thing, and it *does* expire: in March 2025 an intermediate CA in the Chromecast chain
  reached its end date (community analysis, fix shipped by firmware update). A sender that **validates** the chain breaks whenever Google's
  CA ages out; a sender that doesn't, doesn't.

### Recommendation (amends ADR 0005, proposed)

- Pin the **SPKI of the TLS leaf** on 8009 with the normal TOFU flow (fingerprint shown at pairing, loud alert on change).
- **Do not validate** the device-auth chain and don't build a Google CA trust store. It would add a dependency on Google's PKI and
  break on CA expiry, while adding nothing the pin doesn't already give us for a LAN device we paired ourselves.
- A leaf-key change shows as TrustChanged and needs a re-pair, as for LG TVs. If hardware tests show Cast leaves rotate often,
  revisit with the user before weakening anything.

## Android TV Remote Protocol v2 (Google TV, Android TV, Chromecast with Google TV)

| Piece | Detail |
|---|---|
| Ports | TLS **6467** for pairing, TLS **6466** for the remote session |
| Pairing | We generate a client certificate and key once. The TV shows a code; the user types it in Patchbay; a hash derived from both certificates and the code proves it. After that the TV accepts only certificates it paired with |
| Session | Protobuf messages: key presses (the remote's buttons), volume, power, app links, and status |
| Trust | Mutual: the TV pins our certificate, and we pin the TV's leaf (TOFU, as above) |
| Storage | Our client private key is a secret: encrypted at rest (invariant 4), never logged |

The protocol is reverse-engineered (the community `androidtv-remote` project descends from a library by louis49). Treat every message from the TV as untrusted.

## Scope when built

- Capabilities: Power (via remote key), Volume/Mute, Media transport, InputSource (as app/HDMI shortcuts), nothing that installs software.
- No casting of the user's own media, no screen mirroring, no microphone. Out of scope without an ADR.

## What I need from you before building

- Which devices: model of the Google TV / Chromecast, and the Cast speakers if any.
- Hardware test of the LG TV and Bambu first: they exercise the same TOFU code, so Cast needs no new trust design.

## Sources

- node-castv2 (protocol overview, device-auth namespace): https://github.com/thibauts/node-castv2
- r/Chromecast thread on the March 2025 device-auth certificate expiry (community analysis).
- `androidtv-remote` (npm) and the louis49 original it ports, for the v2 ports and pairing flow.
