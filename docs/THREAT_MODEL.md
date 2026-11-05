# Threat model — Patchbay

Status: Phase 0 draft · 2026-11-05 · Review at every phase boundary and for every new adapter.

## 1. System summary

Two clients (an Android phone and a Windows laptop) and, from Phase 3, an optional hub. Each runs the full
`core` and talks to devices **directly on the LAN**. Clients sync with each other peer-to-peer over the LAN
(ADR 0002). There's no server, account, telemetry or relay.

```
 [Phone] ──LAN──┐                      ┌── Govee (UDP 4001-4003)
                ├── NetGuard ─────────►├── LG webOS (WSS 3001)   ── TOFU pin
 [Laptop] ─LAN──┘   (RFC1918/LL/ULA)   ├── Cast / Google TV (TLS 8009, 6466/6467) ── TOFU pin
      ▲   Noise KK peer sync           ├── Bambu H2D (MQTT/TLS 8883, FTPS 990)    ── TOFU pin
      └────────────────────────────────├── ecobee (HAP: SRP + Ed25519 + ChaCha20)
                                       └── ESPHome (Noise NNpsk0 6053)
   Internet: refused in code. Cloud-only devices (Kidde, VeSync stock, ThinQ): displayed, never contacted.
```

## 2. Assets

| Asset | Where | Sensitivity |
|---|---|---|
| Device credentials: webOS client key, Bambu access code, HAP long-term keys, Android TV certs, ESPHome PSKs | Encrypted DB | **High.** They give control of devices; the Bambu code also unlocks the camera. |
| Peer static keys (Noise) | Keystore-wrapped | **High.** Impersonating a peer leaks all credentials via sync. |
| Data-encryption key | Wrapped by Keystore / DPAPI | **High** |
| Home layout (floor plan, rooms, device positions) | Encrypted DB | Medium–high (burglary reconnaissance) |
| Presence data (phone at home, occupancy sensors, automation history) | Ledger in DB | **High** (reveals when the home is empty) |
| Network census (MACs, vendors, unknown devices) | Encrypted DB | Medium |
| Camera stream (H2D) | Memory only, never stored | High |
| Logs | Files, redacted | Low (by design) |

## 3. Adversaries and capabilities

| # | Adversary | Capability | Goal |
|---|---|---|---|
| A1 | **Malicious or compromised device on the LAN** (cheap IoT, a guest's phone) | Sends arbitrary mDNS/SSDP/UDP; impersonates devices; answers our connections; ARP/mDNS spoofing | Exploit our parsers; harvest credentials we send to the "device"; pivot |
| A2 | **Compromised dependency / build pipeline** | Malicious code in a library, plugin, Gradle wrapper or CI action | Exfiltrate data; open an internet backchannel |
| A3 | **Lost or stolen phone; laptop theft** | Physical access, possibly unlocked briefly; offline disk access | Read credentials and layout; control the home |
| A4 | **Curious vendor cloud** | Receives anything a device or SDK sends it | Profile the household |
| A5 | **Another app on the same phone/laptop** | Same-user (Windows) or sandboxed (Android) local access | Read our data; abuse exported components |
| A6 | **Shoulder surfer / screen recorder** | Sees the screen, screenshots, recents thumbnail | Read pairing/access codes |
| A7 | **Network observer on the LAN** | Passive sniffing on Wi-Fi | Read credentials in transit |

## 4. Mitigations (mapped to brief §2–3)

| Threat | Mitigation | Brief | Status |
|---|---|---|---|
| A1 parser exploits | Strict, bounded parsers; size caps on every frame; Jazzer fuzzing for every parser in CI; no reflection-based deserialisation of device data | §3 Untrusted input | Phase 1 |
| A1 device impersonation | TOFU SPKI pinning; loud `TrustChanged` alarm, never silent re-pin (ADR 0005). HAP/ESPHome authenticate cryptographically | §2.5 | Phase 1–2 |
| A1 credential phishing (fake device asks for a code) | Credentials are bound to a device identity at pairing and only sent over the pinned channel | §2.5 | Phase 1 |
| A1 discovery spoofing to redirect us to the internet | `NetGuard` checks the **resolved** address after DNS/mDNS, before connect. IPv4-mapped IPv6, NAT64 and 6to4 are refused; tested (`AddressPolicyTest`) | §2.1 | **Policy done (Phase 0)**, sockets Phase 1 |
| A1 resource exhaustion | Rate limits and exponential back-off per device; bounded queues; discovery reply caps | §7 | Phase 1 |
| A2 exfiltration by a library | NetGuard refuses non-LAN destinations in code, and `check-policy.sh` NET001 bans socket/HTTP APIs outside NetGuard. A malicious dependency could still open sockets directly via reflection; that residual risk is reduced by dependency minimisation, verification and SBOM review | §2.1, §2.9 | **Done (Phase 0)** |
| A2 tampered artifacts | Gradle dependency verification (sha256), wrapper checksum pinned and validated in CI, content-filtered repositories, CI actions pinned by SHA, WiX checksum pinned | §2.9 | **Done (Phase 0)**; Android/Compose metadata bootstrapped on first CI run |
| A2 banned SDKs | `check-policy.sh` SDK001/SDK002 | §2.2 | **Done (Phase 0)** |
| A3 offline disk access | Encrypted DB with a Keystore-/DPAPI-wrapped DEK (ADR 0003); `setUnlockedDeviceRequired`; no backups (`allowBackup=false`, data-extraction rules exclude everything) | §2.4, §2.8 | Manifest done; storage Phase 1 |
| A3 brief unlocked access | App lock (BiometricPrompt with DEVICE_CREDENTIAL; Windows Hello/PIN) with configurable auto-lock; re-auth before showing secrets or pairing | §2.6 | Phase 1 |
| A4 vendor clouds | No cloud adapters in v1; Exposure Map shows cloud-only devices "untouched"; suggested firewall rules (text only, never applied) | §2.3 | Data model done; UI Phase 1 |
| A5 other apps | Only the launcher activity is exported; no content providers; app-private storage; Windows: per-user install, data under `%LOCALAPPDATA%` with user-only ACLs | §2.8 | Manifest done |
| A6 shoulder surfing | `FLAG_SECURE` (whole app in Phase 0); codes masked by default with press-to-reveal | §2.6 | Partly done |
| A7 sniffing | Every protocol with credentials uses TLS/Noise/HAP encryption. **Exception:** the legacy webOS `ws://3000` is never used for the client key. Govee LAN is cleartext UDP with no credentials (documented exposure: anyone on the LAN can control Govee lights) | §4 | Phase 1 |
| Life safety | The Kidde alarm is never integrated or controlled. `CommandValidator` refuses any command to `lifeSafety` devices. The UI never presents Patchbay as an alarm path | §4, §9 | **Done (model)** |
| Printer safety | No heating, motion or G-code command types exist in the model; Stop needs two-step confirmation | §4 | **Done (model + UI)** |

## 5. Presence without location

Android requires location permission to read the Wi-Fi SSID. Instead, "phone is home" means all of:

1. The active network is Wi-Fi with a private address.
2. The default gateway answers at the expected LAN IP (ARP/NDP reachability, no internet).
3. At least one **pinned** device (TOFU-verified) is reachable.

This is spoofable only by an attacker who already controls the LAN and holds a pinned device's key.
It's recorded as an accepted risk for automations, which are never security-critical.

## 6. Residual risks / accepted

- A rooted or compromised phone/OS defeats everything. Keystore helps only partially.
- Govee LAN control is unauthenticated by design (vendor protocol).
- DPAPI CurrentUser protects against other users and offline theft of the disk only when it is
  encrypted (BitLocker recommended in `HARDWARE_TEST.md`). It doesn't protect against malware running
  as the same user.
- A malicious dependency using reflection or JNI to bypass NetGuard isn't caught by the grep. Mitigation:
  few dependencies, verification metadata, SBOM review, and an optional Windows Firewall rule
  (Private profile + local subnet only) documented for the user.

## 7. Open questions for review

1. Should `FLAG_SECURE` stay whole-app (current) or move to secret screens only? Whole-app also blocks
   your own screenshots.
2. Is the hub's "key file readable only by the service user" acceptable until a TPM option exists?
