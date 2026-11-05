package dev.patchbay.core.discovery

import dev.patchbay.core.net.NetGuard
import dev.patchbay.core.result.PatchbayResult
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlin.random.Random

/**
 * The network census (brief §5, Inventory): who answers on this LAN, and what they say they are.
 *
 * Sends, once per run, from one ephemeral UDP port:
 * - an mDNS DNS-SD meta-query plus PTR queries for the service types we care about. Because the source
 *   port isn't 5353, responders reply by unicast (RFC 6762 §6.7), so we never compete for port 5353.
 * - an SSDP `M-SEARCH ssdp:all` with MX=2.
 * Then it listens for [windowMs]. Replies are grouped by their LAN source address; non-LAN sources are
 * dropped by NetGuard before parsing. Polite by construction: two multicast datagrams per run.
 */
public class NetworkCensus(
    private val netGuard: NetGuard,
    private val config: Config = Config(),
) {
    public data class Config(
        val mdnsTarget: String = Dns.MDNS_GROUP_V4,
        val mdnsPort: Int = Dns.MDNS_PORT,
        val ssdpTarget: String = Ssdp.GROUP_V4,
        val ssdpPort: Int = Ssdp.PORT,
        val windowMs: Long = 3_000,
        val bindLoopbackOnly: Boolean = false,
    )

    public suspend fun run(): PatchbayResult<List<Finding>> {
        val sock = when (val r = netGuard.openUdp(0, config.bindLoopbackOnly)) {
            is PatchbayResult.Ok -> r.value
            is PatchbayResult.Err -> return r
        }
        sock.use { s ->
            val mdns = netGuard.address(config.mdnsTarget)
            val ssdp = netGuard.address(config.ssdpTarget)
            if (mdns is PatchbayResult.Ok) s.send(mdns.value, config.mdnsPort, Dns.ptrQuery(Random.nextInt(1, 0xFFFF), QUERY_TYPES))
            if (ssdp is PatchbayResult.Ok) s.send(ssdp.value, config.ssdpPort, Ssdp.search())

            val acc = LinkedHashMap<String, Builder>()
            val deadline = System.nanoTime() + config.windowMs * 1_000_000
            while (currentCoroutineContext().isActive) {
                val left = ((deadline - System.nanoTime()) / 1_000_000).toInt()
                if (left <= 0) break
                val d = (s.receive(9_000, left) as? PatchbayResult.Ok)?.value ?: continue
                val b = acc.getOrPut(d.from.literal) { Builder(d.from.literal) }
                if (looksLikeHttp(d.payload)) {
                    (Ssdp.parse(d.payload) as? PatchbayResult.Ok)?.value?.let(b::add)
                } else {
                    (Dns.parse(d.payload) as? PatchbayResult.Ok)?.value?.takeIf { it.isResponse }?.let(b::add)
                }
            }
            return PatchbayResult.Ok(acc.values.filter { it.seen }.map { it.build() }.sortedBy { it.address })
        }
    }

    private class Builder(val address: String) {
        var seen = false
        val hosts = LinkedHashSet<String>()
        val services = LinkedHashSet<String>()
        val ports = LinkedHashSet<Int>()
        var friendly: String? = null
        var model: String? = null
        var server: String? = null

        fun add(m: Dns.Message) {
            seen = true
            for (r in m.records) when (r) {
                is Dns.Record.Ptr -> {
                    // Meta-query answers name service types; others name instances of a type.
                    if (r.name == META) services += serviceType(r.target) else services += serviceType(r.name)
                }
                is Dns.Record.Srv -> { ports += r.port; hosts += r.target.removeSuffix(".local") }
                is Dns.Record.Txt -> {
                    friendly = friendly ?: r.entries["fn"]?.takeIf { it.isNotBlank() }
                    model = model ?: (r.entries["md"] ?: r.entries["model"])?.takeIf { it.isNotBlank() }
                }
                is Dns.Record.A, is Dns.Record.Aaaa -> hosts += r.name.removeSuffix(".local")
            }
        }

        fun add(r: Ssdp.Reply) {
            seen = true
            r.st?.let { services += it.take(96) }
            server = server ?: r.server
        }

        fun build() = Finding(address, hosts, services, ports, friendly, model, server)
    }

    public companion object {
        private const val META = "_services._dns-sd._udp.local"

        /** Brief §5: browse all common types, plus the meta-query for everything else. */
        public val QUERY_TYPES: List<String> = listOf(
            META, "_googlecast._tcp.local", "_hap._tcp.local", "_esphomelib._tcp.local", "_matter._tcp.local",
            "_matterc._udp.local", "_ipp._tcp.local", "_airplay._tcp.local", "_androidtvremote2._tcp.local",
            "_http._tcp.local", "_printer._tcp.local", "_raop._tcp.local", "_spotify-connect._tcp.local",
        )

        /** "Living-TV._googlecast._tcp.local" or "_googlecast._tcp.local" -> "_googlecast._tcp". */
        public fun serviceType(name: String): String {
            val parts = name.removeSuffix(".local").split('.')
            val i = parts.indexOfFirst { it.startsWith("_") }
            return if (i < 0) name else parts.drop(i).joinToString(".")
        }

        private fun looksLikeHttp(b: ByteArray): Boolean {
            val head = b.copyOf(minOf(b.size, 8)).decodeToString()
            return head.startsWith("HTTP/") || head.startsWith("NOTIFY")
        }

    }
}
