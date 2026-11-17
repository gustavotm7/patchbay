#!/usr/bin/env python3
"""Compares what Patchbay's RTSP client delivered with the encoder's own elementary stream, and decodes it with ffmpeg.

usage: rtsp_compare.py <codec: h264|h265> <delivered Annex-B file> [<reference Annex-B file>] [<expected frames>]

With a reference (the copy mode of rtsp_ffmpeg_server.py): the picture (VCL) NAL units must be identical to the reference, in order, byte for
byte. Always: ffmpeg must decode the delivered stream without errors and produce the expected number of frames.
"""
import re, subprocess, sys


def reference_nals(data):
    """The encoder's own NAL units from its Annex-B file. A NAL never ends in a zero byte, so zeros before the next start code belong to that start code."""
    starts = [m.start() for m in re.finditer(b"\x00\x00\x01", data)]
    out = []
    for k, s in enumerate(starts):
        nal = data[s + 3:starts[k + 1] if k + 1 < len(starts) else len(data)]
        while nal.endswith(b"\x00"):
            nal = nal[:-1]
        out.append(nal)
    return out


def delivered_nals(data):
    """Patchbay's client always writes 4-byte start codes, and a NAL cannot contain 00 00 00 01, so splitting on it is exact: every byte a NAL carried is kept."""
    parts = data.split(b"\x00\x00\x00\x01")
    if parts[0] != b"":
        sys.exit("FAIL the delivered stream does not begin with a start code")
    return parts[1:]


def nal_type(codec, nal):
    return (nal[0] & 0x1F) if codec == "h264" else ((nal[0] >> 1) & 0x3F)


def is_vcl(codec, nal):
    t = nal_type(codec, nal)
    return t in (1, 5) if codec == "h264" else t < 32


def main():
    codec, delivered = sys.argv[1], open(sys.argv[2], "rb").read()
    ref_path = sys.argv[3] if len(sys.argv) > 3 and sys.argv[3] != "-" else None
    expected = int(sys.argv[4]) if len(sys.argv) > 4 else 60
    ok = True
    got = delivered_nals(delivered)
    vcl = [n for n in got if is_vcl(codec, n)]
    print("delivered: %d NAL units, %d picture NAL units" % (len(got), len(vcl)))
    if ref_path:
        ref = [n for n in reference_nals(open(ref_path, "rb").read()) if is_vcl(codec, n)]
        # ffmpeg's RTP muxer leaves the zero byte of the next NAL's 4-byte start code attached to the NAL before it, so a NAL may arrive with exactly one extra
        # trailing 0x00 (a decoder ignores it; a real NAL never ends in 0x00). Anything else is a difference.
        exact = sum(1 for a, b in zip(vcl, ref) if a == b)
        stray = sum(1 for a, b in zip(vcl, ref) if a == b + b"\x00")
        same = len(vcl) == len(ref) and exact + stray == len(ref)
        first_diff = next((i for i, (a, b) in enumerate(zip(vcl, ref)) if a != b and a != b + b"\x00"), None)
        print("%s picture NAL units equal the encoder's: %d identical, %d with ffmpeg's one stray trailing zero byte (%d vs %d)%s" % (
            "PASS" if same else "FAIL", exact, stray, len(vcl), len(ref), "" if same else "; first difference at %s" % first_diff))
        ok &= same
        # The first parameter sets the client delivers come from the SDP (sprop-...), before anything is in-band: they must be the encoder's own.
        want = [n for n in reference_nals(open(ref_path, "rb").read()) if not is_vcl(codec, n) and nal_type(codec, n) in ((7, 8) if codec == "h264" else (32, 33, 34))]
        want = want[:3 if codec == "h265" else 2]
        sets = got[:len(want)]
        same_sets = sets == want
        print("%s parameter sets from the SDP equal the encoder's (%d)" % ("PASS" if same_sets else "FAIL", len(want)))
        ok &= same_sets
    raw = "h264" if codec == "h264" else "hevc"
    r = subprocess.run(["ffmpeg", "-hide_banner", "-v", "error", "-f", raw, "-i", sys.argv[2], "-f", "null", "-", "-stats_period", "0.1"], capture_output=True, text=True)
    probe = subprocess.run(["ffprobe", "-v", "error", "-f", raw, "-count_frames", "-select_streams", "v:0", "-show_entries", "stream=nb_read_frames", "-of", "default=nw=1:nk=1", sys.argv[2]], capture_output=True, text=True)
    frames = int(probe.stdout.strip() or 0)
    clean = r.returncode == 0 and r.stderr.strip() == ""
    print("%s ffmpeg decodes it cleanly%s" % ("PASS" if clean else "FAIL", "" if clean else ": " + r.stderr[:300]))
    print("%s decoded frames: %d (expected %d)" % ("PASS" if frames == expected else "FAIL", frames, expected))
    ok &= clean and frames == expected
    sys.exit(0 if ok else 1)


main()
