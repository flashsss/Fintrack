package com.fintrack.app.analytics

import com.fintrack.app.analytics.insights.Insight
import com.fintrack.app.analytics.insights.InsightRules
import com.fintrack.app.analytics.insights.Reason
import com.fintrack.app.analytics.insights.RuleOutcome
import com.fintrack.app.analytics.metrics.CategoryTotal
import com.fintrack.app.analytics.metrics.PeriodTotals
import org.junit.Assert.assertEquals
import org.junit.Test

class InsightRulesTest {

    private fun increase(current: Long, previous: Long, count: Int = 3) =
        InsightRules.categoryIncreases(listOf(CategoryTotal("food", current, count)), mapOf("food" to previous))

    @Test
    fun `I1 fires exactly at 30 percent and Rp200_000`() {
        // previous 666.667: +30% is 200.000,1 -> use previous 700.000, +210.000 = 30%.
        val fired = increase(current = 910_000, previous = 700_000)
        assertEquals(RuleOutcome.Fired(listOf(Insight.CategoryIncrease("food", 910_000, 700_000, 3, 30.0))), fired)
    }

    @Test
    fun `I1 does not fire just below either threshold`() {
        assertEquals(RuleOutcome.NoFinding, increase(current = 909_999, previous = 700_000)) // 29,99%
        // 40% but only Rp199.999 more.
        assertEquals(RuleOutcome.NoFinding, increase(current = 699_997, previous = 499_998))
    }

    @Test
    fun `I1 requires a previous baseline and enough transactions`() {
        assertEquals(RuleOutcome.NotEnoughData(Reason.NO_ELIGIBLE_CATEGORY), increase(current = 5_000_000, previous = 99_999))
        assertEquals(RuleOutcome.NotEnoughData(Reason.NO_ELIGIBLE_CATEGORY), increase(current = 5_000_000, previous = 1_000_000, count = 2))
        assertEquals(RuleOutcome.NotEnoughData(Reason.NO_ELIGIBLE_CATEGORY), InsightRules.categoryIncreases(emptyList(), emptyMap()))
    }

    @Test
    fun `I1 keeps the three largest absolute increases`() {
        val current = (1..5).map { CategoryTotal("c$it", 1_000_000L + it * 100_000L, 3) }
        val previous = (1..5).associate { "c$it" to 500_000L }
        val fired = InsightRules.categoryIncreases(current, previous) as RuleOutcome.Fired
        assertEquals(listOf("c5", "c4", "c3"), fired.value.map { it.categoryId })
    }

    private fun totals(income: Long, expense: Long) = PeriodTotals(income, expense, 2, 0)

    @Test
    fun `I5 fires at a 10 point drop and not just above it`() {
        // 40% -> 30% = -10 points exactly.
        val fired = InsightRules.savingRateDrop(totals(1_000_000, 700_000), totals(2_000_000, 1_200_000))
        assertEquals(RuleOutcome.Fired(Insight.SavingRateDrop(30.0, 40.0)), fired)
        assertEquals(-10.0, (fired as RuleOutcome.Fired).value.pointChange, 1e-9)
        // 40% -> 30,01%.
        assertEquals(RuleOutcome.NoFinding, InsightRules.savingRateDrop(totals(10_000_000, 6_999_000), totals(2_000_000, 1_200_000)))
    }

    @Test
    fun `I5 needs income now and a meaningful previous income`() {
        assertEquals(RuleOutcome.NotEnoughData(Reason.NO_CURRENT_INCOME), InsightRules.savingRateDrop(totals(0, 500_000), totals(2_000_000, 0)))
        assertEquals(RuleOutcome.NotEnoughData(Reason.LOW_PREVIOUS_INCOME), InsightRules.savingRateDrop(totals(1_000_000, 900_000), totals(999_999, 0)))
    }

    @Test
    fun `I5 with negative saving rates still measures points`() {
        // -20% -> -35% = -15 points.
        val fired = InsightRules.savingRateDrop(totals(1_000_000, 1_350_000), totals(1_000_000, 1_200_000)) as RuleOutcome.Fired
        assertEquals(-15.0, fired.value.pointChange, 1e-9)
    }
}
