# Bambu Lab (H2D) — LAN MQTT

Adapter: `core/src/jvmShared/.../adapter/bambu/BambuAdapter.kt` · Codec: `core/src/commonMain/.../adapter/bambu/BambuReport.kt`
MQTT: `core/src/jvmShared/.../net/mqtt/MqttClient.kt` · Simulator: `simulators/.../BambuSimulator.kt` · Tests: `BambuContractTest`, `BambuReportTest`

## Sources

- `ha-bambulab` (MIT; commit `242be3e`, 2026-11-01): topics, credentials, `pushall`, `pause`/`resume`/`stop`,
  `ledctrl`, and the H2D packed temperature words. Read for reference; no code or mock data was copied.
- Community guides (Bambuddy, 3DPrinterOS, All3DP), summarised in `docs/protocols/README.md`.

## Printer setup (yours to do)

On the printer: **Settings → LAN Only Mode → on**, then **Developer Mode → on** (it only appears after LAN Only).
Note the **access code** shown there. It changes whenever you toggle these modes. Bambu calls Developer Mode
unsupported, and Bambu Handy/cloud features stop working.

## Transport and trust

- MQTT 3.1.1 over **TLS on 8883**. The printer's key is pinned on first use (ADR 0005). ha-bambulab instead
  verifies against Bambu's CA; per-device pinning is stricter.
- User `bblp`, password = the access code (stored encrypted, never logged).
- Subscribe `device/<serial>/report`; publish to `device/<serial>/request`.
- Discovery: SSDP `ST: urn:bambulab-com:device:3dprinter:1`. The serial comes from `USN`, plus `DevModel.bambu.com`
  and `DevName.bambu.com`. We ask on ports 1900 and 1990. **To verify on your printer:** some firmware only
  broadcasts NOTIFY on UDP 2021 and may not answer M-SEARCH. The network census still lists it, and manual add
  is a follow-up.

## Reports

One full `pushall`, then **incremental** updates with only the changed fields. These are deep-merged
(`BambuReport.merge`).

| Field | Meaning |
|---|---|
| `gcode_state` | IDLE / PREPARE / RUNNING / PAUSE / FINISH / FAILED |
| `mc_percent`, `layer_num`, `total_layer_num`, `mc_remaining_time` (min), `subtask_name` | job |
| `device.extruder.info[].temp` (H2D, 2 nozzles) | packed: low 16 bits current °C, high 16 bits target |
| `device.bed.info.temp`, `device.ctc.info.temp` | packed bed / chamber (fallback: `bed_temper`, `chamber_temper`) |
| `ams.ams[].tray[]`, `ams.tray_now` | filament slots (type, RRGGBBAA colour, remain %), active slot |
| `hms[]` | `{attr, code}` → `HMS_AAAA_AAAA_CCCC_CCCC`; severity from the code's high word |
| `lights_report[]` | chamber light(s) |

## Commands (the whole set; brief §4 safety scope)

- `{"print":{"command":"pause"|"resume"|"stop"}}`. Stop is two-step in the UI.
- `{"system":{"command":"ledctrl","led_node":"chamber_light"|"chamber_light2","led_mode":"on"|"off",…}}`. The H2D's
  second light is switched together with the first.

There is **no** builder for heating, motion, fans, speed or G-code anywhere in the code.

## Not yet

- Camera (RTSPS on 322) and FTPS file listing.
- Manual add by IP and serial, for printers that don't answer SSDP.
- Real-device verification on your H2D firmware.
