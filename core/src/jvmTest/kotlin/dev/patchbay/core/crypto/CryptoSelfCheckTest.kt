package dev.patchbay.core.crypto

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CryptoSelfCheckTest {
    @Test
    fun everyAlgorithmPatchbayNeedsIsCheckedAndWorksOnTheJdk() {
        val lines = CryptoSelfCheck.lines()
        assertEquals(8, lines.size)
        assertTrue(lines.all { it.endsWith(": ok") }, lines.toString())
        assertTrue(lines.first().startsWith("ChaCha20-Poly1305"))
        // The report passes through the redactor: a label that looks like an identifier would be shortened there and unreadable on the phone.
        lines.forEach { assertEquals(it, dev.patchbay.core.log.Redactor.redact(it)) }
    }
}
