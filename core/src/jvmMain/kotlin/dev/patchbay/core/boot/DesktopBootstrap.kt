package dev.patchbay.core.boot

import dev.patchbay.core.store.DesktopStorage
import java.io.File

/** Bootstrap for Windows (DPAPI) and the hub (file fallback): encrypted DB via sqlite-jdbc-crypt. */
public object DesktopBootstrap {
    public fun start(platform: String, root: File): Services {
        val data = File(root, "data").apply { mkdirs() }
        return Bootstrap.start(platform, root, DesktopStorage.keyWrapper(data), DesktopStorage::open)
    }
}
