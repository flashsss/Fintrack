package com.fintrack.app.presentation.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.fintrack.app.core.common.MutableClock
import com.fintrack.app.core.ui.theme.FinTrackTheme
import com.fintrack.app.data.TestDatabase
import com.fintrack.app.data.requireId
import com.fintrack.app.domain.model.CategoryType
import com.fintrack.app.domain.model.TransactionDraft
import com.fintrack.app.domain.model.TransactionType
import com.fintrack.app.domain.model.WalletType
import com.fintrack.app.presentation.analysis.AnalysisScreen
import com.fintrack.app.presentation.analysis.AnalysisViewModel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Instant
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class AnalysisScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val t = TestDatabase()

    @After
    fun tearDown() = t.close()

    private fun show(dark: Boolean = false) {
        val vm = AnalysisViewModel(t.reports, t.analytics, t.categories, t.todayProvider(MutableClock(Instant.parse("2026-11-25T03:00:00Z"))))
        vm.onTabChange(com.fintrack.app.presentation.analysis.AnalysisTab.TREND)
        compose.setContent { FinTrackTheme(darkTheme = dark) { AnalysisScreen(vm) } }
    }

    private fun waitForText(text: String) = compose.waitUntil(5_000) {
        compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
    }

    private fun seed() = runBlocking {
        val bca = t.wallets.createWallet("BCA", WalletType.BANK, 0).requireId()
        val salary = t.categories.createCategory("Gaji", CategoryType.INCOME, null, null, null).requireId()
        val food = t.categories.createCategory("Makanan", CategoryType.EXPENSE, null, null, null).requireId()
        t.transactions.addTransaction(TransactionDraft(TransactionType.INCOME, bca, null, salary, 8_000_000, Instant.parse("2026-10-01T03:00:00Z"))).requireId()
        t.transactions.addTransaction(TransactionDraft(TransactionType.EXPENSE, bca, null, food, 3_000_000, Instant.parse("2026-10-15T03:00:00Z"))).requireId()
        t.transactions.addTransaction(TransactionDraft(TransactionType.EXPENSE, bca, null, food, 1_500_000, Instant.parse("2026-11-10T03:00:00Z"))).requireId()
    }

    private fun ImageBitmap.countPixels(color: Color): Int {
        val pixels = toPixelMap()
        var count = 0
        for (x in 0 until width) for (y in 0 until height) {
            val p = pixels[x, y]
            if (abs(p.red - color.red) < 0.03f && abs(p.green - color.green) < 0.03f && abs(p.blue - color.blue) < 0.03f) count++
        }
        return count
    }

    private val chart = SemanticsMatcher("trend chart") { node ->
        node.config.getOrElseNullable(SemanticsProperties.ContentDescription) { null }?.any { it.startsWith("Grafik batang pemasukan") } == true
    }

    @Test
    fun `empty data shows an explicit empty state, not a chart`() {
        show()
        waitForText("Tidak ada pemasukan atau pengeluaran pada rentang ini.")
        assertTrue(compose.onAllNodes(chart).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun `trend renders real bars with Indonesian formatting`() {
        seed()
        show()
        waitForText("Rp8.000.000")
        compose.onNodeWithText("1 Sep – 25 Nov 2026 · berjalan", substring = true).assertExists()
        val image = compose.onNode(chart).captureToImage()
        assertTrue("income bars", image.countPixels(Color(0xFF00796B)) > 20)
        assertTrue("expense bars", image.countPixels(Color(0xFFC0503E)) > 20)
        compose.onNodeWithText("* Sebagian", substring = true).performScrollTo().assertExists()
        assertTrue(compose.onAllNodesWithText("Rp4.500.000").fetchSemanticsNodes().isNotEmpty()) // total expense
    }

    @Test
    fun `dark theme draws the bars in dark-theme colors`() {
        seed()
        show(dark = true)
        waitForText("Rp8.000.000")
        val image = compose.onNode(chart).captureToImage()
        assertTrue(image.countPixels(Color(0xFF4FD1C0)) > 20)
        assertTrue(image.countPixels(Color(0xFFF2937F)) > 20)
    }

    @Test
    fun `selecting this month switches to daily buckets`() {
        seed()
        show()
        waitForText("Rp8.000.000")
        compose.onNodeWithText("Bulan ini").performClick()
        waitForText("1–25 Nov 2026 · berjalan")
        waitForText("Rp1.500.000")
        compose.onNodeWithText("Harian").assertExists()
    }

    @Test
    fun `category tab ranks categories and shows the monthly trend of a tapped one`() {
        seed()
        show()
        waitForText("Rp8.000.000")
        compose.onNodeWithText("Kategori").performClick()
        waitForText("Total pengeluaran")
        waitForText("Makanan") // rows load after the header
        compose.onAllNodesWithText("Makanan")[0].performClick() // ranking row (it also appears under movers)
        waitForText("Tren bulanan: Makanan")
        compose.onNodeWithText("Per induk").performClick()
        waitForText("Rp4.500.000")
    }

    @Test
    fun `insights tab lists evaluated rules with reasons when data is missing`() {
        seed()
        show()
        compose.onNodeWithText("Wawasan").performClick()
        waitForText("Aturan yang diperiksa")
        waitForText("Belum cukup data: belum ada kategori")
        waitForText("Belum cukup data: pemasukan periode sebelumnya kurang dari Rp1.000.000.")
        // Rule text is not a format string: it must read "30%", never "30%%".
        waitForText("naik minimal 30% dan Rp200.000")
        assertTrue(compose.onAllNodesWithText("%%", substring = true).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun `pattern tab shows weekdays normalized by occurrences`() {
        seed()
        show()
        compose.onNodeWithText("Pola").performScrollTo().performClick() // fifth tab, beyond the visible tab row
        waitForText("Pengeluaran per hari dalam minggu")
        waitForText("Kamis") // 15 Oct 2026
        waitForText("Akhir pekan vs hari kerja")
        compose.onNodeWithText("Median (P50)").performScrollTo().assertExists()
        compose.onNodeWithText("— (butuh minimal 10 transaksi)", substring = true).performScrollTo().assertExists() // P90 with 2 expenses
    }

    @Test
    fun `merchant tab explains missing merchant data`() {
        seed()
        show()
        compose.onNodeWithText("Merchant").performScrollTo().performClick()
        waitForText("Belum ada pengeluaran dengan merchant pada rentang ini.")
    }
}
