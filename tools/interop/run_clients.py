"""Independent client libraries against our simulators, on loopback. See README.md. Usage: run_clients.py <dir with the ports file>"""
import asyncio, json, os, sys, time, uuid

D = sys.argv[1]
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
sys.exit(0 if ok else 1)
