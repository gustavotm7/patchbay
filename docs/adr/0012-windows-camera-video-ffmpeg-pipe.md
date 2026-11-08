# 0012 — Windows camera video: guarded RTSP in Patchbay, decoding by a piped ffmpeg

Status: Accepted (owner asked for camera video on Windows: "premium, go build all") · Date: 2026-11-08

## Context

Android plays RTSP through Media3 with a socket factory that goes through NetGuard (ADR 0008). The JVM has no H.264 decoder, and every Windows player
library (VLC, ffmpeg/JavaCV, JavaFX) opens its own sockets, which would bypass NetGuard (invariant 1) and put the camera login on a process command line.

## Decision

- Patchbay's own RTSP client (`RtspStreamClient`) does all the networking through NetGuard: DESCRIBE with the camera's own Digest/Basic challenge, SETUP/PLAY with
  RTP interleaved on the same TCP connection, keep-alives, strict size limits. It depacketizes H.264 (RFC 6184: single NAL, STAP-A, FU-A) to Annex-B.
- Decoding is done by a local `ffmpeg` that reads **only standard input** (`-f h264 -i pipe:0 -protocol_whitelist pipe`) and writes MJPEG to standard output, which Patchbay splits
  into pictures and draws. ffmpeg never sees the camera's address or login and cannot open a network connection.
- ffmpeg is **not bundled**: it is found as `ffmpeg.exe` next to `Patchbay.exe` (or in `PatchbayData`, or on PATH). Without it the camera panel says where to put it.
  Bundling would add a ~80 MB GPL/LGPL binary and its update duty to the supply chain (invariant 9).

## Alternatives considered

- JCodec (pure-Java H.264 decoder): no external binary, but a new dependency, High-profile and 2K streams are slow and incomplete in it.
- VLC/libvlc via vlcj, JavaCV: own sockets, large native bundles.
- Hand the stream URL to an external player: the login leaves Patchbay and the player's sockets are unguarded.

## Consequences

- Video on Windows needs the owner to download ffmpeg once (any recent `ffmpeg.exe`). Patchbay does not download it (no outbound internet).
- ffmpeg is a program Patchbay executes; only a file named ffmpeg in those folders (or on PATH) is used. A wrong or tampered ffmpeg could do anything an exe can; it is the owner's choice and folder.
- Latency is a second or two; frame rate is capped (12 fps) and width at 960 px to keep CPU use down.
- Audio is not played. Two-way audio and PTZ are out of scope.
- The H.264 depacketizer and RTSP client are exercised against the simulator only; HARDWARE_TEST C2.x is the first real-camera check.
