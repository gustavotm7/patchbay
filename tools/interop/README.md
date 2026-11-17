# Interop with independent implementations

Our simulators and our adapters were written from the same reading of each protocol, so passing against the simulators proves consistency, not correctness. These checks put
**code we did not write** on the other side. They are run by hand (they need PyPI packages, which CI does not install) and only ever talk to loopback.

Set up once, in a throwaway virtualenv outside the repository:

    python3 -m venv /tmp/pbvenv
    /tmp/pbvenv/bin/pip install HAP-python tinytuya python-kasa pychromecast androidtvremote2 paho-mqtt govee-local-api bscpylgtv

**1. Our HomeKit client against HAP-python (a real accessory).**

    /tmp/pbvenv/bin/python tools/interop/hap_thermostat.py 51826 /tmp/hap.state &     # prints the setup code 314-15-926; note "mac" in /tmp/hap.state
    PATCHBAY_HAP_PYTHON_PORT=51826 PATCHBAY_HAP_PYTHON_ID=<mac from the state file> \
      ./gradlew -Ppatchbay.jvmOnly=true :simulators:jvmTest --tests '*HapPythonInteropTest' -i

**2. Reference clients against our simulators** (Tuya 3.3 to 3.5, Kasa, Cast, Google TV remote, Bambu MQTT, Govee, webOS).

    mkdir /tmp/pbhost && PATCHBAY_SIM_HOST_DIR=/tmp/pbhost ./gradlew -Ppatchbay.jvmOnly=true :simulators:jvmTest --tests '*SimulatorHostForInterop' &
    # wait for /tmp/pbhost/ports, then:
    /tmp/pbvenv/bin/python tools/interop/run_clients.py /tmp/pbhost
    touch /tmp/pbhost/stop      # then /tmp/pbhost/final shows the simulators' state (webos.volume=23 webos.muted=true webos.input=HDMI_3 webos.on=false, govee.brightness=40, ...)

`tools/interop/tuya_vectors.py` prints the known-answer vectors (3.4 and 3.5 frames, session keys, HMACs) from tinytuya's own codec; they are pinned in `TuyaSecureFramesTest`.

**3. Camera video: our RTSP client and depacketizers against ffmpeg's packetizer** (needs `ffmpeg` and `ffprobe` with libx264 and libx265; standard library Python only).
`rtsp_ffmpeg_server.py` encodes a test pattern, lets ffmpeg's RTP muxer packetize it, and serves those exact packets over RTSP (RTP interleaved on TCP, paced by the RTP timestamps).
`copy` mode packetizes a reference elementary stream so the output can be compared NAL for NAL; `direct` mode encodes straight into the muxer, as a camera would.

    W=/tmp/pbrtsp
    python3 -I tools/interop/rtsp_ffmpeg_server.py $W/h264 h264 copy 18554 --control streamid=0 &      # prints READY <port>; add "h265" for HEVC
    PATCHBAY_RTSP_PORT=18554 PATCHBAY_RTSP_OUT=$W/out.h264 PATCHBAY_FFMPEG=$(which ffmpeg) \
      ./gradlew -Ppatchbay.jvmOnly=true :simulators:jvmTest --tests '*RtspFfmpegInteropTest' --rerun
    python3 tools/interop/rtsp_compare.py h264 $W/out.h264 $W/h264/ref.h264 60     # picture NAL units identical, parameter sets, clean decode, 60 frames
    cat $W/out.h264.frames                                                         # how many JPEG pictures the real ffmpeg produced through CameraVideo

(First check the server itself with `ffprobe -rtsp_transport tcp -count_frames rtsp://127.0.0.1:18554/live`.) `rtp_vectors.py` writes the small real-packet fixture that
`RealPacketizerTest` pins in CI (so CI needs no ffmpeg).

Results on 2026-11-17 are in `docs/protocols/interop.md`.
