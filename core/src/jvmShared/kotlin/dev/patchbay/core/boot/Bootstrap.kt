package dev.patchbay.core.boot

import app.cash.sqldelight.db.SqlDriver
import dev.patchbay.core.adapter.DeviceAdapter
import dev.patchbay.core.db.PatchbayDb
import dev.patchbay.core.store.DataKey
import dev.patchbay.core.store.HomeStore
import dev.patchbay.core.store.KeyWrapper
import dev.patchbay.core.store.SqlHomeStore
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
    public val store: HomeStore,
    public val keyKind: String,
    private val driver: SqlDriver,
    public val netGuard: NetGuard,
    public val log: SessionLog,
    public val adapters: List<DeviceAdapter>,
    public val home: LiveHome,
    private val scope: CoroutineScope,
) : AutoCloseable {
    override fun close() {
        adapters.forEach { (it as? AutoCloseable)?.close() }
        scope.cancel()
        driver.close()
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
 * 2. keystore-wrapped data key ([DataKey] + the platform [KeyWrapper])
 * 3. encrypted storage (ADR 0003; the platform opens the driver)
 * 4. NetGuard
 * 5. logger
 * 6. adapter registry
 *
 * Nothing creates files lazily after this returns.
 */
public object Bootstrap {

    /**
     * @param keyWrapper OS protection for the data key (Keystore, DPAPI, or the hub's file fallback).
     * @param openDb opens the encrypted database file with the raw 256-bit key (SQLCipher / sqlite-jdbc-crypt).
     */
    public fun start(platform: String, root: File, keyWrapper: KeyWrapper, openDb: (File, ByteArray) -> SqlDriver): Services {
        // 1. paths
        val paths = Paths(root)
        paths.logs.mkdirs()
        paths.data.mkdirs()
        // 2. keys
        val key = DataKey.loadOrCreate(paths.data, keyWrapper)
        // 3. encrypted storage
        val driver = try {
            openDb(File(paths.data, "patchbay.db"), key)
        } finally {
            DataKey.zeroise(key)
        }
        val store = SqlHomeStore(PatchbayDb(driver))
        // 4. NetGuard
        val netGuard = NetGuard()
        // 5. logger
        val log = SessionLog.start(paths.logs, platform)
        log.info("boot", "patchbay starting on $platform; key=${keyWrapper.kind}" + if (keyWrapper.osProtected) "" else " (NOT OS-protected)")
        // 6. adapters
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val govee = GoveeLanAdapter(netGuard, scope)
        val started = govee.start()
        val adapters = listOf<DeviceAdapter>(govee)
        val home = LanHome(netGuard, adapters, scope, log, store)
        if (started is PatchbayResult.Err) home.reportStartupProblem(started.error.message)
        log.info("boot", "ready: ${adapters.size} adapter(s)")
        return Services(paths, store, keyWrapper.kind, driver, netGuard, log, adapters, home, scope)
    }
}
