package dev.patchbay.core.adapter

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RecentProblemsTest {
    @Test
    fun keepsTheNewestAndForgetsTheOldest() {
        val p = RecentProblems(capacity = 3)
        for (i in 1..5) p.add("problem $i")
        assertEquals(listOf("problem 3", "problem 4", "problem 5"), p.list())
    }

    @Test
    fun theSameReasonInARowIsOneEntryWithACount() {
        val p = RecentProblems(capacity = 3)
        p.add("timeout")
        repeat(40) { p.add("timeout") }
        p.add("io: SocketException")
        assertEquals(listOf("timeout (x41)", "io: SocketException"), p.list())
        p.add("timeout") // not in a row any more: a new entry
        assertEquals(listOf("timeout (x41)", "io: SocketException", "timeout"), p.list())
    }

    @Test
    fun entriesAreBoundedAndHaveNoControlCharacters() {
        val p = RecentProblems()
        p.add("a\nb\u0000c\r\t" + "x".repeat(1_000))
        p.add("\n\u0007")
        val only = p.list().single()
        assertTrue(only.length <= RecentProblems.MAX_LENGTH)
        assertFalse(only.any { it.isISOControl() })
        assertTrue(only.startsWith("abc"))
    }
}
