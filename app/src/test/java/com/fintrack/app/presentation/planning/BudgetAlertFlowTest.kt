package com.fintrack.app.presentation.planning

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.fintrack.app.analytics.metrics.BudgetAlert
import com.fintrack.app.analytics.metrics.BudgetAlertKind
import com.fintrack.app.core.common.BudgetNotifications
import com.fintrack.app.data.TestDatabase
import com.fintrack.app.data.requireId
import com.fintrack.app.domain.model.CategoryType
import com.fintrack.app.domain.model.TransactionDraft
import com.fintrack.app.domain.model.TransactionType
import com.fintrack.app.domain.model.WalletType
import com.fintrack.app.presentation.MainDispatcherRule
import com.fintrack.app.presentation.awaitValue
import com.fintrack.app.presentation.navigation.Routes
import com.fintrack.app.presentation.transactions.TransactionFormViewModel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import java.time.Instant

/** The test clock is 1 Jan 2026 07:00 WIB, so "this month" is January 2026. */
@RunWith(AndroidJUnit4::class)
class BudgetAlertFlowTest {

    @get:Rule
    val mainDispatcher = MainDispatcherRule()

    private val t = TestDatabase()
    private lateinit var wallet: String
    private lateinit var other: String
    private lateinit var food: String
    private lateinit var cafe: String

    @Before
    fun setUp() = runBlocking {
        wallet = t.wallets.createWallet("BCA", WalletType.BANK, 0).requireId()
        other = t.wallets.createWallet("Mandiri", WalletType.BANK, 0).requireId()
        food = t.categories.createCategory("Makanan", CategoryType.EXPENSE, null, null, null).requireId()
        cafe = t.categories.createCategory("Kafe", CategoryType.EXPENSE, food, null, null).requireId()
        t.planning.createBudget(food, 1_000_000).requireId()
        Unit
    }

    @After
    fun tearDown() = t.close()

    private suspend fun addViaForm(category: String, amount: String, type: TransactionType = TransactionType.EXPENSE) {
        val form = TransactionFormViewModel(
            SavedStateHandle(mapOf(Routes.ARG_TYPE to type.name)), t.transactions, t.wallets, t.categories, t.budgetAlerts, t.clock,
        )
        form.onAmountChange(amount)
        form.onWalletChange(wallet)
        if (type == TransactionType.TRANSFER) form.onDestinationChange(other) else form.onCategoryChange(category)
        form.save()
        form.state.awaitValue { it.isSaved }
    }

    @Test
    fun `alerts fire once per crossing and count child categories towards the parent budget`() = runBlocking {
        addViaForm(food, "700.000")
        assertTrue(t.deliveredAlerts.isEmpty())
        addViaForm(cafe, "100.000") // 800.000 of 1.000.000 via a child category
        assertEquals(listOf(BudgetAlertKind.NEAR_LIMIT), t.deliveredAlerts.map { it.kind })
        addViaForm(food, "50.000") // 85%: no repeat
        assertEquals(1, t.deliveredAlerts.size)
        addViaForm(food, "200.000") // 1.050.000
        assertEquals(listOf(BudgetAlertKind.NEAR_LIMIT, BudgetAlertKind.OVER_LIMIT), t.deliveredAlerts.map { it.kind })
        assertEquals(1_050_000L, t.deliveredAlerts.last().spent)
        addViaForm(food, "10.000")
        assertEquals("no repeat once over", 2, t.deliveredAlerts.size)
    }

    @Test
    fun `transfers and expenses outside this month do not alert`() = runBlocking {
        addViaForm(food, "5.000.000", TransactionType.TRANSFER)
        t.budgetAlerts.onExpenseAdded(TransactionType.EXPENSE, food, 900_000, Instant.parse("2025-12-31T16:59:59Z")) // 31 Dec WIB
        t.transactions.addTransaction(TransactionDraft(TransactionType.EXPENSE, wallet, null, food, 900_000, Instant.parse("2025-12-31T16:59:59Z"))).requireId()
        assertTrue(t.deliveredAlerts.isEmpty())
    }

    @Test
    fun `notifications are posted only with permission`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val notifications = BudgetNotifications(app).apply { createChannel() }
        val manager = app.getSystemService(NotificationManager::class.java)
        val alert = BudgetAlert("b1", null, BudgetAlertKind.OVER_LIMIT, 1_200_000, 1_000_000)

        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        notifications.deliver(listOf(alert), emptyMap())
        assertEquals(0, shadowOf(manager).allNotifications.size)

        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        notifications.deliver(listOf(alert), emptyMap())
        val posted = shadowOf(manager).allNotifications.single()
        assertEquals("Anggaran Semua pengeluaran terlampaui", shadowOf(posted).contentTitle)
        assertEquals("Rp1.200.000 dari Rp1.000.000 bulan ini", shadowOf(posted).contentText)
        // Lock screen: amounts and names hidden behind a generic public version; tapping opens the app.
        assertEquals(android.app.Notification.VISIBILITY_PRIVATE, posted.visibility)
        assertEquals("Pemberitahuan anggaran", shadowOf(posted.publicVersion).contentTitle)
        assertEquals(null, shadowOf(posted.publicVersion).contentText)
        assertEquals(
            com.fintrack.app.MainActivity::class.java.name,
            shadowOf(posted.contentIntent).savedIntent.component?.className,
        )
    }
}
