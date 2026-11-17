# Interop with independent implementations

Our simulators and our adapters were written from the same reading of each protocol, so passing against the simulators proves consistency, not correctness. These checks put
**code we did not write** on the other side. They are run by hand (they need PyPI packages, which CI does not install) and only ever talk to loopback.

Set up once, in a throwaway virtualenv outside the repository:

    python3 -m venv /tmp/pbvenv
    /tmp/pbvenv/bin/pip install HAP-python tinytuya python-kasa pychromecast androidtvremote2

**1. Our HomeKit client against HAP-python (a real accessory).**

    /tmp/pbvenv/bin/python tools/interop/hap_thermostat.py 51826 /tmp/hap.state &     # prints the setup code 314-15-926; note "mac" in /tmp/hap.state
    PATCHBAY_HAP_PYTHON_PORT=51826 PATCHBAY_HAP_PYTHON_ID=<mac from the state file> \
      ./gradlew -Ppatchbay.jvmOnly=true :simulators:jvmTest --tests '*HapPythonInteropTest' -i

**2. Reference clients against our simulators** (Tuya, Kasa, Cast, Google TV remote).

    mkdir /tmp/pbhost && PATCHBAY_SIM_HOST_DIR=/tmp/pbhost ./gradlew -Ppatchbay.jvmOnly=true :simulators:jvmTest --tests '*SimulatorHostForInterop' &
    # wait for /tmp/pbhost/ports, then:
    /tmp/pbvenv/bin/python tools/interop/run_clients.py /tmp/pbhost
    touch /tmp/pbhost/stop

`tools/interop/tuya_vectors.py` prints the known-answer vectors (3.4 and 3.5 frames, session keys, HMACs) from tinytuya's own codec; they are pinned in `TuyaSecureFramesTest`.

Results on 2026-11-17 are in `docs/protocols/interop.md`.
