package com.fintrack.app.analytics

import com.fintrack.app.analytics.metrics.CategoryAnalysis
import com.fintrack.app.analytics.metrics.CategoryChange
import com.fintrack.app.analytics.metrics.CategoryDayTotal
import com.fintrack.app.analytics.metrics.CategoryTotal
import com.fintrack.app.domain.model.Category
import com.fintrack.app.domain.model.CategoryType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth

class CategoryAnalysisTest {

    private val now = Instant.EPOCH
    private fun cat(id: String, name: String, parent: String? = null, archived: Boolean = false) =
        Category(id, name, CategoryType.EXPENSE, parent, null, null, false, archived, now, now)

    private val food = cat("food", "Makanan")
    private val cafe = cat("cafe", "Kafe", parent = "food")
    private val restaurant = cat("resto", "Restoran", parent = "food")
    private val transport = cat("trans", "Transportasi", archived = true)
    private val twin = cat("food2", "Makanan") // same name, different id
    private val byId = listOf(food, cafe, restaurant, transport, twin).associateBy { it.id }

    private val day = LocalDate.of(2026, 11, 1)
    private fun row(id: String?, total: Long, count: Int = 1, date: LocalDate = day) = CategoryDayTotal(id, date, total, count)

    private val days = listOf(
        row("food", 100), row("cafe", 50, 2), row("cafe", 25, date = day.plusDays(1)), row("resto", 75),
        row("trans", 300), row("food2", 40), row(null, 60, 3), row("deleted-id", 20),
    )

    @Test
    fun `flat totals are per id, keep same-name categories apart and exclude null`() {
        val flat = CategoryAnalysis.flat(days)
        assertEquals(listOf("trans", "food", "cafe", "resto", "food2", "deleted-id"), flat.map { it.categoryId })
        assertEquals(CategoryTotal("cafe", 75, 3), flat.single { it.categoryId == "cafe" })
        assertEquals(40L, flat.single { it.categoryId == "food2" }.total)
    }

    @Test
    fun `parents roll up children and the null and unknown buckets stay outside`() {
        val flat = CategoryAnalysis.flat(days)
        val parents = CategoryAnalysis.byParent(flat, byId)
        val outside = CategoryAnalysis.outsideHierarchy(days, byId)

        val foodNode = parents.single { it.categoryId == "food" }
        assertEquals(100L, foodNode.direct)
        assertEquals(250L, foodNode.rolledUp) // 100 + cafe 75 + resto 75
        assertEquals(setOf("cafe", "resto"), foodNode.children.map { it.categoryId }.toSet())
        assertEquals(60L, outside.uncategorized)
        assertEquals(3, outside.uncategorizedCount)
        assertEquals(listOf("deleted-id"), outside.unknown.map { it.categoryId })
        // Invariant: Σ rolled up + outside = Σ flat + uncategorized = total expense.
        val total = days.sumOf { it.total }
        assertEquals(total, parents.sumOf { it.rolledUp } + outside.uncategorized + outside.unknown.sumOf { it.total })
        assertEquals(total, flat.sumOf { it.total } + outside.uncategorized)
    }

    @Test
    fun `only null expenses produce an empty hierarchy`() {
        val nullOnly = listOf(row(null, 10), row(null, 5))
        assertEquals(emptyList<Any>(), CategoryAnalysis.byParent(CategoryAnalysis.flat(nullOnly), byId))
        assertEquals(15L, CategoryAnalysis.outsideHierarchy(nullOnly, byId).uncategorized)
    }

    @Test
    fun `child of an archived parent rolls up into that parent`() {
        val archivedParent = byId + ("food" to food.copy(isArchived = true))
        val parents = CategoryAnalysis.byParent(CategoryAnalysis.flat(days), archivedParent)
        assertEquals(250L, parents.single { it.categoryId == "food" }.rolledUp)
    }

    @Test
    fun `previous totals roll up with the same hierarchy`() {
        assertEquals(mapOf("food" to 60L, "trans" to 5L), CategoryAnalysis.rollUp(mapOf("cafe" to 10L, "food" to 50L, "trans" to 5L, "gone" to 99L), byId))
    }

    @Test
    fun `movers are ordered by absolute change and percent needs a baseline`() {
        val current = listOf(CategoryTotal("a", 500_000, 3), CategoryTotal("b", 90_000, 2), CategoryTotal("c", 100_000, 1))
        val movers = CategoryAnalysis.movers(current, mapOf("a" to 400_000, "b" to 10_000, "d" to 300_000, "c" to 100_000))
        assertEquals(listOf("d", "a", "b"), movers.map { it.categoryId }) // c unchanged is omitted
        assertEquals(-300_000L, movers.first().absolute)
        assertEquals(25.0, movers[1].percent(CategoryAnalysis.PERCENT_BASELINE)!!, 1e-9)
        assertNull("baseline 10.000 is below Rp100.000", movers[2].percent(CategoryAnalysis.PERCENT_BASELINE))
        assertEquals(0.0, CategoryChange("x", 100_000, 100_000).percent(CategoryAnalysis.PERCENT_BASELINE)!!, 1e-9) // exactly at baseline
    }

    @Test
    fun `monthly series includes months without expense`() {
        val rows = listOf(row("food", 10, date = LocalDate.of(2026, 9, 3)), row("food", 5, date = LocalDate.of(2026, 11, 2)), row("cafe", 99, date = LocalDate.of(2026, 10, 1)))
        val months = listOf(YearMonth.of(2026, 9), YearMonth.of(2026, 10), YearMonth.of(2026, 11))
        assertEquals(listOf(10L, 0L, 5L), CategoryAnalysis.monthly(rows, "food", months).map { it.second })
    }
}
