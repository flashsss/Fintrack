package com.fintrack.app.domain.validation

import com.fintrack.app.domain.model.CategoryType
import com.fintrack.app.domain.model.TransactionDraft
import com.fintrack.app.domain.model.TransactionType
import com.fintrack.app.domain.validation.TransactionValidationError.AmountNotPositive
import com.fintrack.app.domain.validation.TransactionValidationError.CategoryArchived
import com.fintrack.app.domain.validation.TransactionValidationError.CategoryNotAllowedForTransfer
import com.fintrack.app.domain.validation.TransactionValidationError.CategoryNotFound
import com.fintrack.app.domain.validation.TransactionValidationError.CategoryRequired
import com.fintrack.app.domain.validation.TransactionValidationError.CategoryTypeMismatch
import com.fintrack.app.domain.validation.TransactionValidationError.DestinationWalletArchived
import com.fintrack.app.domain.validation.TransactionValidationError.DestinationWalletNotAllowed
import com.fintrack.app.domain.validation.TransactionValidationError.DestinationWalletNotFound
import com.fintrack.app.domain.validation.TransactionValidationError.DestinationWalletRequired
import com.fintrack.app.domain.validation.TransactionValidationError.SameSourceAndDestination
import com.fintrack.app.domain.validation.TransactionValidationError.SourceWalletArchived
import com.fintrack.app.domain.validation.TransactionValidationError.SourceWalletNotFound
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class TransactionValidatorTest {

    private val bca = WalletRef("bca", isActive = true)
    private val mandiri = WalletRef("mandiri", isActive = true)
    private val salary = CategoryRef("salary", CategoryType.INCOME, isArchived = false)
    private val food = CategoryRef("food", CategoryType.EXPENSE, isArchived = false)
    private val date = Instant.parse("2026-01-15T03:00:00Z")

    private fun draft(
        type: TransactionType,
        amount: Long = 100_000,
        walletId: String = "bca",
        destinationWalletId: String? = null,
        categoryId: String? = null,
    ) = TransactionDraft(type, walletId, destinationWalletId, categoryId, amount, date)

    private fun validate(
        draft: TransactionDraft,
        source: WalletRef? = bca,
        destination: WalletRef? = null,
        category: CategoryRef? = null,
        existing: ExistingReferences? = null,
    ) = TransactionValidator.validate(draft, source, destination, category, existing)

    // Income

    @Test
    fun `valid income passes`() {
        assertTrue(validate(draft(TransactionType.INCOME, categoryId = "salary"), category = salary).isEmpty())
    }

    @Test
    fun `income with expense category is rejected`() {
        val errors = validate(draft(TransactionType.INCOME, categoryId = "food"), category = food)
        assertEquals(listOf(CategoryTypeMismatch), errors)
    }

    @Test
    fun `income without category is rejected`() {
        assertEquals(listOf(CategoryRequired), validate(draft(TransactionType.INCOME)))
    }

    @Test
    fun `income with unknown category is rejected`() {
        assertEquals(
            listOf(CategoryNotFound),
            validate(draft(TransactionType.INCOME, categoryId = "missing"), category = null),
        )
    }

    @Test
    fun `income with destination wallet is rejected`() {
        val errors = validate(
            draft(TransactionType.INCOME, categoryId = "salary", destinationWalletId = "mandiri"),
            destination = mandiri,
            category = salary,
        )
        assertEquals(listOf(DestinationWalletNotAllowed), errors)
    }

    // Expense

    @Test
    fun `valid expense passes`() {
        assertTrue(validate(draft(TransactionType.EXPENSE, categoryId = "food"), category = food).isEmpty())
    }

    @Test
    fun `expense with income category is rejected`() {
        val errors = validate(draft(TransactionType.EXPENSE, categoryId = "salary"), category = salary)
        assertEquals(listOf(CategoryTypeMismatch), errors)
    }

    @Test
    fun `expense with destination wallet is rejected`() {
        val errors = validate(
            draft(TransactionType.EXPENSE, categoryId = "food", destinationWalletId = "mandiri"),
            destination = mandiri,
            category = food,
        )
        assertEquals(listOf(DestinationWalletNotAllowed), errors)
    }

    // Transfer

    @Test
    fun `valid transfer passes`() {
        val errors = validate(draft(TransactionType.TRANSFER, destinationWalletId = "mandiri"), destination = mandiri)
        assertTrue(errors.isEmpty())
    }

    @Test
    fun `transfer without destination is rejected`() {
        assertEquals(listOf(DestinationWalletRequired), validate(draft(TransactionType.TRANSFER)))
    }

    @Test
    fun `transfer to the same wallet is rejected`() {
        val errors = validate(draft(TransactionType.TRANSFER, destinationWalletId = "bca"), destination = bca)
        assertEquals(listOf(SameSourceAndDestination), errors)
    }

    @Test
    fun `transfer with category is rejected`() {
        val errors = validate(
            draft(TransactionType.TRANSFER, destinationWalletId = "mandiri", categoryId = "food"),
            destination = mandiri,
            category = food,
        )
        assertEquals(listOf(CategoryNotAllowedForTransfer), errors)
    }

    @Test
    fun `transfer to unknown wallet is rejected`() {
        val errors = validate(draft(TransactionType.TRANSFER, destinationWalletId = "missing"), destination = null)
        assertEquals(listOf(DestinationWalletNotFound), errors)
    }

    // Amount

    @Test
    fun `zero amount is rejected for every type`() {
        assertEquals(
            listOf(AmountNotPositive),
            validate(draft(TransactionType.INCOME, amount = 0, categoryId = "salary"), category = salary),
        )
        assertEquals(
            listOf(AmountNotPositive),
            validate(draft(TransactionType.EXPENSE, amount = 0, categoryId = "food"), category = food),
        )
        assertEquals(
            listOf(AmountNotPositive),
            validate(draft(TransactionType.TRANSFER, amount = 0, destinationWalletId = "mandiri"), destination = mandiri),
        )
    }

    @Test
    fun `negative amount is rejected for every type`() {
        assertEquals(
            listOf(AmountNotPositive),
            validate(draft(TransactionType.INCOME, amount = -1, categoryId = "salary"), category = salary),
        )
        assertEquals(
            listOf(AmountNotPositive),
            validate(draft(TransactionType.EXPENSE, amount = -50_000, categoryId = "food"), category = food),
        )
        assertEquals(
            listOf(AmountNotPositive),
            validate(draft(TransactionType.TRANSFER, amount = -1, destinationWalletId = "mandiri"), destination = mandiri),
        )
    }

    // Wallet references

    @Test
    fun `unknown source wallet is rejected`() {
        val errors = validate(draft(TransactionType.EXPENSE, categoryId = "food"), source = null, category = food)
        assertEquals(listOf(SourceWalletNotFound), errors)
    }

    @Test
    fun `archived source wallet is rejected for new transaction`() {
        val errors = validate(
            draft(TransactionType.EXPENSE, categoryId = "food"),
            source = bca.copy(isActive = false),
            category = food,
        )
        assertEquals(listOf(SourceWalletArchived), errors)
    }

    @Test
    fun `archived destination wallet is rejected for new transfer`() {
        val errors = validate(
            draft(TransactionType.TRANSFER, destinationWalletId = "mandiri"),
            destination = mandiri.copy(isActive = false),
        )
        assertEquals(listOf(DestinationWalletArchived), errors)
    }

    // Archived references on edits

    @Test
    fun `archived category is rejected for new transaction`() {
        val errors = validate(
            draft(TransactionType.EXPENSE, categoryId = "food"),
            category = food.copy(isArchived = true),
        )
        assertEquals(listOf(CategoryArchived), errors)
    }

    @Test
    fun `edit may keep the archived category and wallet the transaction already uses`() {
        val errors = validate(
            draft(TransactionType.EXPENSE, amount = 75_000, categoryId = "food"),
            source = bca.copy(isActive = false),
            category = food.copy(isArchived = true),
            existing = ExistingReferences(walletId = "bca", destinationWalletId = null, categoryId = "food"),
        )
        assertTrue(errors.isEmpty())
    }

    @Test
    fun `edit cannot switch to a different archived category`() {
        val errors = validate(
            draft(TransactionType.EXPENSE, categoryId = "food"),
            category = food.copy(isArchived = true),
            existing = ExistingReferences(walletId = "bca", destinationWalletId = null, categoryId = "other"),
        )
        assertEquals(listOf(CategoryArchived), errors)
    }

    @Test
    fun `multiple violations are all reported`() {
        val errors = validate(draft(TransactionType.TRANSFER, amount = 0, categoryId = "food"), category = food)
        assertEquals(setOf(AmountNotPositive, CategoryNotAllowedForTransfer, DestinationWalletRequired), errors.toSet())
    }
}
