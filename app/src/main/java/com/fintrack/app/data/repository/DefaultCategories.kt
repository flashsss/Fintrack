package com.fintrack.app.data.repository

import com.fintrack.app.domain.model.CategoryType
import java.util.UUID

/**
 * Default categories created on first launch.
 *
 * Each default has a stable [key] from which a deterministic UUID is derived, so seeding can run
 * on every launch without creating duplicates, and a default that the user renamed or archived is
 * never recreated or overwritten.
 */
internal object DefaultCategories {

    data class Definition(val key: String, val name: String, val type: CategoryType) {
        val id: String = UUID.nameUUIDFromBytes("fintrack.default-category.$key".toByteArray()).toString()
    }

    val all: List<Definition> = listOf(
        Definition("income.salary", "Gaji", CategoryType.INCOME),
        Definition("income.business", "Bisnis", CategoryType.INCOME),
        Definition("income.investment", "Investasi", CategoryType.INCOME),
        Definition("income.bonus", "Bonus", CategoryType.INCOME),
        Definition("income.gift", "Hadiah", CategoryType.INCOME),
        Definition("income.other", "Pemasukan lainnya", CategoryType.INCOME),
        Definition("expense.food", "Makanan dan minuman", CategoryType.EXPENSE),
        Definition("expense.transport", "Transportasi", CategoryType.EXPENSE),
        Definition("expense.shopping", "Belanja", CategoryType.EXPENSE),
        Definition("expense.housing", "Tempat tinggal", CategoryType.EXPENSE),
        Definition("expense.bills", "Tagihan", CategoryType.EXPENSE),
        Definition("expense.health", "Kesehatan", CategoryType.EXPENSE),
        Definition("expense.entertainment", "Hiburan", CategoryType.EXPENSE),
        Definition("expense.education", "Pendidikan", CategoryType.EXPENSE),
        Definition("expense.travel", "Perjalanan", CategoryType.EXPENSE),
        Definition("expense.personal", "Kebutuhan pribadi", CategoryType.EXPENSE),
        Definition("expense.other", "Pengeluaran lainnya", CategoryType.EXPENSE),
    )
}
