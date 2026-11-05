# Protocol notes — verification status

Per brief §0.4, each adapter gets `docs/protocols/<device>.md` with primary-source links, written **when the
adapter is built** (Phase 1/2). This page tracks what Phase 0 checked and where the brief may need correcting.

Legend: ✅ confirmed against a source · ⚠️ partially confirmed / third-party only · ❓ not yet checked.

| Topic | Brief says | Phase 0 finding | Status |
|---|---|---|---|
| Android local network access | "check current requirements" | **Android 17 enforces local network protection for apps targeting SDK 37+.** Such apps need the new runtime permission `ACCESS_LOCAL_NETWORK` (NEARBY_DEVICES group). Blocked TCP times out; blocked UDP fails with `EPERM`. Apps below SDK 37 keep access (implicit grant with INTERNET). System pickers via NsdManager avoid the permission for single-device selection, but broad IoT discovery needs it. We target 37 and request it at first discovery, with a rationale screen. Test toggle: `adb shell am compat enable RESTRICT_LOCAL_NETWORK <pkg>`. Source: developer.android.com/privacy-and-security/local-network-permission | ✅ |
| Presence "phone on home Wi-Fi" | implies reading the SSID | Reading the SSID on Android needs a location permission. **Correction:** we infer "home" from LAN identity (gateway reachability + a pinned device reachable) and never request location. See THREAT_MODEL §5 | ✅ (design) |
| Network security config "deny cleartext except to local ranges" | per-range exceptions | NSC can't express IP ranges (only domains/exact hosts), and it governs platform HTTP stacks, **not raw sockets**. **Correction:** cleartext is denied globally in NSC, and LAN-only enforcement for raw-socket protocols (Govee UDP, legacy webOS ws) lives in NetGuard. Any NSC exception is listed per host | ✅ |
| Compose Desktop dependencies | — | Compose Multiplatform 1.12 desktop artifacts depend on `androidx.*` from **Google Maven**, so Google Maven is a required build repository for both apps | ✅ |
| Roborazzi | "Roborazzi or equivalent" | Used Compose's own `ImageComposeScene` instead (fewer dependencies; ADR 0006) | — |
| Bambu H2D access | MQTT 8883 `bblp`/access code; LAN Only + Developer Mode | Third-party guides (Bambuddy, 3DPrinterOS, All3DP) agree: on H2D, enable **LAN Only Mode first, then Developer Mode** (only visible after LAN Only). **The access code changes when these modes are toggled.** Developer Mode unlocks MQTT, FTP and the camera for third parties, and Bambu says it's **not officially supported**. Camera on X1/H2/P2-series: RTSPS on **322** (`rtsps://bblp:<code>@<ip>:322/streaming/live/1`); P1/A1 use port 6000 (a JPEG stream). Discovery: SSDP-style NOTIFY broadcasts (port to verify in Phase 1 — commonly reported as UDP 2021). Needs H2D firmware-specific verification | ⚠️ |
| ESPHome native API | port 6053, protobuf, Noise | `Noise_NNpsk0_25519_ChaChaPoly_SHA256` confirmed by independent implementations. Plaintext still exists when no key is configured. A device with a key rejects plaintext clients. We'll support both but warn on plaintext. Official esphome.io docs to be linked in Phase 1 | ⚠️ |
| ecobee cloud API | closed to new keys — don't build | Agreed; not built. HAP via `_hap._tcp` with `aiohomekit` as reference | ❓ (Phase 2) |
| LG webOS SSAP | wss 3001 current, ws 3000 legacy | ❓ Phase 1. We'll never send the client key over ws:3000 | ❓ |
| Cast v2 / Android TV Remote v2 | 8009 TLS; 6466/6467 | ❓ Phase 1 | ❓ |
| Govee LAN API | 239.255.255.250:4001 → 4002, commands on 4003 | ❓ Phase 1; per-model LAN support detection | ❓ |
| OUI database | bundled offline snapshot | `standards-oui.ieee.org` was unreachable from the Phase 0 sandbox. Plan: `tools/update-oui.sh` fetches the IEEE MA-L/MA-M/MA-S CSVs at **developer** time; the snapshot is committed (with date + sha256) so builds stay offline and reproducible. Check IEEE's redistribution terms first | ❓ |
| Windows Hello from the JVM | "Windows Hello (or PIN fallback)" | No JVM API. Options: WinRT `UserConsentVerifier` via a tiny signed helper exe, or JNA → COM/WinRT activation. **Risk flagged for Phase 1**; the PIN fallback (Argon2id-hashed, DPAPI-protected) ships first | ⚠️ |
| WiX for MSI | — | The Compose plugin downloads WiX 3.11.2 without verifying a checksum. CI now fetches it itself (sha256 verified). WiX 3 is legacy | ✅ |
