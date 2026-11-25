package com.fintrack.app.analytics

import com.fintrack.app.analytics.insights.InsightRules
import com.fintrack.app.analytics.insights.Reason
import com.fintrack.app.analytics.insights.RuleOutcome
import com.fintrack.app.analytics.metrics.MerchantAnalysis
import com.fintrack.app.analytics.metrics.MerchantKey
import com.fintrack.app.analytics.metrics.RawMerchantTotal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MerchantAnalysisTest {

    @Test
    fun `normalization trims, collapses whitespace and ignores case, without fuzzy matching`() {
        assertEquals(MerchantKey.of("Indomaret"), MerchantKey.of("  INDOMARET \t"))
        assertEquals(MerchantKey.of("Warung Bu Tini"), MerchantKey.of("warung   bu\ntini"))
        assertEquals(MerchantKey.of("CAFÉ Ébène"), MerchantKey.of("café ébène")) // Unicode-aware
        assertNotEquals(MerchantKey.of("Indomaret"), MerchantKey.of("Indomaret Point"))
        assertNull(MerchantKey.of(null))
        assertNull(MerchantKey.of("   "))
    }

    private fun raw(name: String?, total: Long, count: Int) = RawMerchantTotal(name, total, count)

    @Test
    fun `variants merge into one merchant named after the most frequent spelling`() {
        val summary = MerchantAnalysis.summarize(
            listOf(raw("Indomaret", 100_000, 2), raw(" indomaret ", 50_000, 5), raw("Alfamart", 300_000, 1), raw(null, 40_000, 2), raw("  ", 10_000, 1)),
        )
        assertEquals(listOf("Alfamart", "indomaret"), summary.merchants.map { it.displayName })
        val indomaret = summary.merchants[1]
        assertEquals(150_000L, indomaret.total)
        assertEquals(7, indomaret.count)
        assertEquals(150_000.0 / 7, indomaret.mean, 1e-9)
        assertEquals(500_000L, summary.totalExpense)
        assertEquals(50_000L, summary.withoutMerchant)
        assertEquals(3, summary.withoutMerchantCount)
        assertEquals(90.0, summary.coveragePercent!!, 1e-9)
        assertEquals(30.0, summary.share(indomaret)!!, 1e-9)
    }

    @Test
    fun `new merchants use the normalized lookback`() {
        val current = MerchantAnalysis.summarize(listOf(raw("Kopi Kenangan", 50_000, 2), raw("Gojek", 20_000, 1)))
        assertEquals(listOf("Kopi Kenangan"), MerchantAnalysis.newMerchants(current, listOf("  GOJEK ")).map { it.displayName })
    }

    @Test
    fun `changes need three previous transactions and are ordered by absolute total change`() {
        val previous = MerchantAnalysis.summarize(listOf(raw("A", 300_000, 3), raw("B", 100_000, 4), raw("C", 900_000, 2), raw("D", 50_000, 3)))
        val current = MerchantAnalysis.summarize(listOf(raw("A", 350_000, 4), raw("B", 400_000, 6), raw("C", 10_000, 1), raw("D", 50_000, 3)))
        val changes = MerchantAnalysis.changes(current, previous)
        assertEquals(listOf("B", "A"), changes.map { it.displayName }) // C has 2 previous; D unchanged
        assertEquals(300_000L, changes[0].totalChange)
        assertEquals(2, changes[0].countChange)
    }

    @Test
    fun `I3 fires at 30 percent share with 3 transactions when coverage is at least 50 percent`() {
        val fired = InsightRules.merchantConcentration(MerchantAnalysis.summarize(listOf(raw("Toko X", 300_000, 3), raw("Lain", 200_000, 5), raw(null, 500_000, 2))))
        assertTrue(fired is RuleOutcome.Fired)
        assertEquals(30.0, (fired as RuleOutcome.Fired).value.sharePercent, 1e-9)
        // Just below 30% and every other merchant below 30% too.
        assertEquals(
            RuleOutcome.NoFinding,
            InsightRules.merchantConcentration(
                MerchantAnalysis.summarize(listOf(raw("Toko X", 299_999, 3), raw("B", 250_000, 5), raw("C", 250_000, 5), raw("D", 200_001, 5))),
            ),
        )
        // 90% share but only 2 transactions; the other merchant has 10%.
        assertEquals(RuleOutcome.NoFinding, InsightRules.merchantConcentration(MerchantAnalysis.summarize(listOf(raw("Toko X", 900_000, 2), raw("Lain", 100_000, 5)))))
        assertEquals(
            RuleOutcome.NotEnoughData(Reason.LOW_MERCHANT_COVERAGE),
            InsightRules.merchantConcentration(MerchantAnalysis.summarize(listOf(raw("Toko X", 499_999, 3), raw(null, 500_001, 3)))),
        )
        assertEquals(RuleOutcome.NotEnoughData(Reason.NO_EXPENSE), InsightRules.merchantConcentration(MerchantAnalysis.summarize(emptyList())))
    }
}
