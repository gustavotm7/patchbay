package dev.patchbay.simulators

import org.junit.jupiter.api.Assumptions

/**
 * The interop tests need an outside program (a reference library, ffmpeg) started by hand with tools/interop. Without it they must report SKIPPED, not passed:
 * a test that returns early counts as green and inflates the number of tests that really ran.
 */
internal fun skipInterop(why: String): Nothing {
    return Assumptions.abort<Nothing>("interop test skipped: $why (see tools/interop/README.md)")
}
