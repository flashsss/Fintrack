package com.fintrack.app.sync

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.time.Instant

/** Why the last run did not complete. Stored by name. */
enum class SyncProblem { OFFLINE, SIGNED_OUT, ACCOUNT_MISMATCH, REJECTED }

/** Device sync state shown on the Sinkronisasi screen. */
data class SyncStatusInfo(
    val lastSuccessAt: Instant? = null,
    val problem: SyncProblem? = null,
    /** Rows refused by the server or by local validation in the last run. */
    val rejectedRows: Int = 0,
)

/**
 * Per-device sync state in DataStore (design §3): pull cursors, the account this device's data
 * belongs to, and the result of the last run. Never contains financial data or tokens.
 */
class SyncStateStore(private val store: DataStore<Preferences>) : SyncCursorStore {

    override suspend fun cursor(table: SyncTable): Instant? =
        store.data.first()[cursorKey(table)]?.let(Instant::parse)

    override suspend fun setCursor(table: SyncTable, value: Instant) {
        store.edit { it[cursorKey(table)] = value.toString() }
    }

    suspend fun boundUserId(): String? = store.data.first()[BOUND_USER]

    suspend fun bindUser(userId: String) {
        store.edit { it[BOUND_USER] = userId }
    }

    suspend fun recordSuccess(at: Instant, rejectedRows: Int) {
        store.edit {
            it[LAST_SUCCESS] = at.toEpochMilli()
            it.remove(PROBLEM)
            it[REJECTED] = rejectedRows.toLong()
        }
    }

    suspend fun recordProblem(problem: SyncProblem) {
        store.edit { it[PROBLEM] = problem.name }
    }

    val status: Flow<SyncStatusInfo> = store.data.map { prefs ->
        SyncStatusInfo(
            lastSuccessAt = prefs[LAST_SUCCESS]?.let(Instant::ofEpochMilli),
            problem = prefs[PROBLEM]?.let { name -> SyncProblem.entries.firstOrNull { it.name == name } },
            rejectedRows = (prefs[REJECTED] ?: 0L).toInt(),
        )
    }

    private fun cursorKey(table: SyncTable) = stringPreferencesKey("cursor_${table.remoteName}")

    private companion object {
        val BOUND_USER = stringPreferencesKey("bound_user_id")
        val LAST_SUCCESS = longPreferencesKey("last_success_millis")
        val PROBLEM = stringPreferencesKey("last_problem")
        val REJECTED = longPreferencesKey("last_rejected_rows")
    }
}
