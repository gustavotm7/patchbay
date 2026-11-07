package dev.patchbay.core.home

import kotlin.test.Test
import kotlin.test.assertEquals

class LedgerCsvTest {
    @Test
    fun quotesFieldsAndEscapesQuotesAndFormulas() {
        val csv = LedgerCsv.of(
            listOf(
                LedgerEntry(1_000, "Floor lamp", "Turned on (from Patchbay)"),
                LedgerEntry(2_000, "=HYPERLINK(\"x\")", "He said \"hi\", twice"),
                LedgerEntry(3_000, "+cmd", "@sum"),
            ),
        ) { "T$it" }
        assertEquals(
            "time_utc,device,event\n" +
                "T1000,\"Floor lamp\",\"Turned on (from Patchbay)\"\n" +
                "T2000,\"'=HYPERLINK(\"\"x\"\")\",\"He said \"\"hi\"\", twice\"\n" +
                "T3000,\"'+cmd\",\"'@sum\"\n",
            csv,
        )
    }

    @Test
    fun emptyLedgerIsJustTheHeader() {
        assertEquals("time_utc,device,event\n", LedgerCsv.of(emptyList()) { "x" })
    }
}
