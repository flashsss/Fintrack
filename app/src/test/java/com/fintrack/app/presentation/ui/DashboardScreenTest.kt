package com.fintrack.app.presentation.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
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
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.math.abs

/**
 * Dashboard UI on a real in-memory database. Native graphics mode makes Vico actually draw, so the
 * chart tests inspect rendered pixels.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class DashboardScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val t = TestDatabase()

    // 25 Nov 2026, 10:00 WIB.
    private val clock = Clock.fixed(Instant.parse("2026-11-25T03:00:00Z"), ZoneOffset.UTC)

    @After
    fun tearDown() = t.close()

    private var openedTransaction: String? = null
    private var openedWallet: String? = null

    private fun show(darkTheme: Boolean = false) {
        val vm = DashboardViewModel(t.reports, t.wallets, t.categories, t.todayProvider(clock))
        compose.setContent {
            FinTrackTheme(darkTheme = darkTheme) {
                DashboardScreen(
                    onOpenTransaction = { openedTransaction = it },
                    onOpenWallet = { openedWallet = it },
                    onSeeAllTransactions = {},
                    viewModel = vm,
                )
            }
        }
    }

    private fun waitForText(text: String, substring: Boolean = false) = compose.waitUntil(5_000) {
        compose.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty()
    }

    private fun scrollTo(text: String, substring: Boolean = false) =
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(text, substring = substring))

    private fun seed(): String = runBlocking {
        val bca = t.wallets.createWallet("BCA", WalletType.BANK, 10_000_000).requireId()
        val mandiri = t.wallets.createWallet("Mandiri", WalletType.BANK, 5_000_000).requireId()
        val salary = t.categories.createCategory("Gaji", CategoryType.INCOME, null, null, null).requireId()
        val food = t.categories.createCategory("Makanan", CategoryType.EXPENSE, null, null, null).requireId()
        suspend fun add(type: TransactionType, amount: Long, at: String, category: String?, dest: String? = null, note: String? = null) =
            t.transactions.addTransaction(TransactionDraft(type, bca, dest, category, amount, Instant.parse(at), note)).requireId()
        add(TransactionType.INCOME, 4_000_000, "2026-10-05T03:00:00Z", salary)
        add(TransactionType.INCOME, 5_000_000, "2026-11-01T03:00:00Z", salary)
        add(TransactionType.EXPENSE, 2_000_000, "2026-11-10T03:00:00Z", food, note = "Belanja bulanan")
        add(TransactionType.TRANSFER, 3_000_000, "2026-11-24T03:00:00Z", null, dest = mandiri)
    }

    private fun descriptionMatcher(prefix: String) = SemanticsMatcher("contentDescription starts with $prefix") { node ->
        node.config.getOrElseNullable(SemanticsProperties.ContentDescription) { null }?.any { it.startsWith(prefix) } == true
    }

    /** Counts pixels within a small tolerance of [color]. */
    private fun ImageBitmap.countPixels(color: Color): Int {
        val pixels = toPixelMap()
        var count = 0
        for (x in 0 until width) for (y in 0 until height) {
            val p = pixels[x, y]
            if (abs(p.red - color.red) < 0.03f && abs(p.green - color.green) < 0.03f && abs(p.blue - color.blue) < 0.03f) count++
        }
        return count
    }

    @Test
    fun `empty dashboard explains what to do`() {
        show()
        waitForText("Belum ada data keuangan. Tambahkan dompet di menu Dompet, lalu catat transaksi pertama.")
    }

    @Test
    fun `wallets without transactions show balance and no-data states`() {
        runBlocking { t.wallets.createWallet("Tunai", WalletType.CASH, 150_000).requireId() }
        show()
        waitForText("Rp150.000")
        scrollTo("Tidak ada pemasukan atau pengeluaran pada periode ini.")
        scrollTo("Belum ada pengeluaran pada periode ini.")
        scrollTo("Tidak tersedia (tidak ada pemasukan)")
    }

    @Test
    fun `populated dashboard shows database values`() {
        seed()
        show()
        waitForText("Rp22.000.000")
        compose.onNodeWithText("1–25 Nov 2026 · berjalan").assertExists()
        scrollTo("+Rp3.000.000") // net cash flow
        scrollTo("60,0%") // saving rate
        scrollTo("3 transaksi, termasuk 1 transfer")
        scrollTo("Makanan")
        scrollTo("100,0%")
        scrollTo("1–25 Nov 2026 dibandingkan 1–25 Okt 2026")
        scrollTo("+25,0%") // income vs same days of October
        scrollTo("Persentase tidak tersedia (nilai sebelumnya Rp0)") // October had no expense
        scrollTo("BCA → Mandiri")
        scrollTo("Lihat semua transaksi")
    }

    @Test
    fun `selecting last month updates the period-dependent sections`() {
        seed()
        show()
        waitForText("Rp22.000.000")
        compose.onNodeWithText("Bulan lalu").performClick()
        waitForText("1–31 Okt 2026")
        // October: income 4.000.000, no expense. The balance does not depend on the period.
        compose.onNodeWithText("Rp22.000.000").assertExists()
        scrollTo("+Rp4.000.000")
        scrollTo("100,0%") // saving rate
        scrollTo("Belum ada pengeluaran pada periode ini.")
        scrollTo("1–31 Okt 2026 dibandingkan 1–30 Sep 2026")
    }

    private fun assertChartsDrawn(dark: Boolean) {
        seed()
        show(darkTheme = dark)
        waitForText("Rp22.000.000")
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(descriptionMatcher("Grafik batang harian"))
        val bars = compose.onNode(descriptionMatcher("Grafik batang harian")).captureToImage()
        val income = if (dark) Color(0xFF5EE6C0) else Color(0xFF00796B)
        val expense = if (dark) Color(0xFFFFB08A) else Color(0xFFC2551F)
        assertTrue("income bars drawn", bars.countPixels(income) > 20)
        assertTrue("expense bars drawn", bars.countPixels(expense) > 20)

        compose.onNode(hasScrollToIndexAction()).performScrollToNode(descriptionMatcher("Diagram donat"))
        val donut = compose.onNode(descriptionMatcher("Diagram donat")).captureToImage()
        // A single category ("Makanan") takes the first categorical color, which equals the income color.
        assertTrue("donut slice drawn", donut.countPixels(income) > 200)
    }

    @Test
    fun `charts render real data in the light theme`() = assertChartsDrawn(dark = false)

    @Test
    fun `charts render real data in the dark theme`() = assertChartsDrawn(dark = true)

    @Test
    fun `recent transaction and wallet open their detail screens`() {
        val transferId = seed()
        show()
        waitForText("Rp22.000.000")
        scrollTo("BCA → Mandiri")
        compose.onNodeWithText("BCA → Mandiri").performClick()
        compose.waitUntil(5_000) { openedTransaction != null }
        assertEquals(transferId, openedTransaction)

        scrollTo("Rp8.000.000")
        compose.onNodeWithText("Rp8.000.000").performClick()
        compose.waitUntil(5_000) { openedWallet != null }
    }
}
