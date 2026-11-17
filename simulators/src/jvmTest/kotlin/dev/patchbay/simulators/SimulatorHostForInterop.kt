package dev.patchbay.simulators

import java.io.File
import java.security.KeyStore
import kotlin.test.Test

/**
 * Not a test: when PATCHBAY_SIM_HOST_DIR is set, it starts simulated Tuya bulbs (protocols 3.3, 3.4 and 3.5), a Kasa plug and others on loopback, writes their ports to
 * <dir>/ports, and keeps them up until <dir>/stop exists (at most 10 minutes). It lets independent client libraries (tinytuya,
 * python-kasa) talk to our simulators by hand. Does nothing in a normal run.
 */
class SimulatorHostForInterop {
    @Test
    fun host() {
        val dir = System.getenv("PATCHBAY_SIM_HOST_DIR")?.let(::File) ?: return
        val tuya = TuyaSimulator("127.0.0.171", "bf1234567890abcdef12", "0123456789abcdef")
        val tuya34 = TuyaSimulator("127.0.0.176", "bf1234567890abcdef12", "0123456789abcdef", version = "3.4")
        val tuya35 = TuyaSimulator("127.0.0.177", "bf1234567890abcdef12", "0123456789abcdef", version = "3.5")
        val kasa = KasaSimulator("127.0.0.172", "80060000000000000000000000000000000000aa")
        fun ks(n: String) = KeyStore.getInstance("PKCS12").apply { File("src/jvmTest/resources/tls/$n").inputStream().use { load(it, "testpass".toCharArray()) } }
        val cast = CastSimulator("127.0.0.173", ks("device-a.p12"), "testpass".toCharArray())
        val tv = AndroidTvSimulator("127.0.0.174", ks("tv-a.p12"), "testpass".toCharArray())
        val printer = BambuSimulator("127.0.0.175", "0948AD4A0B00001", "12345678", ks("device-a.p12"), "testpass".toCharArray())
        try {
            File(dir, "ports").writeText("tuya=${tuya.port}\ntuya34=${tuya34.port}\ntuya35=${tuya35.port}\nkasa=${kasa.port}\ncast=${cast.port}\natvpair=${tv.pairingPort}\natvremote=${tv.remotePort}\nbambu=${printer.mqttPort}\n")
            var lastCode: String? = null
            val end = System.nanoTime() + 600_000_000_000L
            while (!File(dir, "stop").exists() && System.nanoTime() < end) {
                tv.displayedCode?.let { if (it != lastCode) { lastCode = it; File(dir, "atv.code").writeText(it) } }
                Thread.sleep(200)
            }
            File(dir, "final").writeText("tuya.dps=${tuya.dps}\ntuya34.dps=${tuya34.dps}\ntuya35.dps=${tuya35.dps}\ntuya34.sessions=${tuya34.sessionsNegotiated}\ntuya35.sessions=${tuya35.sessionsNegotiated}\nkasa.on=${kasa.on}\ncast.volume=${cast.volume}\natv.code=${tv.displayedCode}\n")
        } finally { tuya.close(); tuya34.close(); tuya35.close(); kasa.close(); cast.close(); tv.close(); printer.close() }
    }
}
