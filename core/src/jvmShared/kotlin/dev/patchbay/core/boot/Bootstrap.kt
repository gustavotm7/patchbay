package dev.patchbay.core.boot

import dev.patchbay.core.adapter.DeviceAdapter
import dev.patchbay.core.adapter.govee.GoveeLanAdapter
import dev.patchbay.core.home.LanHome
import dev.patchbay.core.home.LiveHome
import dev.patchbay.core.log.SessionLog
import dev.patchbay.core.net.NetGuard
import dev.patchbay.core.result.PatchbayResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.io.File

/** Everything a client needs, created once at process start. */
public class Services internal constructor(
    public val paths: Paths,
    public val netGuard: NetGuard,
    public val log: SessionLog,
    public val adapters: List<DeviceAdapter>,
    public val home: LiveHome,
    private val scope: CoroutineScope,
) : AutoCloseable {
    override fun close() {
        adapters.forEach { (it as? AutoCloseable)?.close() }
        scope.cancel()
        log.info("boot", "shutdown")
        log.close()
    }
}

public data class Paths(val root: File) {
    val logs: File get() = File(root, "logs")
    val data: File get() = File(root, "data")
}

/**
 * The single place where process-wide resources are created, in the order the brief requires:
 *
 * 1. paths
 * 2. encrypted storage — **not yet** (ADR 0003; next Phase 1 step). Until it exists, nothing is persisted:
 *    no pairings, no layout. Devices are rediscovered at each start.
 * 3. keystore-wrapped keys — arrives with storage.
 * 4. NetGuard
 * 5. logger
 * 6. adapter registry
 *
 * Nothing creates files lazily after this returns.
 */
public object Bootstrap {

    public fun start(platform: String, root: File): Services {
        // 1. paths
        val paths = Paths(root)
        paths.logs.mkdirs()
        paths.data.mkdirs()
        // 2–3. storage + keys: pending (see KDoc).
        // 4. NetGuard
        val netGuard = NetGuard()
        // 5. logger
        val log = SessionLog.start(paths.logs, platform)
        log.info("boot", "patchbay starting on $platform; storage=none (Phase 1 pending)")
        // 6. adapters
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val govee = GoveeLanAdapter(netGuard, scope)
        val started = govee.start()
        val adapters = listOf<DeviceAdapter>(govee)
        val home = LanHome(netGuard, adapters, scope, log)
        if (started is PatchbayResult.Err) home.reportStartupProblem(started.error.message)
        log.info("boot", "ready: ${adapters.size} adapter(s)")
        return Services(paths, netGuard, log, adapters, home, scope)
    }
}
