package com.fintrack.app.sync

import com.fintrack.app.data.local.entity.BudgetEntity
import com.fintrack.app.data.local.entity.CategoryEntity
import com.fintrack.app.data.local.entity.GoalEntity
import com.fintrack.app.data.local.entity.TransactionEntity
import com.fintrack.app.data.local.entity.WalletEntity
import com.fintrack.app.domain.model.CategoryType
import com.fintrack.app.domain.model.SyncStatus
import com.fintrack.app.domain.model.TransactionSource
import com.fintrack.app.domain.model.TransactionType
import com.fintrack.app.domain.model.WalletType
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.time.Instant

class SyncMappingTest {

    // encodeDefaults = true like supabase-kt; server_updated_at must still not be sent.
    private val json = Json { encodeDefaults = true }
    private val lenient = Json { ignoreUnknownKeys = true }

    private val wallet = WalletEntity("w-1", "BCA", WalletType.CREDIT_CARD, -1_500_000, "IDR", false, 11, 12, 13, SyncStatus.PENDING)
    private val category = CategoryEntity("c-1", "Listrik", CategoryType.EXPENSE, "c-0", "bolt", "#FF0000", true, true, 21, 22, null, SyncStatus.PENDING)
    private val transaction = TransactionEntity(
        "t-1", "w-1", "w-2", null, TransactionType.TRANSFER, 999_999_999_999L, 31, "Bayar", "Toko",
        TransactionSource.CSV_IMPORT, "b-1", "fp#1", 32, 33, 34, SyncStatus.PENDING,
    )
    private val budget = BudgetEntity("b-1", null, 5_000_000, 41, 42, null, SyncStatus.PENDING)
    private val goal = GoalEntity("g-1", "Dana darurat", 30_000_000, 1_000_000, 1_790_000_000_000, 51, 52, 53, SyncStatus.PENDING)

    @Test
    fun `every table round-trips through JSON and arrives as synced`() {
        assertEquals(wallet.copy(syncStatus = SyncStatus.SYNCED), SyncMapping.toEntity(json.decodeFromString<WalletDto>(json.encodeToString(SyncMapping.toDto(wallet)))))
        assertEquals(category.copy(syncStatus = SyncStatus.SYNCED), SyncMapping.toEntity(json.decodeFromString<CategoryDto>(json.encodeToString(SyncMapping.toDto(category)))))
        assertEquals(transaction.copy(syncStatus = SyncStatus.SYNCED), SyncMapping.toEntity(json.decodeFromString<TransactionDto>(json.encodeToString(SyncMapping.toDto(transaction)))))
        assertEquals(budget.copy(syncStatus = SyncStatus.SYNCED), SyncMapping.toEntity(json.decodeFromString<BudgetDto>(json.encodeToString(SyncMapping.toDto(budget)))))
        assertEquals(goal.copy(syncStatus = SyncStatus.SYNCED), SyncMapping.toEntity(json.decodeFromString<GoalDto>(json.encodeToString(SyncMapping.toDto(goal)))))
    }

    @Test
    fun `sent JSON keys are exactly the server columns except user_id and server_updated_at`() {
        val columns = serverColumns()
        fun keys(encoded: String) = json.parseToJsonElement(encoded).jsonObject.keys
        val sent = mapOf(
            "wallets" to keys(json.encodeToString(SyncMapping.toDto(wallet))),
            "categories" to keys(json.encodeToString(SyncMapping.toDto(category))),
            "transactions" to keys(json.encodeToString(SyncMapping.toDto(transaction))),
            "budgets" to keys(json.encodeToString(SyncMapping.toDto(budget))),
            "financial_goals" to keys(json.encodeToString(SyncMapping.toDto(goal))),
        )
        assertEquals(SyncTable.entries.map { it.remoteName }.toSet(), columns.keys)
        for ((table, keys) in sent) assertEquals(table, columns.getValue(table) - setOf("user_id", "server_updated_at"), keys)
    }

    @Test
    fun `rows read from the server keep server_updated_at with microseconds`() {
        val text = """{"id":"w-1","name":"BCA","type":"BANK","initial_balance":0,"currency":"IDR","is_active":true,
            "created_at":1,"updated_at":2,"deleted_at":null,"server_updated_at":"2026-11-27T03:51:12.123456+00:00","user_id":"u"}"""
        val dto = lenient.decodeFromString<WalletDto>(text)
        assertEquals(Instant.parse("2026-11-27T03:51:12.123456Z"), SyncMapping.parseServerTime(dto.serverUpdatedAt!!))
    }

    /** Column names per table from the migration file (the server's source of truth). */
    private fun serverColumns(): Map<String, Set<String>> {
        val sql = listOf("../supabase/migrations/0001_initial.sql", "supabase/migrations/0001_initial.sql")
            .map(::File).first { it.exists() }.readText()
        val tables = Regex("""create table public\.(\w+) \((.*?)\n\);""", RegexOption.DOT_MATCHES_ALL).findAll(sql)
        return tables.associate { match ->
            val columns = match.groupValues[2].lines().map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("--") && !it.startsWith("primary key") && !it.startsWith("foreign key") && !it.startsWith("check") && !it.startsWith("(") && !it.startsWith("or ") && !it.startsWith(")") }
                .map { it.substringBefore(' ') }
            match.groupValues[1] to columns.toSet()
        }
    }

}
