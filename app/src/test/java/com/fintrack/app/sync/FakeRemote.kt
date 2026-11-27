package com.fintrack.app.sync

import java.time.Instant

/**
 * In-memory stand-in for the Supabase tables, with the server behavior from
 * supabase/migrations/0001_initial.sql: upsert by id, updates with an older updatedAt ignored,
 * server_updated_at stamped from a strictly increasing clock, pull ordered by (stamp, id).
 */
class FakeRemote : RemoteSyncSource {
    private data class Row(val record: SyncRecord, val stamp: Instant)

    private val rows = mutableMapOf<Pair<SyncTable, String>, Row>()
    private var clock = Instant.parse("2026-01-01T00:00:00Z")

    /** Rows for which upsert throws Rejected (like a CHECK violation). */
    var rejectIf: (SyncRecord) -> Boolean = { false }
    /** Throws Unavailable when [pullCalls] reaches this value (1-based, counted over all devices). */
    var failPullCall: Int? = null
    /** Called during each upsert, before rows are stored (e.g. to edit locally mid-upload). */
    var onUpsert: suspend (List<SyncRecord>) -> Unit = {}

    var upsertCalls = 0
        private set
    var pullCalls = 0
        private set

    override suspend fun upsert(table: SyncTable, records: List<SyncRecord>) {
        upsertCalls++
        onUpsert(records)
        if (records.any(rejectIf)) throw RemoteSyncException.Rejected("check violation")
        records.forEach { record ->
            val key = table to record.id
            val existing = rows[key]
            if (existing != null && record.updatedAt < existing.record.updatedAt) return@forEach
            clock = clock.plusMillis(1)
            rows[key] = Row(record, clock)
        }
    }

    override suspend fun pull(table: SyncTable, since: Instant?, offset: Int, limit: Int): List<PulledRecord> {
        pullCalls++
        if (pullCalls == failPullCall) throw RemoteSyncException.Unavailable()
        return rows.filter { it.key.first == table && (since == null || it.value.stamp >= since) }
            .values.sortedWith(compareBy<Row> { it.stamp }.thenBy { it.record.id })
            .drop(offset).take(limit)
            .map { PulledRecord(it.record, it.stamp) }
    }

    fun record(table: SyncTable, id: String): SyncRecord? = rows[table to id]?.record
    fun count(table: SyncTable) = rows.keys.count { it.first == table }
}

class MemoryCursors : SyncCursorStore {
    val values = mutableMapOf<SyncTable, Instant>()
    override suspend fun cursor(table: SyncTable) = values[table]
    override suspend fun setCursor(table: SyncTable, value: Instant) {
        values[table] = value
    }
}
