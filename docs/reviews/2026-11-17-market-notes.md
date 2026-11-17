# Market notes (2026-11-17): what the alternatives already cover

Gathered by the main project session after the independent review ran out of usage at Phase 2. **Not independent**: facts and links first, my reading second. Every claim is from a web search on 2026-11-17; most search hits were forum posts and vendor-marketing pages, so the official pages are the ones to trust. The independent reviewer's Phase 4 (still TODO in `2026-11-16-fable-review.partial.md`) should redo and deepen this.

## Facts found (with sources)

| Area | What Home Assistant (HA) already does | Source |
|---|---|---|
| Govee | Official "Govee lights local" integration using the Govee LAN API; LAN Control must be switched on per device in the Govee app; not every Govee product supports it | https://home-assistant.io/integrations/govee_light_local |
| TP-Link Kasa/Tapo | Official TP-Link Smart Home integration for both lines; newer devices may need TP-Link cloud credentials to authenticate even for local control (vendor/forum claims, 2025) | https://selorahomes.com/home-assistant/integrations/tplink/ (secondary) |
| Android / Google TV | Official "Android TV Remote" integration (remote keys, launching apps) | https://www.home-assistant.io/integrations/androidtv_remote/ |
| HomeKit accessories (ecobee) | Official "HomeKit Device" (homekit_controller) integration; an accessory can be paired to one controller at a time, so the ecobee must be removed from Apple Home first. Same constraint as Patchbay | https://home-assistant.io/integrations/homekit_controller |
| Bambu Lab | Widely used custom integration greghesp/ha-bambulab lists the H2D among supported models; Bambu's local MQTT control is restricted while the printer is connected to Bambu Cloud (LAN-only mode is the workaround); some firmware requires MQTT encryption | https://www.github.com/greghesp/ha-bambulab ; https://forum.bambulab.com/t/mqtt-support/58793 |
| Tuya local | The original `localtuya` supports up to protocol 3.4; forks report 3.5; `tuya-local` (make-all) added special handling for 3.4/3.5 devices. 3.4/3.5 support is the known weak spot of local Tuya | https://github.com/make-all/tuya-local/releases ; https://community.home-assistant.io/t/local-tuya-control-tuya-devices-locally-fork-from-localtuya/634334 |
| Scale | About 661,000 active installations in HA's opt-in analytics (fewer than a quarter of users opt in, so the real base is larger) | https://analytics.home-assistant.io/statistics/ |
| Not found | LG webOS and Google Cast coverage, and a Windows companion app: the searches returned nothing usable | (gap) |

## What this suggests (my reading, not a verdict)

1. **The device layer is a solved, maintained problem.** Every device family Patchbay targets has an HA integration with far more real-world mileage than anything here, including the same HomeKit "remove from Apple Home first" limitation. Patchbay's protocol clients are therefore not a differentiator; at best they are a private reimplementation.
2. **What is different** is the stack and stance, not the protocols: no hub or server (the apps talk to devices directly), a hard NetGuard no-internet rule enforced in CI, a threat model, and a Windows-without-install build. HA can be run local-only, but it is a large platform with its own cloud-optional components and a broad add-on surface; Patchbay's smallness is its privacy argument.
3. **The honest question for the owner:** is the goal (a) the best possible control of these specific devices in the owner's home, or (b) a privacy-first product of its own? For (a), running Home Assistant (for example on a Home Assistant Green or a spare PC) and its official integrations is much less effort than twelve hand-built clients. For (b), Patchbay needs the part HA does not do well: a very small trusted surface and a first-class setup and privacy experience.
4. **Cheap way to find out:** before building more, install the HA integrations for the three or four owner devices that matter most, and compare. If HA does everything needed, that is the answer; if its setup, privacy or reliability falls short in a way the owner cares about, that is the opening for Patchbay.

## Not covered here (left for the independent Phase 4)
Homebridge, Matter/Thread coverage, openHAB, Hubitat, ioBroker; a ranked feature list with effort; a cost model for "build on Home Assistant" (custom integration, add-on, or custom frontend) versus continuing.
