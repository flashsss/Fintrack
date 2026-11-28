package com.fintrack.app.presentation.ui

import android.graphics.Bitmap
import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.fintrack.app.core.ui.theme.FinTrackTheme
import com.fintrack.app.data.TestDatabase
import com.fintrack.app.data.requireId
import com.fintrack.app.domain.model.CategoryType
import com.fintrack.app.domain.model.TransactionDraft
import com.fintrack.app.domain.model.TransactionType
import com.fintrack.app.domain.model.WalletType
import com.fintrack.app.presentation.dashboard.DashboardScreen
import com.fintrack.app.presentation.dashboard.DashboardViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * Renders screens to PNG for design review (not an assertion test). Runs only when the
 * FINTRACK_SHOTS environment variable names an output folder.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class DesignShots {

    @get:Rule
    val compose = createComposeRule()

    private val out = System.getenv("FINTRACK_SHOTS")?.let(::File)
    private val t = TestDatabase()
    private val clock = Clock.fixed(Instant.parse("2026-11-25T03:00:00Z"), ZoneOffset.UTC)

    @Before
    fun only() = assumeTrue("set FINTRACK_SHOTS to render", out != null)

    @After
    fun tearDown() = t.close()

    private fun seed() = runBlocking {
        val bca = t.wallets.createWallet("BCA", WalletType.BANK, 10_000_000).requireId()
        val gopay = t.wallets.createWallet("GoPay", WalletType.E_WALLET, 500_000).requireId()
        t.wallets.createWallet("Kartu Kredit", WalletType.CREDIT_CARD, 0).requireId()
        val salary = t.categories.createCategory("Gaji", CategoryType.INCOME, null, null, null).requireId()
        val food = t.categories.createCategory("Makanan", CategoryType.EXPENSE, null, null, null).requireId()
        val home = t.categories.createCategory("Tempat tinggal", CategoryType.EXPENSE, null, null, null).requireId()
        val transport = t.categories.createCategory("Transportasi", CategoryType.EXPENSE, null, null, null).requireId()
        suspend fun add(type: TransactionType, amount: Long, day: Int, category: String?, wallet: String = bca, dest: String? = null, note: String? = null) =
            t.transactions.addTransaction(TransactionDraft(type, wallet, dest, category, amount, Instant.parse("2026-11-%02dT03:00:00Z".format(day)), note)).requireId()
        add(TransactionType.INCOME, 12_500_000, 1, salary, note = "Gaji November")
        add(TransactionType.EXPENSE, 1_800_000, 2, home, note = "Kos")
        listOf(3 to 85_000L, 7 to 120_000L, 12 to 64_000L, 18 to 210_000L, 21 to 95_000L, 24 to 32_000L).forEach { (d, a) -> add(TransactionType.EXPENSE, a, d, food, note = "Makan") }
        add(TransactionType.EXPENSE, 150_000, 15, transport, wallet = gopay, note = "Ojek")
        add(TransactionType.TRANSFER, 200_000, 20, null, dest = gopay)
    }

    private fun shoot(name: String, waitFor: String, content: @Composable () -> Unit) {
        compose.setContent(content)
        val deadline = System.currentTimeMillis() + 5_000
        while (compose.onAllNodesWithText(waitFor, substring = true).fetchSemanticsNodes().isEmpty()) {
            check(System.currentTimeMillis() < deadline) { "$waitFor not shown" }
            shadowOf(Looper.getMainLooper()).idle(); compose.waitForIdle(); Thread.sleep(10)
        }
        settle()
        out!!.mkdirs()
        save("$name-1")
        val list = compose.onAllNodes(hasScrollToIndexAction()).fetchSemanticsNodes()
        if (list.isNotEmpty()) {
            compose.onAllNodes(hasScrollToIndexAction())[0].performScrollToIndex(lastIndex())
            settle()
            save("$name-2")
        }
    }

    private fun lastIndex(): Int = 20.let { max ->
        // Scroll as far as the list allows; LazyColumn clamps the index.
        var index = max
        while (index > 0) {
            try {
                compose.onAllNodes(hasScrollToIndexAction())[0].performScrollToIndex(index)
                return index
            } catch (error: Throwable) {
                index--
            }
        }
        0
    }

    private fun settle() = repeat(30) { shadowOf(Looper.getMainLooper()).idle(); compose.waitForIdle(); Thread.sleep(10) }

    private fun save(name: String) =
        File(out, "$name.png").outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }

    @Test fun dashboardLight() = dashboard(false)
    @Test fun dashboardDark() = dashboard(true)

    private fun dashboard(dark: Boolean) {
        seed()
        val vm = DashboardViewModel(t.reports, t.wallets, t.categories, t.todayProvider(clock))
        shoot(if (dark) "beranda-gelap" else "beranda-terang", "Total saldo dompet aktif") {
            FinTrackTheme(darkTheme = dark) { DashboardScreen({}, {}, {}, viewModel = vm) }
        }
    }

    private fun seedPlanning() = runBlocking {
        val food = t.categories.observeAllCategories().first().first { it.name == "Makanan" }.id
        t.planning.createBudget(null, 8_000_000)
        t.planning.createBudget(food, 600_000)
        t.planning.createGoal("Dana darurat", 30_000_000, 7_500_000, null)
    }

    private val today get() = t.todayProvider(clock)

    @Test fun transactionsLight() = transactions(false)
    @Test fun transactionsDark() = transactions(true)
    private fun transactions(dark: Boolean) {
        seed()
        val vm = com.fintrack.app.presentation.transactions.TransactionListViewModel(t.transactions, t.wallets, t.categories, today)
        shoot("transaksi-" + if (dark) "gelap" else "terang", "Makan") {
            FinTrackTheme(dark) { com.fintrack.app.presentation.transactions.TransactionListScreen({}, {}, viewModel = vm) }
        }
    }

    @Test fun wallets() {
        seed()
        val vm = com.fintrack.app.presentation.wallets.WalletListViewModel(t.wallets)
        shoot("dompet-terang", "GoPay") { FinTrackTheme(false) { com.fintrack.app.presentation.wallets.WalletListScreen({}, {}, viewModel = vm) } }
    }

    @Test fun walletDetail() {
        seed()
        val wallet = runBlocking { t.wallets.observeActiveWallets().first().first { it.name == "BCA" }.id }
        val vm = com.fintrack.app.presentation.wallets.WalletDetailViewModel(
            androidx.lifecycle.SavedStateHandle(mapOf(com.fintrack.app.presentation.navigation.Routes.ARG_WALLET_ID to wallet)), t.wallets, t.transactions, t.categories,
        )
        shoot("dompet-detail-gelap", "Riwayat transaksi") { FinTrackTheme(true) { com.fintrack.app.presentation.wallets.WalletDetailScreen({}, {}, {}, viewModel = vm) } }
    }

    @Test fun analysis() {
        seed()
        val vm = com.fintrack.app.presentation.analysis.AnalysisViewModel(t.reports, t.analytics, t.categories, today)
        shoot("analisis-terang", "Wawasan") { FinTrackTheme(false) { com.fintrack.app.presentation.analysis.AnalysisScreen(viewModel = vm) } }
    }

    @Test fun planning() {
        seed()
        seedPlanning()
        val vm = com.fintrack.app.presentation.planning.PlanningViewModel(t.planning, t.reports, t.categories, today)
        shoot("perencanaan-terang", "Dana darurat") { FinTrackTheme(false) { com.fintrack.app.presentation.planning.PlanningScreen({}, {}, {}, {}, {}, viewModel = vm) } }
    }

    @Test fun settings() {
        shoot("pengaturan-gelap", "Kategori") { FinTrackTheme(true) { com.fintrack.app.presentation.settings.SettingsScreen({}, {}, {}, {}) } }
    }
}
