package com.fintrack.app.analytics

import com.fintrack.app.analytics.metrics.BudgetAlertKind
import com.fintrack.app.analytics.metrics.BudgetAlertRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BudgetAlertRulesTest {
    @Test
    fun `near limit triggers exactly when 80 percent is reached`() {
        assertEquals(BudgetAlertKind.NEAR_LIMIT, BudgetAlertRules.crossed(before = 799_999, after = 800_000, limit = 1_000_000))
        assertNull(BudgetAlertRules.crossed(before = 700_000, after = 799_999, limit = 1_000_000))
        assertNull("already past 80%", BudgetAlertRules.crossed(before = 800_000, after = 900_000, limit = 1_000_000))
        assertEquals(BudgetAlertKind.NEAR_LIMIT, BudgetAlertRules.crossed(before = 0, after = 1_000_000, limit = 1_000_000)) // exactly 100% is not over
    }

    @Test
    fun `over limit wins when one expense jumps past 100 percent`() {
        assertEquals(BudgetAlertKind.OVER_LIMIT, BudgetAlertRules.crossed(before = 500_000, after = 1_000_001, limit = 1_000_000))
        assertEquals(BudgetAlertKind.OVER_LIMIT, BudgetAlertRules.crossed(before = 1_000_000, after = 1_000_001, limit = 1_000_000))
        assertNull("already over", BudgetAlertRules.crossed(before = 1_100_000, after = 1_200_000, limit = 1_000_000))
    }
}
