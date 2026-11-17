package dev.patchbay.simulators

import java.io.File
import java.security.KeyStore
import kotlin.test.Test

/**
 * Not a test: when PATCHBAY_SIM_HOST_DIR is set, it starts a simulated Tuya bulb and Kasa plug on loopback, writes their ports to
 * <dir>/ports, and keeps them up until <dir>/stop exists (at most 10 minutes). It lets independent client libraries (tinytuya,
 * python-kasa) talk to our simulators by hand. Does nothing in a normal run.
 */
class SimulatorHostForInterop {
    @Test
    fun host() {
        val dir = System.getenv("PATCHBAY_SIM_HOST_DIR")?.let(::File) ?: return
        val tuya = TuyaSimulator("127.0.0.171", "bf1234567890abcdef12", "0123456789abcdef")
        val kasa = KasaSimulator("127.0.0.172", "80060000000000000000000000000000000000aa")
        fun ks(n: String) = KeyStore.getInstance("PKCS12").apply { File("src/jvmTest/resources/tls/$n").inputStream().use { load(it, "testpass".toCharArray()) } }
        val cast = CastSimulator("127.0.0.173", ks("device-a.p12"), "testpass".toCharArray())
        val tv = AndroidTvSimulator("127.0.0.174", ks("tv-a.p12"), "testpass".toCharArray())
        try {
            File(dir, "ports").writeText("tuya=${tuya.port}\nkasa=${kasa.port}\ncast=${cast.port}\natvpair=${tv.pairingPort}\natvremote=${tv.remotePort}\n")
            val end = System.nanoTime() + 600_000_000_000L
            while (!File(dir, "stop").exists() && System.nanoTime() < end) {
                tv.displayedCode?.let { File(dir, "atv.code").writeText(it) }
                Thread.sleep(200)
            }
            File(dir, "final").writeText("tuya.dps=${tuya.dps}\nkasa.on=${kasa.on}\ncast.volume=${cast.volume}\natv.code=${tv.displayedCode}\n")
        } finally { tuya.close(); kasa.close(); cast.close(); tv.close() }
    }
}
