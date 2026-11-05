# Govee LAN API

Adapter: `core/src/jvmShared/.../adapter/govee/GoveeLanAdapter.kt` · Codec: `core/src/commonMain/.../adapter/govee/GoveeProtocol.kt`
Simulator: `simulators/src/jvmShared/.../GoveeSimulator.kt` · Contract tests: `simulators/src/jvmTest/.../GoveeContractTest.kt`

## Sources

- Govee's official "WLAN guide": https://app-h5.govee.com/user-manual/wlan-guide (primary; it was unreachable
  from the build sandbox, so its content was cross-checked through search summaries).
- The `govee` Rust crate 0.8.4, `src/backend/local.rs` (Apache-2.0), read for message shapes. No code was copied.
- The openHAB Govee LAN binding docs (ports and firewall notes).

## Transport

| Purpose | Direction | Address / port |
|---|---|---|
| Scan | client → multicast | `239.255.255.250:4001` |
| Scan reply, status reply | device → **client IP, port 4002** (fixed, not the request's source port) | unicast |
| Commands, `devStatus` request | client → device | `<device-ip>:4003` |

Only one process per host can bind UDP 4002. If Home Assistant or another Govee integration runs on the same
machine, the adapter reports "Govee needs UDP port 4002…". This isn't an issue on a phone.

## Messages (all JSON over UDP, cleartext, unauthenticated)

Requests:

```json
{"msg":{"cmd":"scan","data":{"account_topic":"reserve"}}}
{"msg":{"cmd":"devStatus","data":{}}}
{"msg":{"cmd":"turn","data":{"value":1}}}
{"msg":{"cmd":"brightness","data":{"value":40}}}
{"msg":{"cmd":"colorwc","data":{"color":{"r":46,"g":196,"b":182},"colorTemInKelvin":0}}}
{"msg":{"cmd":"colorwc","data":{"color":{"r":0,"g":0,"b":0},"colorTemInKelvin":2700}}}
```

Notes on requests:

- `account_topic` must be `"reserve"`.
- Brightness is 1–100.
- `colorwc` carries both colour and colour temperature. Colour temperature 0 means "use RGB"; the valid
  range is 2000–9000 K.

Replies:

```json
{"msg":{"cmd":"scan","data":{"ip":"192.168.1.23","device":"1F:80:C5:32:32:36:72:4E","sku":"H6076",
  "bleVersionHard":"3.01.01","bleVersionSoft":"1.03.01","wifiVersionHard":"1.00.10","wifiVersionSoft":"1.02.03"}}}
{"msg":{"cmd":"devStatus","data":{"onOff":1,"brightness":40,"color":{"r":46,"g":196,"b":182},"colorTemInKelvin":0}}}
```

## Our hardening decisions

- **The address comes from the UDP source, never from the `ip` field.** A forged scan reply can't redirect us,
  and a forged status from another address is ignored (contract test `forgedStatusFromAnotherAddressIsIgnored`).
  An attacker who can spoof the device's own source IP on the LAN can still forge status. The protocol has no
  authentication; see THREAT_MODEL §6.
- The parser is **strict**: 2 KiB maximum, depth 6 at most, integer-only numbers, ranges enforced. Out-of-spec
  values are rejected, not clamped. Unknown extra fields are ignored for forward compatibility. A 20 000-input
  mutation test runs in every build.
- **Pacing:** at least 120 ms between datagrams to one device. Firmware drops bursts.
- **Polling:** `devStatus` every 5 s. On failure: DEGRADED, then OFFLINE after 3 misses, with exponential back-off
  capped at 60 s.
- **No pairing secret.** "Pairing" only confirms the device answers.

## Open questions (need real hardware — see HARDWARE_TEST.md, Phase 1)

- [ ] Which of your models answer the scan (LAN Control must be on per device).
- [ ] Whether segmented strips (e.g. H619A) expose anything beyond colour/brightness over LAN (`razer`/`ptReal`
      commands exist on some models; out of scope for now).
- [ ] Real reply latency, to tune the 1.5 s status timeout.
