package com.fintrack.app.data.local

import androidx.room.Transactor
import androidx.room.useReaderConnection
import com.fintrack.app.analytics.stats.DistributionSummary
import com.fintrack.app.analytics.stats.StreamingDistribution
import com.fintrack.app.data.local.database.FinTrackDatabase
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Streams expense amounts in ascending order to compute exact statistics without loading the list
 * (docs/PHASE_4_TECHNICAL_DESIGN.md addendum B2).
 *
 * Snapshot consistency has two layers:
 * 1. one statement returns the amounts and, from an uncorrelated scalar subquery, their count; a
 *    single SQLite statement always reads one snapshot;
 * 2. the statement runs inside a DEFERRED read transaction on Room's reader connection.
 * A stream whose length differs from the count is an error, never silently accepted.
 */
@Singleton
class ExpenseDistributionReader @Inject constructor(private val database: FinTrackDatabase) {

    suspend fun read(startMillis: Long, endMillis: Long): DistributionSummary =
        database.useReaderConnection { connection ->
            connection.withTransaction(Transactor.SQLiteTransactionType.DEFERRED) {
                usePrepared(SQL) { statement ->
                    statement.bindLong(1, startMillis)
                    statement.bindLong(2, endMillis)
                    var distribution: StreamingDistribution? = null
                    while (statement.step()) {
                        val accumulator = distribution ?: StreamingDistribution(statement.getLong(1).toInt()).also { distribution = it }
                        accumulator.accept(statement.getLong(0))
                    }
                    distribution?.result() ?: DistributionSummary.EMPTY
                }
            }
        }

    companion object {
        /** ?1 = start (inclusive), ?2 = end (exclusive), UTC millis of Jakarta midnights. */
        const val SQL = """
            SELECT amount,
                (SELECT COUNT(*) FROM transactions
                 WHERE +deletedAt IS NULL AND type = 'EXPENSE'
                   AND transactionDate >= ?1 AND transactionDate < ?2) AS n
            FROM transactions
            WHERE +deletedAt IS NULL AND type = 'EXPENSE'
              AND transactionDate >= ?1 AND transactionDate < ?2
            ORDER BY amount
            """
    }
}
