package dev.patchbay.core.home

import kotlinx.serialization.Serializable

@Serializable
internal data class StoredLedgerEntry(val t: Long, val d: String, val e: String)
