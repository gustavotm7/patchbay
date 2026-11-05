package dev.patchbay.hub

import kotlin.system.exitProcess

/**
 * Phase 3 placeholder. The hub will run the same `core` as the clients, headless, for always-on
 * automations. It is a peer, never a required relay (ADR 0001).
 */
public fun main() {
    System.err.println("patchbay-hub: not implemented until Phase 3 (see docs/BRIEF.md §10).")
    exitProcess(2)
}
