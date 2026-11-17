#!/usr/bin/env python3
"""A tiny RTSP server whose video is real: ffmpeg (libx264 or libx265) encodes a test pattern, ffmpeg's own RTP muxer packetizes it
(single NAL units, STAP-A / aggregation packets and FU-A / FU fragments as a real encoder and packetizer produce them), and this script
replays those exact RTP packets to a client over RTSP with RTP interleaved on the TCP connection. Only the RTSP signalling is ours; the
payloads and the SDP are ffmpeg's. Used to check Patchbay's RTSP client and depacketizer against an independent packetizer
(tools/interop/README.md). Standard library only; loopback only.

usage: rtsp_ffmpeg_server.py <workdir> <h264|h265> <copy|direct> <port> [--control track1]

  copy    encode a reference elementary stream first (<workdir>/ref.h264 or ref.h265), then packetize that very file (-c copy): the parameter
          sets are in-band, and a client's output can be compared NAL for NAL with the reference.
  direct  encode straight into the RTP muxer, as a camera would: the parameter sets travel in the SDP (sprop-...) and are not in-band.

Prints READY <port> when it is serving, then answers connections one after another until it is killed.
"""
import os, re, socket, struct, subprocess, sys, time


def run(cmd, **kw):
    r = subprocess.run(cmd, capture_output=True, text=True, **kw)
    if r.returncode != 0:
        sys.exit("command failed: %s\n%s" % (" ".join(cmd), r.stderr[-2000:]))


def encode_args(codec):
    if codec == "h264":
        return ["-c:v", "libx264", "-preset", "medium", "-g", "15", "-x264-params", "slices=3:log-level=error"]
    return ["-c:v", "libx265", "-preset", "medium", "-g", "15", "-x265-params", "log-level=error:repeat-headers=1"]


def prepare(work, codec, mode):
    os.makedirs(work, exist_ok=True)
    src = ["-f", "lavfi", "-i", "testsrc2=size=640x480:rate=15", "-t", "4"]
    sdp_path = os.path.join(work, "stream.sdp")
    if os.path.exists(sdp_path):
        os.remove(sdp_path)
    udp = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    udp.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, 8 * 1024 * 1024)
    udp.bind(("127.0.0.1", 0))
    udp.settimeout(0.5)
    port = udp.getsockname()[1]
    pkt = ["-pkt_size", "1200"]
    if mode == "copy":
        ref = os.path.join(work, "ref." + codec)
        raw = "h264" if codec == "h264" else "hevc"
        run(["ffmpeg", "-y", "-loglevel", "error"] + src + encode_args(codec) + ["-f", raw, ref])
        feed = ["ffmpeg", "-y", "-loglevel", "error", "-re", "-f", raw, "-i", ref, "-c", "copy"]
    else:
        feed = ["ffmpeg", "-y", "-loglevel", "error", "-re"] + src + encode_args(codec)
    feed += ["-f", "rtp", "-sdp_file", sdp_path, "rtp://127.0.0.1:%d?pkt_size=1200" % port]
    proc = subprocess.Popen(feed, stderr=subprocess.PIPE, text=True)
    packets = []
    idle = 0
    while True:
        try:
            data, _ = udp.recvfrom(65535)
            packets.append(data)
            idle = 0
        except socket.timeout:
            idle += 1
            if proc.poll() is not None and idle >= 2:
                break
    err = proc.stderr.read()
    if proc.returncode != 0 or not packets:
        sys.exit("ffmpeg produced no RTP (%s): %s" % (proc.returncode, err[-2000:]))
    udp.close()
    sdp = open(sdp_path).read()
    return sdp, packets


def sender_report(ssrc, seq):
    # A minimal valid RTCP sender report (no report blocks), as cameras send on the odd interleaved channel.
    return struct.pack(">BBHIIIIII", 0x80, 200, 6, ssrc, int(time.time()) + 2208988800, 0, seq * 90, seq, seq * 100)


def read_request(conn, buf):
    while b"\r\n\r\n" not in buf:
        d = conn.recv(4096)
        if not d:
            return None, buf
        buf += d
    head, _, rest = buf.partition(b"\r\n\r\n")
    lines = head.decode("latin-1").split("\r\n")
    method, url, _ = lines[0].split(" ", 2)
    headers = {}
    for l in lines[1:]:
        k, _, v = l.partition(":")
        headers[k.strip().lower()] = v.strip()
    return (method, url, headers), rest


def reply(conn, cseq, status="200 OK", headers=(), body=b""):
    out = "RTSP/1.0 %s\r\nCSeq: %s\r\n" % (status, cseq)
    for h in headers:
        out += h + "\r\n"
    if body:
        out += "Content-Length: %d\r\n" % len(body)
    conn.sendall(out.encode() + b"\r\n" + body)


def serve(conn, sdp, packets, control):
    buf = b""
    session = "A1B2C3D4"
    channel = 0
    ssrc = struct.unpack(">I", packets[0][8:12])[0] if len(packets[0]) >= 12 else 1
    while True:
        req, buf = read_request(conn, buf)
        if req is None:
            return
        method, url, h = req
        cseq = h.get("cseq", "0")
        if method == "OPTIONS":
            reply(conn, cseq, headers=["Public: OPTIONS, DESCRIBE, SETUP, PLAY, TEARDOWN"])
        elif method == "DESCRIBE":
            text = sdp
            if control:
                text = re.sub(r"(m=video [^\r\n]*\r?\n)", lambda m: m.group(1) + "a=control:" + control + "\r\n", text, count=1)
            reply(conn, cseq, headers=["Content-Type: application/sdp", "Content-Base: " + url.rstrip("/") + "/"], body=text.encode())
        elif method == "SETUP":
            m = re.search(r"interleaved=(\d+)-(\d+)", h.get("transport", ""))
            if not m:
                reply(conn, cseq, "461 Unsupported Transport")
                continue
            channel = int(m.group(1))
            reply(conn, cseq, headers=["Transport: RTP/AVP/TCP;unicast;interleaved=%s-%s" % (m.group(1), m.group(2)), "Session: %s;timeout=60" % session])
        elif method == "PLAY":
            reply(conn, cseq, headers=["Session: " + session, "Range: npt=0.000-"])
            # Paced by the RTP timestamps (90 kHz), as a camera sends: a decoder given the whole clip in a burst would be torn down at the end of the stream
            # before it had produced its pictures.
            t0 = struct.unpack(">I", packets[0][4:8])[0]
            began = time.monotonic()
            for i, p in enumerate(packets):
                due = ((struct.unpack(">I", p[4:8])[0] - t0) & 0xFFFFFFFF) / 90000.0
                wait = due - (time.monotonic() - began)
                if wait > 0:
                    time.sleep(wait)
                conn.sendall(b"$" + bytes([channel]) + struct.pack(">H", len(p)) + p)
                if i % 25 == 0:
                    sr = sender_report(ssrc, i)
                    conn.sendall(b"$" + bytes([channel + 1]) + struct.pack(">H", len(sr)) + sr)
            time.sleep(1.5)
            return
        elif method == "TEARDOWN":
            reply(conn, cseq, headers=["Session: " + session])
            return
        else:
            reply(conn, cseq, "501 Not Implemented")


def main():
    args = [a for a in sys.argv[1:]]
    control = None
    if "--control" in args:
        i = args.index("--control")
        control = args[i + 1]
        del args[i:i + 2]
    if len(args) != 4 or args[1] not in ("h264", "h265") or args[2] not in ("copy", "direct"):
        sys.exit(__doc__)
    work, codec, mode, port = args[0], args[1], args[2], int(args[3])
    sdp, packets = prepare(work, codec, mode)
    # What the packetizer produced, so a run says which payload kinds it exercised (H.264: 24 = STAP-A, 28 = FU-A; H.265: 48 = AP, 49 = FU; the rest are single NAL units).
    kinds = {}
    for p in packets:
        t = (p[12] & 0x1F) if codec == "h264" else ((p[12] >> 1) & 0x3F)
        kind = {24: "STAP-A", 28: "FU-A"}.get(t, "single") if codec == "h264" else {48: "AP", 49: "FU"}.get(t, "single")
        kinds[kind] = kinds.get(kind, 0) + 1
    print("captured %d RTP packets: %s" % (len(packets), ", ".join("%s %d" % kv for kv in sorted(kinds.items()))), flush=True)
    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind(("127.0.0.1", port))
    srv.listen(4)
    print("READY %d" % srv.getsockname()[1], flush=True)
    while True:
        conn, _ = srv.accept()
        try:
            serve(conn, sdp, packets, control)
        except (ConnectionError, BrokenPipeError):
            pass
        finally:
            conn.close()


if __name__ == "__main__":
    main()
