# 0016 — Camera video: H.265, and a small probe for the decoder

Status: Accepted (verification work found both) · Date: 2026-11-17

## Context

ADR 0012 plays a camera's H.264 stream on Windows through Patchbay's own guarded RTSP client and a local ffmpeg that reads only standard input. Two things were found while checking
that code against code we did not write (ffmpeg's RTP packetizer and the real ffmpeg decoder, `docs/protocols/interop.md`):

1. **H.265 was refused.** Some cameras stream H.265 (HEVC), and the eufy models were an open question; a refusal would mean no picture at all on day one.
2. **The first picture took about 4 seconds** on a live H.265 stream and did not appear at all within a four-second H.264 clip. ffmpeg's default probing of a raw stream waits for up to
   5 MB or 5 seconds of data before it shows anything. The ADR's "a second or two of latency" was never measured and was wrong.

## Decision

- **Play H.265 too.** `H265Depacketizer` follows RFC 7798: single NAL units, aggregation packets (type 48) and fragmentation units (type 49), with the NAL header rebuilt from the
  fragmentation header. The donl fields of interleaved mode and PACI packets are not supported (cameras do not use them). The SDP parser accepts `H265` (and `HEVC`) and reads
  `sprop-vps`, `sprop-sps` and `sprop-pps`; the first video section Patchbay can play is used. Both depacketizers share one RTP header parser.
- **The decoder follows the camera's codec.** The RTSP client reports the codec once the camera has agreed to play; only then is ffmpeg started, with `-f h264` or `-f hevc`. ffmpeg
  still reads only standard input and never sees the camera's address or login.
- **Probe a little.** The decoder is started with `-probesize 32768 -analyzeduration 0`. The parameter sets are at the start of what we feed it (from the SDP) or arrive with the next
  keyframe; measured, the first picture then appears after about a second, including when the stream starts in the middle of a group of pictures with no parameter sets up front.
- Every depacketizer is a parser of untrusted input, so each has strict bounds (a NAL is capped, a fragment broken by a lost packet is dropped, malformed packets are counted and
  ignored) and a 25,000-input mutation test.

## Verification

Real RTP from ffmpeg's packetizer (single NAL, STAP-A/AP and FU-A/FU packets) is pinned as test vectors; the NAL units our depacketizers return are the encoder's own (ffmpeg's muxer
leaves one stray zero byte on some NAL units, which is allowed for and reported). End to end, a server replaying ffmpeg's packets feeds our client and the real ffmpeg, which returns
30 to 36 whole JPEG pictures from each four-second stream, for both codecs and with parameter sets in the SDP or only in-band.

## Consequences

- H.265 needs an ffmpeg build with the HEVC decoder (the standard Windows builds have it). A build without it fails to start decoding and Copy diagnostics says which codec the camera sent.
- On Android, Media3 plays the stream itself (ADR 0008); whether H.265 plays there depends on the phone's decoder.
- A very small probe could in principle fail on a stream whose first 32 KB hold no parameter sets and whose keyframes are far apart; ffmpeg then waits for the keyframe instead of failing
  (measured), so the cost is a longer wait, not an error.
- The preview rate follows ffmpeg's assumed 25 frames per second for raw input, not the camera's real rate (a 15 fps camera shows about 7 pictures a second). Wall-clock timestamps did not
  change this in a test, so it is left alone until a real camera shows whether it matters.

## Alternatives considered

- Leaving H.265 out and telling the owner to switch the camera to H.264: not every camera has the option.
- Keeping ffmpeg's default probe and discarding the first seconds: the latency is the problem.
- A hardware-specific or library decoder: ADR 0012 already weighed these.
