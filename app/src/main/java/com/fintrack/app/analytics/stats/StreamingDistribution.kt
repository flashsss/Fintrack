package com.fintrack.app.analytics.stats

import kotlin.math.floor
import kotlin.math.sqrt

/** Percentiles reported, with the minimum sample size below which each is not shown (docs §5.5). */
enum class Percentile(val p: Double, val minCount: Int) {
    P25(0.25, 4), P50(0.50, 1), P75(0.75, 4), P90(0.90, 10), P95(0.95, 20), P99(0.99, 100),
}

data class DistributionSummary(
    val count: Int,
    val sum: Long,
    val min: Long?,
    val max: Long?,
    val mean: Double?,
    /** Null when the sample is smaller than the percentile's minimum. */
    val percentiles: Map<Percentile, Double?>,
    /** P75 − P25; null below 4 values. */
    val iqr: Double?,
    /** Population standard deviation (÷ n): the data is every transaction of the period, not a sample. Null below 2 values. */
    val populationStdDev: Double?,
) {
    companion object {
        val EMPTY = DistributionSummary(0, 0, null, null, null, Percentile.entries.associateWith { null }, null, null)
    }
}

/**
 * Exact statistics over values delivered in ascending order, using O(number of percentiles) memory.
 *
 * Percentiles use linear interpolation between closest ranks (Hyndman–Fan type 7, as Excel
 * PERCENTILE.INC and NumPy's default): h = (n − 1)·p, Q = x[⌊h⌋] + (h − ⌊h⌋)(x[⌊h⌋+1] − x[⌊h⌋])
 * with 0-based indices. The mean and variance use Welford's algorithm for numerical stability.
 */
class StreamingDistribution(private val expectedCount: Int) {
    private val wantedIndices: Set<Int> = Percentile.entries.flatMap { percentile ->
        val h = (expectedCount - 1) * percentile.p
        listOf(floor(h).toInt(), minOf(floor(h).toInt() + 1, expectedCount - 1))
    }.filter { it >= 0 }.toSet()
    private val picked = HashMap<Int, Long>()
    private var index = 0
    private var previous = Long.MIN_VALUE
    private var sum = 0L
    private var min: Long? = null
    private var max: Long? = null
    private var mean = 0.0
    private var m2 = 0.0

    fun accept(value: Long) {
        check(index < expectedCount) { "More values than the expected count $expectedCount" }
        require(value >= previous) { "Values must arrive in ascending order" }
        previous = value
        if (index in wantedIndices) picked[index] = value
        if (index == 0) min = value
        max = value
        sum = Math.addExact(sum, value)
        val delta = value - mean
        mean += delta / (index + 1)
        m2 += delta * (value - mean)
        index++
    }

    fun result(): DistributionSummary {
        check(index == expectedCount) { "Received $index values but the snapshot count was $expectedCount" }
        if (expectedCount == 0) return DistributionSummary.EMPTY
        val percentiles = Percentile.entries.associateWith { percentile ->
            if (expectedCount < percentile.minCount) {
                null
            } else {
                val h = (expectedCount - 1) * percentile.p
                val lower = floor(h).toInt()
                val upper = minOf(lower + 1, expectedCount - 1)
                val low = picked.getValue(lower).toDouble()
                low + (h - lower) * (picked.getValue(upper) - low)
            }
        }
        val p25 = percentiles[Percentile.P25]
        val p75 = percentiles[Percentile.P75]
        return DistributionSummary(
            count = expectedCount,
            sum = sum,
            min = min,
            max = max,
            mean = sum.toDouble() / expectedCount,
            percentiles = percentiles,
            iqr = if (p25 != null && p75 != null) p75 - p25 else null,
            populationStdDev = if (expectedCount >= 2) sqrt(m2 / expectedCount) else null,
        )
    }
}
