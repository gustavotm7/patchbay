# LG webOS (SSAP)

Adapter: `core/src/jvmShared/.../adapter/webos/WebOsAdapter.kt` · Codec: `core/src/commonMain/.../adapter/webos/Ssap.kt`
WebSocket: `core/src/jvmShared/.../net/ws/WebSocketClient.kt` · Simulator: `simulators/.../WebOsSimulator.kt`
Contract tests: `simulators/src/jvmTest/.../WebOsContractTest.kt`

## Sources

- `aiowebostv` 1.0.0 (Apache-2.0), the library Home Assistant uses: registration payload, endpoints,
  hello → system info → register sequence. Read for reference; no code was copied.

## Transport and trust

- **Discovery:** SSDP M-SEARCH with `ST: urn:lge-com:service:webos-second-screen:1`. The TV answers by unicast.
- **Connection:** `wss://<tv>:3001` only. The TV's self-signed key is **pinned on first use** (ADR 0005).
  aiowebostv tries cleartext `ws://:3000` first; **we never do**, so the client key never crosses the LAN
  unencrypted.

## Pairing (two user steps)

1. Patchbay shows the TV's key fingerprint. You tap **Trust**. Nothing is sent before this.
2. `hello` → `register` (`pairingType: PROMPT`, with a minimal permission list). The TV shows a prompt; you
   accept it with the remote.
3. The TV answers `registered` with a `client-key`. The key and the pin are stored encrypted (ADR 0003).

Later sessions: pinned TLS, then `hello`, then `register` with the stored key. If the TV presents a different
key, the result is `TrustChanged` and the stored key is **not** sent (contract test
`changedTvKeyIsTrustChangedNotSilentlyAccepted`).

## Supported now

| Function | SSAP |
|---|---|
| Power state | `com.webos.service.tvpower/power/getPowerState` (`Active`, `Screen Off` = on) |
| Power off | `system/turnOff` |
| Volume | `audio/getVolume`, `audio/setVolume {volume}` |
| Mute | `audio/setMute {mute}` |
| Inputs | `tv/getExternalInputList`, `tv/switchInput {inputId}` |
| Media keys | `media.controls/play`, `pause`, `stop` |

Polling every 5 s over one reused connection. Exponential back-off, capped at 60 s, when the TV is
unreachable. A TV in standby usually drops the socket.

## Not yet

- **Power on:** needs Wake-on-LAN plus the TV's MAC, and "Turn on via Wi-Fi" enabled on the TV. NetGuard
  already allows limited broadcast (`255.255.255.255`).
- Toasts, app launching, and push subscriptions instead of polling.
- Real-device verification: firmware differences in volume and power-state shapes are handled per
  aiowebostv, but untested on hardware.
