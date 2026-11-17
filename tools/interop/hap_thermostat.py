import logging, sys, signal
from pyhap.accessory import Accessory
from pyhap.accessory_driver import AccessoryDriver
from pyhap.const import CATEGORY_THERMOSTAT

class T(Accessory):
    category = CATEGORY_THERMOSTAT
    def __init__(self, *a, **k):
        super().__init__(*a, **k)
        s = self.add_preload_service('Thermostat')
        self.cur = s.get_characteristic('CurrentTemperature'); self.cur.set_value(21.5)
        self.tgt = s.get_characteristic('TargetTemperature'); self.tgt.set_value(20.0)
        self.tgt.setter_callback = lambda v: print("SET target", v, flush=True)
        s.get_characteristic('TargetHeatingCoolingState').set_value(1)
        s.get_characteristic('CurrentHeatingCoolingState').set_value(1)
        s.get_characteristic('TargetHeatingCoolingState').setter_callback = lambda v: print("SET mode", v, flush=True)

logging.basicConfig(level=logging.INFO)
port = int(sys.argv[1])
driver = AccessoryDriver(port=port, address="127.0.0.1", persist_file=sys.argv[2], pincode=b"314-15-926", mac="AA:BB:CC:11:22:33", advertiser=None) if False else AccessoryDriver(port=port, address="127.0.0.1", persist_file=sys.argv[2], pincode=b"314-15-926")
driver.add_accessory(T(driver, "Hallway"))
signal.signal(signal.SIGTERM, driver.signal_handler)
driver.start()
