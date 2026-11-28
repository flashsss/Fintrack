package com.fintrack.app.presentation.ui

import android.os.Looper
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.fintrack.app.core.ui.theme.FinTrackTheme
import com.fintrack.app.data.TestDatabase
import com.fintrack.app.data.requireId
import com.fintrack.app.domain.model.CategoryType
import com.fintrack.app.domain.model.TransactionDraft
import com.fintrack.app.domain.model.TransactionType
import com.fintrack.app.domain.model.WalletType
import com.fintrack.app.presentation.importing.ImportScreen
import com.fintrack.app.presentation.importing.ImportViewModel
import com.fintrack.app.presentation.navigation.Routes
import com.fintrack.app.presentation.settings.SettingsScreen
import com.fintrack.app.presentation.transactions.TransactionFormScreen
import com.fintrack.app.presentation.transactions.TransactionFormViewModel
import com.fintrack.app.presentation.transactions.TransactionListScreen
import com.fintrack.app.presentation.transactions.TransactionListViewModel
import com.fintrack.app.presentation.wallets.WalletListScreen
import com.fintrack.app.presentation.wallets.WalletListViewModel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Instant

/**
 * Accessibility check on the semantics tree that TalkBack reads: every node with a click action
 * has a spoken label (text or content description). Google's Accessibility Test Framework does
 * not see Compose nodes under Robolectric, so the rule is checked directly; the negative control
 * proves the check can fail. Touch-target size is not checked here: Compose hit testing extends
 * every clickable to the 48dp minimum, and Material components add it to their layout.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class AccessibilityChecksTest {

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

    /**
     * Lets the screen's initial Room queries finish, so none is still running when the test closes
     * the database (that race made this test flaky).
     */
    private fun settle() {
        val until = System.currentTimeMillis() + 300
        while (System.currentTimeMillis() < until) {
            shadowOf(Looper.getMainLooper()).idle()
            compose.waitForIdle()
            Thread.sleep(10)
        }
    }

    /** Problems found in the merged semantics tree, as readable lines. */
    private fun problems(): List<String> {
        val found = mutableListOf<String>()
        fun visit(node: SemanticsNode) {
            val config = node.config
            if (config.getOrNull(SemanticsActions.OnClick) != null) {
                val label = (config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } +
                    config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() +
                    config.getOrNull(SemanticsProperties.EditableText)?.text.orEmpty()).joinToString().trim()
                // Text fields are labeled by their placeholder/label, which Compose exposes as Text.
                if (label.isEmpty()) found += "unlabeled clickable at ${node.boundsInRoot}"
            }
            node.children.forEach(::visit)
        }
        visit(compose.onRoot().fetchSemanticsNode())
        return found
    }

    private fun assertAccessible(waitFor: String? = null, content: @Composable () -> Unit) {
        compose.setContent { FinTrackTheme { content() } }
        if (waitFor != null) waitForText(waitFor)
        settle()
        assertEquals(emptyList<String>(), problems())
    }

    @Test
    fun `transaction form`() {
        runBlocking { t.wallets.createWallet("BCA", WalletType.BANK, 0).requireId() }
        val vm = TransactionFormViewModel(
            SavedStateHandle(mapOf(Routes.ARG_TYPE to TransactionType.EXPENSE.name)),
            t.transactions, t.wallets, t.categories, t.budgetAlerts, t.clock,
        )
        assertAccessible { TransactionFormScreen(onBack = {}, onSaved = {}, viewModel = vm) }
    }

    @Test
    fun `transaction list with rows`() {
        runBlocking {
            val bca = t.wallets.createWallet("BCA", WalletType.BANK, 0).requireId()
            val food = t.categories.createCategory("Makanan", CategoryType.EXPENSE, null, null, null).requireId()
            t.transactions.addTransaction(
                TransactionDraft(TransactionType.EXPENSE, bca, null, food, 25_000, Instant.parse("2026-01-01T00:00:00Z"), "Kopi"),
            ).requireId()
        }
        val vm = TransactionListViewModel(t.transactions, t.wallets, t.categories, t.todayProvider())
        assertAccessible(waitFor = "Kamis, 1 Januari 2026") {
            TransactionListScreen(onAddTransaction = {}, onOpenTransaction = {}, viewModel = vm)
        }
    }

    @Test
    fun `wallet list`() {
        runBlocking { t.wallets.createWallet("BCA", WalletType.BANK, 10_000).requireId() }
        val vm = WalletListViewModel(t.wallets)
        assertAccessible(waitFor = "BCA") { WalletListScreen(onAddWallet = {}, onOpenWallet = {}, viewModel = vm) }
    }

    @Test
    fun `settings`() {
        assertAccessible { SettingsScreen(onOpenCategories = {}, onOpenPlanning = {}, onOpenImport = {}) }
    }

    @Test
    fun `import start`() {
        val vm = ImportViewModel({ error("not used") }, t.imports, t.wallets, t.categories, t.clock)
        assertAccessible { ImportScreen(onBack = {}, viewModel = vm) }
    }

    @Test
    fun `negative control - an unlabeled button is reported`() {
        compose.setContent { Box(Modifier.size(10.dp).clickable(onClick = {})) }
        val found = problems()
        assertTrue(found.toString(), found.single().startsWith("unlabeled"))
    }
}
