package com.fintrack.app.analytics.metrics

import com.fintrack.app.domain.model.Category
import java.time.LocalDate
import java.time.YearMonth

/** Expense of one category (null = uncategorized) on one Jakarta day. */
data class CategoryDayTotal(val categoryId: String?, val date: LocalDate, val total: Long, val count: Int)

/** Expense of one category in a span. */
data class CategoryTotal(val categoryId: String, val total: Long, val count: Int)

/** A top-level category with its own expense and its children's (docs §5.2, addendum B4). */
data class ParentNode(val categoryId: String, val direct: Long, val children: List<CategoryTotal>) {
    val rolledUp: Long get() = children.fold(direct) { sum, child -> Math.addExact(sum, child.total) }
}

/** Expense outside the category hierarchy: uncategorized (null id) and ids missing from the table. */
data class OutsideHierarchy(val uncategorized: Long, val uncategorizedCount: Int, val unknown: List<CategoryTotal>)

data class CategoryChange(val categoryId: String, val current: Long, val previous: Long) {
    val absolute: Long get() = current - previous

    /** Δ% only against a baseline of at least [baseline] rupiah; small bases make percentages misleading. */
    fun percent(baseline: Long): Double? = if (previous >= baseline && previous > 0) (current - previous) * 100.0 / previous else null
}

object CategoryAnalysis {

    /** Minimum previous expense for showing a percentage change (design decision D2). */
    const val PERCENT_BASELINE = 100_000L

    /** Flat totals per category id (null excluded: see [outsideHierarchy]), largest first. */
    fun flat(days: List<CategoryDayTotal>): List<CategoryTotal> =
        days.filter { it.categoryId != null }
            .groupBy { it.categoryId!! }
            .map { (id, rows) -> CategoryTotal(id, rows.fold(0L) { s, r -> Math.addExact(s, r.total) }, rows.sumOf { it.count }) }
            .sortedWith(compareByDescending<CategoryTotal> { it.total }.thenBy { it.categoryId })

    fun outsideHierarchy(days: List<CategoryDayTotal>, categoriesById: Map<String, Category>): OutsideHierarchy {
        val uncategorized = days.filter { it.categoryId == null }
        return OutsideHierarchy(
            uncategorized = uncategorized.fold(0L) { s, r -> Math.addExact(s, r.total) },
            uncategorizedCount = uncategorized.sumOf { it.count },
            unknown = flat(days).filter { it.categoryId !in categoriesById },
        )
    }

    /**
     * Rolls known categories up to their top-level parent. Uncategorized and unknown ids never enter
     * the hierarchy. Invariant: Σ rolledUp + outside = Σ flat + uncategorized = total expense.
     */
    fun byParent(flat: List<CategoryTotal>, categoriesById: Map<String, Category>): List<ParentNode> {
        val known = flat.filter { it.categoryId in categoriesById }
        val rootOf = { id: String -> categoriesById.getValue(id).parentId?.takeIf { it in categoriesById } ?: id }
        return known.groupBy { rootOf(it.categoryId) }
            .map { (root, members) ->
                ParentNode(
                    categoryId = root,
                    direct = members.filter { it.categoryId == root }.fold(0L) { s, c -> Math.addExact(s, c.total) },
                    children = members.filter { it.categoryId != root }.sortedByDescending { it.total },
                )
            }
            .sortedWith(compareByDescending<ParentNode> { it.rolledUp }.thenBy { it.categoryId })
    }

    /** Sums per-category totals into their top-level parent (same rule as [byParent]); unknown ids are dropped. */
    fun rollUp(totals: Map<String, Long>, categoriesById: Map<String, Category>): Map<String, Long> =
        totals.filterKeys { it in categoriesById }
            .entries
            .groupBy { (id, _) -> categoriesById.getValue(id).parentId?.takeIf { it in categoriesById } ?: id }
            .mapValues { (_, entries) -> entries.fold(0L) { sum, e -> Math.addExact(sum, e.value) } }

    /** Changes per category id between two spans, ordered by the size of the absolute change. */
    fun movers(current: List<CategoryTotal>, previous: Map<String, Long>): List<CategoryChange> {
        val ids = current.map { it.categoryId }.toSet() + previous.keys
        val currentById = current.associate { it.categoryId to it.total }
        return ids.map { CategoryChange(it, currentById[it] ?: 0L, previous[it] ?: 0L) }
            .filter { it.absolute != 0L }
            .sortedWith(compareByDescending<CategoryChange> { kotlin.math.abs(it.absolute) }.thenBy { it.categoryId })
    }

    /** Monthly expense of one category id (null = uncategorized) for the given months, zeros included. */
    fun monthly(days: List<CategoryDayTotal>, categoryId: String?, months: List<YearMonth>): List<Pair<YearMonth, Long>> {
        val byMonth = days.filter { it.categoryId == categoryId }.groupBy { YearMonth.from(it.date) }
        return months.map { month -> month to (byMonth[month]?.fold(0L) { s, r -> Math.addExact(s, r.total) } ?: 0L) }
    }
}
