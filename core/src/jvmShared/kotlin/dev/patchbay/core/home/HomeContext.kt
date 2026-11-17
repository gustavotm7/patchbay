package dev.patchbay.core.home

import dev.patchbay.core.log.SessionLog
import dev.patchbay.core.store.HomeStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.Json

/**
 * What the parts of the home share: the clock, the scope that runs background work, the saved settings, the log and the current list of devices.
 * [LanHome] builds one and hands it to each part, so a test that gives `LanHome` a fixed clock fixes the time everywhere.
 */
internal class HomeContext(
    val scope: CoroutineScope,
    val clock: () -> Long,
    val zone: java.time.ZoneId,
    val random: kotlin.random.Random,
    val store: HomeStore?,
    val log: SessionLog?,
    val devices: StateFlow<List<LiveDevice>>,
) {
    /** How everything that is saved as a setting is written and read. Unknown fields are ignored so older and newer versions can share a file. */
    val json: Json = Json { ignoreUnknownKeys = true }
}
