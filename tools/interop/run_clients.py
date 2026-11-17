"""Independent client libraries against our simulators, on loopback. See README.md. Usage: run_clients.py <dir with the ports file>"""
import asyncio, json, os, sys, time, uuid

D = sys.argv[1]
# Everything here talks to loopback. Some client libraries (websockets 15 and later, used by the webOS client) pick up HTTPS_PROXY from the environment and try to
# tunnel even loopback connections through it, which a sandbox proxy resets; so this process, and only this process, bypasses proxies.
os.environ["NO_PROXY"] = os.environ["no_proxy"] = "*"
ports = dict(l.strip().split("=") for l in open(os.path.join(D, "ports")))
ok = True

def check(name, cond, detail=""):
    global ok
    ok &= bool(cond)
    print(("PASS " if cond else "FAIL ") + name, detail)

# tinytuya, protocol 3.3: reads the data points our simulator holds, then switches the bulb off.
import tinytuya
d = tinytuya.BulbDevice("bf1234567890abcdef12", "127.0.0.171", "0123456789abcdef", version=3.3, port=int(ports["tuya"]), connection_timeout=5)
d.set_socketPersistent(False)
before = d.status()
d.turn_off()
after = d.status()
check("tinytuya reads and writes", before["dps"]["20"] is True and after["dps"]["20"] is False, json.dumps(after)[:100])

# tinytuya, protocols 3.4 and 3.5: the session-key handshake, then HMAC (3.4) or AES-GCM (3.5) frames, from the reference client.
for ver, addr, key in ((3.4, "127.0.0.176", "tuya34"), (3.5, "127.0.0.177", "tuya35")):
    d = tinytuya.BulbDevice("bf1234567890abcdef12", addr, "0123456789abcdef", version=ver, port=int(ports[key]), connection_timeout=5)
    d.set_socketPersistent(False)
    before = d.status()
    d.turn_off()
    after = d.status()
    check("tinytuya %s reads and writes" % ver, before.get("dps", {}).get("20") is True and after.get("dps", {}).get("20") is False, json.dumps(after)[:100])

# python-kasa, legacy XOR protocol.
from kasa import DeviceConfig
from kasa.deviceconfig import DeviceConnectionParameters, DeviceFamily, DeviceEncryptionType
from kasa.protocols import IotProtocol
from kasa.transports import XorTransport
async def kasa():
    cfg = DeviceConfig(host="127.0.0.172", port_override=int(ports["kasa"]), connection_type=DeviceConnectionParameters(DeviceFamily.IotSmartPlugSwitch, DeviceEncryptionType.Xor))
    p = IotProtocol(transport=XorTransport(config=cfg))
    info = (await p.query({"system": {"get_sysinfo": {}}}))["system"]["get_sysinfo"]
    await p.query({"system": {"set_relay_state": {"state": 1}}})
    now = (await p.query({"system": {"get_sysinfo": {}}}))["system"]["get_sysinfo"]
    await p.close()
    return info, now
info, now = asyncio.run(kasa())
check("python-kasa reads and switches", info["relay_state"] == 0 and now["relay_state"] == 1, info["model"])

# pychromecast: connects over TLS, reads the receiver status, sets the volume and gets it acknowledged.
import pychromecast
cc = pychromecast.get_chromecast_from_host(("127.0.0.173", int(ports["cast"]), uuid.uuid4(), "Chromecast", "Living room TV"), timeout=10, tries=1)
cc.wait(timeout=10)
cc.set_volume(0.33)
time.sleep(1)
check("pychromecast reads status and sets volume", cc.status and abs(cc.status.volume_level - 0.33) < 0.01, str(cc.status.volume_level))
cc.disconnect()

# androidtvremote2: pairs with the code the "TV" shows, connects, sends a key.
from androidtvremote2 import AndroidTVRemote
async def atv():
    r = AndroidTVRemote("interop-test", os.path.join(D, "cert.pem"), os.path.join(D, "key.pem"), "127.0.0.174", api_port=int(ports["atvremote"]), pair_port=int(ports["atvpair"]))
    await r.async_generate_cert_if_missing()
    await r.async_start_pairing()
    for _ in range(50):
        if os.path.exists(os.path.join(D, "atv.code")): break
        await asyncio.sleep(0.2)
    await asyncio.sleep(1)  # a person takes a moment to read the code off the TV
    await r.async_finish_pairing(open(os.path.join(D, "atv.code")).read().strip())
    await r.async_connect()
    r.send_key_command("DPAD_UP")
    await asyncio.sleep(1)
    r.disconnect()
    return True
# Roughly one fresh pairing in ten ends with the connection closed before the TV's answer arrives; the cause is not found (see docs/protocols/interop.md), so retry.
atv_ok, tries = False, 0
while not atv_ok and tries < 4:
    tries += 1
    for f in ("atv.code",):
        if os.path.exists(os.path.join(D, f)): os.remove(os.path.join(D, f))
    for f in ("cert.pem", "key.pem"):
        if os.path.exists(os.path.join(D, f)): os.remove(os.path.join(D, f))
    try:
        atv_ok = asyncio.run(atv())
    except Exception as e:
        print("  androidtvremote2 attempt", tries, "failed:", type(e).__name__)
check("androidtvremote2 pairs, connects and sends a key", atv_ok, f"(attempts: {tries})")

# paho-mqtt: a generic MQTT client over TLS with the printer's login reads the printer's report topic from our simulated broker.
import ssl, threading
import paho.mqtt.client as mqtt
got = threading.Event(); report = {}
def on_connect(c, u, f, rc, props=None):
    c.subscribe("device/0948AD4A0B00001/report")
    c.publish("device/0948AD4A0B00001/request", json.dumps({"pushing": {"sequence_id": "1", "command": "pushall"}}))
def on_message(c, u, m):
    report["payload"] = json.loads(m.payload); got.set()
c = mqtt.Client(mqtt.CallbackAPIVersion.VERSION2, client_id="interop")
c.username_pw_set("bblp", "12345678")
c.tls_set(cert_reqs=ssl.CERT_NONE); c.tls_insecure_set(True)
c.on_connect = on_connect; c.on_message = on_message
c.connect("127.0.0.175", int(ports["bambu"]), 10); c.loop_start()
check("paho-mqtt connects with the access code and receives the printer report", got.wait(10) and "print" in report["payload"], str(list(report.get("payload", {}).get("print", {}))[:5]))
c.loop_stop(); c.disconnect()

# govee-local-api (the client library of Home Assistant's Govee LAN integration): discovery is aimed at our simulated lamp's address instead of the multicast group.
from govee_local_api import GoveeController
async def govee():
    found = []
    c = GoveeController(
        broadcast_address="127.0.0.178", broadcast_port=int(ports["govee_scan"]), listening_addresses="127.0.0.1", listening_port=14002,
        device_command_port=int(ports["govee_cmd"]), discovery_enabled=True, update_enabled=False,
        discovered_callback=lambda dev, new: found.append(dev) or True,
    )
    await c.start()
    for _ in range(60):
        if found: break
        await asyncio.sleep(0.1)
    if not found:
        await asyncio.wait_for(c.cleanup().wait(), 3); return None
    dev = found[0]
    out = {"fingerprint": dev.fingerprint, "sku": dev.sku, "ip": dev.ip}
    await dev.turn_off(); await asyncio.sleep(0.3)
    await dev.set_brightness(40); await asyncio.sleep(0.3)
    await dev.set_rgb_color(10, 200, 30); await asyncio.sleep(0.3)
    c.send_update_message(); await asyncio.sleep(0.6)   # asks the lamp for its state, which the library parses
    out.update(on=dev.on, brightness=dev.brightness, rgb=dev.rgb_color)
    await dev.turn_on(); await asyncio.sleep(0.3)
    await dev.set_temperature(3300); await asyncio.sleep(0.3)
    c.send_update_message(); await asyncio.sleep(0.6)
    out.update(on2=dev.on, kelvin=dev.temperature_color)
    await asyncio.wait_for(c.cleanup().wait(), 3)
    return out
g = asyncio.run(govee())
check("govee-local-api discovers, controls and reads the lamp", g is not None and g["sku"] == "H6076" and g["on"] is False and g["brightness"] == 40 and tuple(g["rgb"]) == (10, 200, 30) and g["on2"] is True and g["kelvin"] == 3300, json.dumps(g))

# bscpylgtv (an LG webOS client library): registration with the TV's prompt, then volume, mute, input and power requests over the secure websocket. It sends numeric
# request ids and expects them echoed as numbers, as a real TV does. The simulator's state afterwards (webos.volume=23 webos.muted=true webos.input=HDMI_3 webos.on=false)
# is in the host's "final" file.
from bscpylgtv import WebOsClient
async def webos():
    import tempfile
    c = await WebOsClient.create("127.0.0.179", key_file_path=os.path.join(tempfile.mkdtemp(), "keys.sqlite"), timeout_connect=5, ping_interval=None, states=[])
    c.port = int(ports["webos"])      # the library assumes 3001; the simulator picks its own port
    await c.connect()
    key = c.client_key
    before = await c.get_volume()
    await c.set_volume(23)
    after = await c.get_volume()
    await c.set_mute(True)
    await c.set_input("HDMI_3")
    await c.power_off()
    await c.disconnect()
    return key, before, after
try:
    key, before, after = asyncio.run(webos())
    check("bscpylgtv registers with the TV prompt, sets volume, mute and input, powers off", bool(key) and before == 14 and after == 23, "key issued; volume %s then %s" % (before, after))
except Exception as e:
    check("bscpylgtv registers with the TV prompt, sets volume, mute and input, powers off", False, "%s: %s" % (type(e).__name__, e))

sys.exit(0 if ok else 1)

