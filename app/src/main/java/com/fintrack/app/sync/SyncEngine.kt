package com.fintrack.app.sync

import java.time.Duration
import javax.inject.Inject

/**
 * One synchronization run: push local changes, then pull cloud changes (design §6).
 * Tables are processed in foreign-key order. Runs must not overlap (WorkManager unique work).
 */
class SyncEngine @Inject constructor(
    private val local: LocalSyncStore,
    private val remote: RemoteSyncSource,
    private val cursors: SyncCursorStore,
) {
    /**
     * Throws [RemoteSyncException.Unavailable] or [RemoteSyncException.Unauthorized] when the run
     * cannot continue; work already committed (pushed rows, written pages, cursors) is kept.
     */
    suspend fun run(): SyncReport {
        var pushed = 0
        var pushRejected = 0
        for (table in SyncTable.entries) {
            val result = push(table)
            pushed += result.first
            pushRejected += result.second
        }
        var pulled = 0
        var keptLocal = 0
        var pullRejected = 0
        for (table in SyncTable.entries) {
            val counts = pull(table)
            pulled += counts[MergeOutcome.WRITTEN] ?: 0
            keptLocal += counts[MergeOutcome.KEPT_LOCAL] ?: 0
            pullRejected += counts[MergeOutcome.REJECTED] ?: 0
        }
        return SyncReport(pushed, pushRejected, pulled, keptLocal, pullRejected)
    }

    /** Uploads pending rows in batches. Returns (pushed, rejected). */
    private suspend fun push(table: SyncTable): Pair<Int, Int> {
        var pushed = 0
        val rejected = mutableSetOf<String>()
        while (true) {
            // Rows the server refused stay pending; skip them so the loop ends.
            val batch = local.pending(table, BATCH_SIZE + rejected.size).filter { it.id !in rejected }.take(BATCH_SIZE)
            if (batch.isEmpty()) break
            val accepted = try {
                remote.upsert(table, batch)
                batch
            } catch (error: RemoteSyncException.Rejected) {
                // Isolate the refused rows so one bad row does not block the rest.
                batch.filter { record ->
                    try {
                        remote.upsert(table, listOf(record))
                        true
                    } catch (single: RemoteSyncException.Rejected) {
                        rejected += record.id
                        false
                    }
                }
            }
            accepted.forEach { local.markSynced(it) }
            pushed += accepted.size
            if (batch.size < BATCH_SIZE) break
        }
        return pushed to rejected.size
    }

    /**
     * Downloads rows changed since the cursor (minus an overlap for late commits) and merges them.
     * Each page is merged in one Room transaction and the cursor advances only after it commits.
     * Categories are collected fully and merged parents first (subcategory foreign key).
     */
    private suspend fun pull(table: SyncTable): Map<MergeOutcome, Int> {
        val since = cursors.cursor(table)?.minus(CURSOR_OVERLAP)
        val counts = mutableMapOf<MergeOutcome, Int>()
        if (table == SyncTable.CATEGORIES) {
            val all = buildList {
                var offset = 0
                while (true) {
                    val page = remote.pull(table, since, offset, BATCH_SIZE)
                    addAll(page)
                    offset += page.size
                    if (page.size < BATCH_SIZE) break
                }
            }
            val ordered = all.sortedBy { (it.record as SyncRecord.Category).entity.parentId != null }
            mergePage(table, ordered, counts)
            return counts
        }
        var offset = 0
        while (true) {
            val page = remote.pull(table, since, offset, BATCH_SIZE)
            mergePage(table, page, counts)
            offset += page.size
            if (page.size < BATCH_SIZE) break
        }
        return counts
    }

    private suspend fun mergePage(table: SyncTable, page: List<PulledRecord>, counts: MutableMap<MergeOutcome, Int>) {
        if (page.isEmpty()) return
        val outcomes = local.inTransaction { page.map { local.merge(it.record) } }
        outcomes.forEach { counts.merge(it, 1, Int::plus) }
        val newest = page.maxOf { it.serverUpdatedAt }
        val current = cursors.cursor(table)
        if (current == null || newest > current) cursors.setCursor(table, newest)
    }

    companion object {
        const val BATCH_SIZE = 500

        /** Re-reads recent changes in case a server transaction committed after a later one. */
        val CURSOR_OVERLAP: Duration = Duration.ofMinutes(5)
    }
}
