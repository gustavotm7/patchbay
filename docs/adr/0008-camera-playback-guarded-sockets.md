# 0008 — In-app camera playback with guarded sockets

Status: Accepted (owner approved 2026-11-06) · Date: 2026-11-06

## Context

The eufy RTSP adapter checks that a camera is up but doesn't play video. The obvious Android player, AndroidX Media3 (`media3-exoplayer-rtsp`),
opens its own TCP/UDP sockets. Invariant 1 says every socket goes through NetGuard, and `tools/check-policy.sh` only enforces that for *our*
source, so a library could silently connect anywhere it's handed a URL.

## Decision (proposed)

- Add Media3 ExoPlayer + RTSP (Apache-2.0, no network calls of its own beyond the media URL, no Google Play Services) with a `docs/DEPENDENCIES.md`
  entry and refreshed verification metadata.
- Give it a **NetGuard-backed `SocketFactory`** (`RtspMediaSource.Factory.setSocketFactory`). The factory refuses every address `AddressPolicy`
  refuses and counts connections in NetGuard's stats. Playback of a non-LAN URL fails, and a unit test proves it.
- **TCP only.** The player is told to carry media interleaved on the RTSP TCP connection (`setForceUseRtpTcp(true)`), because UDP media sockets
  are created by the player, not by the factory, and couldn't be guarded. A camera that only streams over UDP won't play. That's an accepted limit.
- Credentials: the adapter hands the player a URL **only at the moment the user opens the camera**, held in memory, never logged, never persisted
  outside the encrypted store, never shown. The panel keeps showing the credential-free address.
- Whole-app `FLAG_SECURE` already blocks screenshots and recents thumbnails of the video.
- No recording, no audio capture, no motion events, no cloud relay. Desktop playback is a separate decision (needs a JVM player library).

## Consequences

- Invariant 1 stays true for playback because the only socket path is the guarded factory. Android lint/R8 must not strip it.
- A new, large dependency (about 1 MB of code) and its supply-chain entry. APK permissions don't change.
- If a Media3 release stops honouring the factory for any transport, playback must be disabled until fixed. A test that the player makes zero sockets
  outside the factory belongs in the contract tests.
