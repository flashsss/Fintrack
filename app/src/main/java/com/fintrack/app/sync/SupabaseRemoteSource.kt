package com.fintrack.app.sync

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.exceptions.RestException
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.postgrest.result.PostgrestResult
import java.io.IOException
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/** [RemoteSyncSource] over Supabase PostgREST (tables in supabase/migrations/0001_initial.sql). */
@Singleton
class SupabaseRemoteSource @Inject constructor(private val access: SupabaseAccess) : RemoteSyncSource {

    override suspend fun upsert(table: SyncTable, records: List<SyncRecord>) {
        if (records.isEmpty()) return
        call { client ->
            val query = client.from(table.remoteName)
            val conflict: io.github.jan.supabase.postgrest.query.request.UpsertRequestBuilder.() -> Unit = { onConflict = "user_id,id" }
            when (table) {
                SyncTable.WALLETS -> query.upsert(records.map { SyncMapping.toDto((it as SyncRecord.Wallet).entity) }, conflict)
                SyncTable.CATEGORIES -> query.upsert(records.map { SyncMapping.toDto((it as SyncRecord.Category).entity) }, conflict)
                SyncTable.TRANSACTIONS -> query.upsert(records.map { SyncMapping.toDto((it as SyncRecord.Transaction).entity) }, conflict)
                SyncTable.BUDGETS -> query.upsert(records.map { SyncMapping.toDto((it as SyncRecord.Budget).entity) }, conflict)
                SyncTable.GOALS -> query.upsert(records.map { SyncMapping.toDto((it as SyncRecord.Goal).entity) }, conflict)
            }
        }
    }

    override suspend fun pull(table: SyncTable, since: Instant?, offset: Int, limit: Int): List<PulledRecord> = call { client ->
        val result: PostgrestResult = client.from(table.remoteName).select {
            if (since != null) filter { gte("server_updated_at", since.toString()) }
            order("server_updated_at", Order.ASCENDING)
            order("id", Order.ASCENDING)
            range(offset.toLong(), (offset + limit - 1).toLong())
        }
        when (table) {
            SyncTable.WALLETS -> result.decodeList<WalletDto>().map { PulledRecord(SyncRecord.Wallet(SyncMapping.toEntity(it)), stamp(it.serverUpdatedAt)) }
            SyncTable.CATEGORIES -> result.decodeList<CategoryDto>().map { PulledRecord(SyncRecord.Category(SyncMapping.toEntity(it)), stamp(it.serverUpdatedAt)) }
            SyncTable.TRANSACTIONS -> result.decodeList<TransactionDto>().map { PulledRecord(SyncRecord.Transaction(SyncMapping.toEntity(it)), stamp(it.serverUpdatedAt)) }
            SyncTable.BUDGETS -> result.decodeList<BudgetDto>().map { PulledRecord(SyncRecord.Budget(SyncMapping.toEntity(it)), stamp(it.serverUpdatedAt)) }
            SyncTable.GOALS -> result.decodeList<GoalDto>().map { PulledRecord(SyncRecord.Goal(SyncMapping.toEntity(it)), stamp(it.serverUpdatedAt)) }
        }
    }

    private fun stamp(text: String?): Instant =
        SyncMapping.parseServerTime(text ?: throw RemoteSyncException.Rejected("server_updated_at missing"))

    /** Maps transport and HTTP failures to [RemoteSyncException] (design §6.3). */
    private suspend fun <T> call(block: suspend (SupabaseClient) -> T): T {
        val client = access.client ?: throw RemoteSyncException.Unauthorized()
        if (client.auth.also { it.awaitInitialization() }.currentSessionOrNull() == null) throw RemoteSyncException.Unauthorized()
        return try {
            block(client)
        } catch (error: RestException) {
            throw when (error.statusCode) {
                401 -> RemoteSyncException.Unauthorized(error)
                in 500..599 -> RemoteSyncException.Unavailable(error)
                else -> RemoteSyncException.Rejected("HTTP ${error.statusCode}", error)
            }
        } catch (error: IOException) {
            throw RemoteSyncException.Unavailable(error)
        } catch (error: kotlinx.serialization.SerializationException) {
            // A response that does not match the expected table shape (schema not installed or changed).
            throw RemoteSyncException.Rejected("Unexpected response", error)
        }
    }
}
