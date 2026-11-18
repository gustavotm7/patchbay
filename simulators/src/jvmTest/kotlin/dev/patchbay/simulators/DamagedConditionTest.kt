package dev.patchbay.simulators

import dev.patchbay.core.home.LanHome
import dev.patchbay.core.net.NetGuard
import dev.patchbay.core.store.InMemoryHomeStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.test.Test
import kotlin.test.assertEquals

/** A condition that cannot be read back must never turn into "no condition": that automation is switched off, the others are untouched. */
class DamagedConditionTest {
    @Test
    fun anAutomationWhoseConditionIsDamagedIsSwitchedOffNotRunUnconditionally(): Unit {
        val store = InMemoryHomeStore()
        store.putSetting(
            "automations",
            """[
              {"id":"a1","name":"Damaged window","sceneId":"s","hour":19,"minute":0,"days":[1,2,3],"enabled":true,"condition":{"from":"18:00","until":"nonsense"}},
              {"id":"a2","name":"Half a window","sceneId":"s","hour":19,"minute":0,"days":[1,2,3],"enabled":true,"condition":{"from":"sunset+0"}},
              {"id":"a3","name":"Fine window","sceneId":"s","hour":19,"minute":0,"days":[1,2,3],"enabled":true,"condition":{"from":"18:00","until":"23:00"}},
              {"id":"a4","name":"Away only","sceneId":"s","hour":19,"minute":0,"days":[1,2,3],"enabled":true,"condition":{"away":true}},
              {"id":"a5","name":"No condition","sceneId":"s","hour":19,"minute":0,"days":[1,2,3],"enabled":true}
            ]""",
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val home = LanHome(NetGuard(), emptyList(), scope, null, store)
            assertEquals(
                mapOf("a1" to false, "a2" to false, "a3" to true, "a4" to true, "a5" to true),
                home.automations.value.associate { it.id to it.enabled },
            )
        } finally { scope.cancel() }
    }
}
