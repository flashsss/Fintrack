package com.fintrack.app.analytics.metrics

import java.util.Locale

/** SQL total for one raw merchant text; null or blank = no merchant. */
data class RawMerchantTotal(val merchant: String?, val total: Long, val count: Int)

/**
 * Merchant identity (docs §5.3): trimmed, inner whitespace collapsed, lower-cased with Locale.ROOT.
 * No fuzzy matching: "Indomaret" and "Indomaret Point" stay different merchants.
 */
@JvmInline
value class MerchantKey private constructor(val value: String) {
    companion object {
        private val WHITESPACE = Regex("\\s+")

        fun of(raw: String?): MerchantKey? =
            raw?.trim()?.replace(WHITESPACE, " ")?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() }?.let(::MerchantKey)
    }
}

data class MerchantStats(val key: MerchantKey, val displayName: String, val total: Long, val count: Int) {
    val mean: Double get() = total.toDouble() / count
}

data class MerchantChange(val key: MerchantKey, val displayName: String, val current: MerchantStats?, val previous: MerchantStats) {
    val totalChange: Long get() = (current?.total ?: 0L) - previous.total
    val countChange: Int get() = (current?.count ?: 0) - previous.count
}

data class MerchantSummary(
    val merchants: List<MerchantStats>,
    val totalExpense: Long,
    val withoutMerchant: Long,
    val withoutMerchantCount: Int,
) {
    /** Share of expense that has a merchant, 0..100; null when there is no expense. */
    val coveragePercent: Double? get() = if (totalExpense == 0L) null else (totalExpense - withoutMerchant) * 100.0 / totalExpense

    fun share(stats: MerchantStats): Double? = if (totalExpense == 0L) null else stats.total * 100.0 / totalExpense
}

object MerchantAnalysis {

    /** Minimum previous transactions before a merchant's change is reported. */
    const val MIN_PREVIOUS_COUNT = 3

    /** Merges raw texts into normalized merchants, largest total first (ties by key). */
    fun summarize(rows: List<RawMerchantTotal>): MerchantSummary {
        val withoutMerchant = rows.filter { MerchantKey.of(it.merchant) == null }
        val merchants = rows.mapNotNull { row -> MerchantKey.of(row.merchant)?.let { it to row } }
            .groupBy({ it.first }, { it.second })
            .map { (key, variants) ->
                MerchantStats(
                    key = key,
                    // Most frequent original spelling; ties by alphabetical order for a stable name.
                    displayName = variants.sortedWith(compareByDescending<RawMerchantTotal> { it.count }.thenBy { it.merchant!!.trim() })
                        .first().merchant!!.trim().replace(Regex("\\s+"), " "),
                    total = variants.fold(0L) { s, v -> Math.addExact(s, v.total) },
                    count = variants.sumOf { it.count },
                )
            }
            .sortedWith(compareByDescending<MerchantStats> { it.total }.thenBy { it.key.value })
        return MerchantSummary(
            merchants = merchants,
            totalExpense = rows.fold(0L) { s, r -> Math.addExact(s, r.total) },
            withoutMerchant = withoutMerchant.fold(0L) { s, r -> Math.addExact(s, r.total) },
            withoutMerchantCount = withoutMerchant.sumOf { it.count },
        )
    }

    /** Merchants in [current] whose key does not occur among the [lookback] raw texts. */
    fun newMerchants(current: MerchantSummary, lookback: List<String>): List<MerchantStats> {
        val seen = lookback.mapNotNull(MerchantKey::of).toSet()
        return current.merchants.filter { it.key !in seen }
    }

    /** Changes for merchants with at least [MIN_PREVIOUS_COUNT] previous transactions, by absolute total change. */
    fun changes(current: MerchantSummary, previous: MerchantSummary): List<MerchantChange> {
        val currentByKey = current.merchants.associateBy { it.key }
        return previous.merchants.filter { it.count >= MIN_PREVIOUS_COUNT }
            .map { before -> MerchantChange(before.key, currentByKey[before.key]?.displayName ?: before.displayName, currentByKey[before.key], before) }
            .filter { it.totalChange != 0L || it.countChange != 0 }
            .sortedWith(compareByDescending<MerchantChange> { kotlin.math.abs(it.totalChange) }.thenBy { it.key.value })
    }
}
