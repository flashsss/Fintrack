package com.fintrack.app.presentation.ui

import android.os.Looper
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.fintrack.app.core.common.FileReadResult
import com.fintrack.app.core.ui.theme.FinTrackTheme
import com.fintrack.app.data.TestDatabase
import com.fintrack.app.data.requireId
import com.fintrack.app.domain.model.CategoryType
import com.fintrack.app.domain.model.WalletType
import com.fintrack.app.presentation.importing.ImportScreen
import com.fintrack.app.presentation.importing.ImportViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class ImportScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val t = TestDatabase()

    @After
    fun tearDown() = t.close()

    private fun waitForText(text: String) {
        val deadline = System.currentTimeMillis() + 5_000
        while (compose.onAllNodesWithText(text).fetchSemanticsNodes().isEmpty()) {
            check(System.currentTimeMillis() < deadline) { "\"$text\" not shown within 5 s" }
            shadowOf(Looper.getMainLooper()).idle()
            compose.waitForIdle()
            Thread.sleep(10)
        }
    }

    @Test
    fun `review shows counts and reasons and import happens only after confirmation`() {
        runBlocking {
            t.wallets.createWallet("BCA", WalletType.BANK, 0).requireId()
            t.categories.createCategory("Lainnya", CategoryType.EXPENSE, null, null, null).requireId()
            t.categories.createCategory("Pemasukan lain", CategoryType.INCOME, null, null, null).requireId()
        }
        val csv = "Tanggal,Jumlah,Keterangan\n30/12/2025,-25000,Kopi\n31/12/2025,abc,Rusak\n"
        val vm = ImportViewModel({ FileReadResult.Text(csv) }, t.imports, t.wallets, t.categories, t.clock)
        compose.setContent { FinTrackTheme { ImportScreen(onBack = {}, viewModel = vm) } }

        compose.onNodeWithText("Pilih file CSV").assertExists()
        vm.onFilePicked("content://test") // the system picker cannot run under Robolectric
        waitForText("2 baris ditemukan")

        compose.onNodeWithText("Dompet tujuan impor").performScrollTo().performClick()
        waitForText("BCA")
        compose.onNodeWithText("BCA").performClick()
        compose.onNodeWithText("Kategori pengeluaran bawaan").performScrollTo().performClick()
        waitForText("Lainnya")
        compose.onNodeWithText("Lainnya").performClick()
        compose.onNodeWithText("Kategori pemasukan bawaan").performScrollTo().performClick()
        waitForText("Pemasukan lain")
        compose.onNodeWithText("Pemasukan lain").performClick()
        compose.onNodeWithText("Periksa").performScrollTo().performClick()

        waitForText("1 baris siap diimpor")
        compose.onNodeWithText("1 baris gagal").assertExists()
        compose.onNodeWithText("Baris 3: jumlah bukan angka").performScrollTo().assertExists()
        compose.onNodeWithText("Impor 1 transaksi").performScrollTo().performClick()

        waitForText("Impor transaksi?")
        assertTrue(runBlocking { t.transactions.observeTransactions().first() }.isEmpty())
        compose.onNodeWithText("Impor").performClick()

        waitForText("1 transaksi berhasil diimpor.")
        assertEquals(25_000L, runBlocking { t.transactions.observeTransactions().first().single().amount })
    }
}
