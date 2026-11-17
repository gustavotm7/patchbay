# Interop with independent implementations (2026-11-17)

Everything else in the repository checks our adapters against simulators we wrote from the same reading of each protocol. These runs put code we did not write on the other side,
on loopback only. How to repeat them: `tools/interop/README.md`.

| System | Independent code | Direction | Result |
|---|---|---|---|
| HomeKit (ecobee) | HAP-python 5.2.0 (a real accessory serving a thermostat) | our client to it | **Pass.** SRP pair-setup with the setup code (method 0), HKDF, Ed25519 exchange, ChaCha20-Poly1305 session, pair-verify, reading temperature and setpoint, writing a setpoint (the accessory logged the write). This is the first check of our hand-written SRP/HKDF/Ed25519/X25519/AEAD stack against a reference implementation |
| Tuya 3.3 | tinytuya 1.21.1 | it to our simulator | **Pass.** It decrypts and parses our frames, reads data points, and its control frame switches the simulated bulb |
| TP-Link Kasa (legacy) | python-kasa 0.12.0 (protocol layer) | it to our simulator | **Pass.** `get_sysinfo` and `set_relay_state` over the scrambled TCP framing. (Its high-level device update asks for modules the simulator does not implement, which says nothing about the framing) |
| Cast | pychromecast 14.1.1 | it to our simulator | **Pass after two simulator fixes** it exposed: replies must carry the request's own id and be addressed to the requester, and requests with spaces in the JSON must be understood. The adapter's side was not affected (it sends compact JSON and does not wait for replies) |
| Google TV remote | androidtvremote2 0.3.3 | it to our simulator | **Pass.** It computes the pairing code from its own certificate and the TV's, and our simulator accepted it; it connects and sends a key |

What this does and does not show. It shows the wire formats and cryptography in our code and simulators agree with independent implementations, which removes the largest "shared
misunderstanding" risk for these five. It does not show how real devices behave: firmware quirks, an ecobee that is certified (the HAP-python accessory is not), rate limits,
Wi-Fi power saving. The hardware checks in `HARDWARE_TEST.md` are still the test for that. Not covered: webOS, Bambu (MQTT) and RTSP, where no suitable library was installed.

The HomeKit check is `HapPythonInteropTest` (skipped unless an accessory is running); the others are `tools/interop/run_clients.py` against `SimulatorHostForInterop`.
