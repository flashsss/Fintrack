package com.fintrack.app.analytics

import com.fintrack.app.analytics.stats.DistributionSummary
import com.fintrack.app.analytics.stats.Percentile
import com.fintrack.app.analytics.stats.StreamingDistribution
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.math.MathContext
import kotlin.random.Random

class StreamingDistributionTest {

    private fun summarize(values: List<Long>): DistributionSummary {
        val sorted = values.sorted()
        return StreamingDistribution(sorted.size).apply { sorted.forEach(::accept) }.result()
    }

    @Test
    fun `matches NumPy reference values for ten amounts`() {
        // numpy.percentile(..., q) default (linear) and numpy.std (population).
        val s = summarize(listOf(15_000, 20_000, 20_000, 35_000, 50_000, 75_000, 120_000, 500_000, 1_200_000, 3_000_000))
        assertEquals(23_750.0, s.percentiles[Percentile.P25]!!, 1e-6)
        assertEquals(62_500.0, s.percentiles[Percentile.P50]!!, 1e-6)
        assertEquals(405_000.0, s.percentiles[Percentile.P75]!!, 1e-6)
        assertEquals(1_380_000.0, s.percentiles[Percentile.P90]!!, 1e-6)
        assertNull("P95 needs 20 values", s.percentiles[Percentile.P95])
        assertNull("P99 needs 100 values", s.percentiles[Percentile.P99])
        assertEquals(381_250.0, s.iqr!!, 1e-6)
        assertEquals(503_500.0, s.mean!!, 1e-9)
        assertEquals(904_414.3132436593, s.populationStdDev!!, 1e-6)
        assertEquals(5_035_000L, s.sum)
        assertEquals(15_000L, s.min)
        assertEquals(3_000_000L, s.max)
    }

    @Test
    fun `matches NumPy reference values for 101 amounts with an outlier`() {
        val s = summarize((1..100).map { it * 1_000L } + 7_777_777L)
        assertEquals(26_000.0, s.percentiles[Percentile.P25]!!, 1e-6)
        assertEquals(51_000.0, s.percentiles[Percentile.P50]!!, 1e-6)
        assertEquals(91_000.0, s.percentiles[Percentile.P90]!!, 1e-6)
        assertEquals(96_000.0, s.percentiles[Percentile.P95]!!, 1e-6)
        assertEquals(100_000.0, s.percentiles[Percentile.P99]!!, 1e-6)
        assertEquals(127_007.69306930693, s.mean!!, 1e-6)
        assertEquals(765_615.9023157287, s.populationStdDev!!, 1e-6)
    }

    @Test
    fun `small samples respect the minimum counts`() {
        val one = summarize(listOf(42_000))
        assertEquals(42_000.0, one.percentiles[Percentile.P50]!!, 0.0)
        assertNull(one.populationStdDev)
        assertNull(one.iqr)
        val two = summarize(listOf(10_000, 30_000))
        assertEquals(20_000.0, two.percentiles[Percentile.P50]!!, 0.0) // average of the two middle values
        assertEquals(10_000.0, two.populationStdDev!!, 1e-9)
        assertNull(two.percentiles[Percentile.P25])
        assertEquals(DistributionSummary.EMPTY, summarize(emptyList()))
    }

    @Test
    fun `identical values have zero spread`() {
        val s = summarize(List(50) { 25_000L })
        assertEquals(0.0, s.populationStdDev!!, 0.0)
        assertEquals(0.0, s.iqr!!, 0.0)
        assertTrue(s.percentiles.values.filterNotNull().all { it == 25_000.0 })
    }

    @Test
    fun `Welford matches an exact two-pass computation and percentiles are monotonic`() {
        val random = Random(20261125)
        val values = List(100_000) { 1_000L + random.nextLong(0, 50_000_000) }
        val s = summarize(values)
        val n = BigDecimal(values.size)
        val mean = values.fold(BigDecimal.ZERO) { a, v -> a + BigDecimal(v) }.divide(n, MathContext.DECIMAL128)
        val variance = values.fold(BigDecimal.ZERO) { a, v -> val d = BigDecimal(v) - mean; a + d * d }.divide(n, MathContext.DECIMAL128)
        val exactStdDev = variance.sqrt(MathContext.DECIMAL128).toDouble()
        assertEquals(1.0, s.populationStdDev!! / exactStdDev, 1e-9)
        assertEquals(mean.toDouble(), s.mean!!, 1e-6)
        val ordered = listOf(s.min!!.toDouble()) + Percentile.entries.map { s.percentiles[it]!! } + s.max!!.toDouble()
        assertEquals(ordered.sorted(), ordered)
    }

    @Test
    fun `a stream that does not match the snapshot count is an error`() {
        val short = StreamingDistribution(3).apply { accept(1); accept(2) }
        assertThrows(IllegalStateException::class.java) { short.result() }
        val long = StreamingDistribution(1).apply { accept(1) }
        assertThrows(IllegalStateException::class.java) { long.accept(2) }
        assertThrows(IllegalArgumentException::class.java) { StreamingDistribution(2).apply { accept(5); accept(4) } }
    }
}
