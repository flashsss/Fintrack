package com.fintrack.app.presentation.ui

import android.content.Context
import android.os.Looper
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.fintrack.app.core.ui.theme.FinTrackTheme
import com.fintrack.app.data.TestDatabase
import com.fintrack.app.data.requireId
import com.fintrack.app.domain.model.WalletType
import com.fintrack.app.presentation.settings.SettingsScreen
import com.fintrack.app.presentation.sync.SyncScreen
import com.fintrack.app.presentation.sync.SyncViewModel
import com.fintrack.app.sync.AuthRepository
import com.fintrack.app.sync.SupabaseAccess
import com.fintrack.app.sync.SupabaseConfig
import com.fintrack.app.sync.SyncScheduler
import com.fintrack.app.sync.SyncStateStore
import io.github.jan.supabase.auth.MemoryCodeVerifierCache
import io.github.jan.supabase.auth.MemorySessionManager
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
import java.io.File

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class SyncScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val t = TestDatabase()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val dir = File(context.cacheDir, "sync-ui-${System.nanoTime()}")
    private val json = headersOf(HttpHeaders.ContentType, "application/json")
    private var passwordOk = false
    private var signInRequests = 0

    private val engine = MockEngine { request ->
        if (request.url.encodedPath == "/auth/v1/token") {
            signInRequests++
            if (passwordOk) respond(
                """{"access_token":"a","token_type":"bearer","expires_in":3600,"refresh_token":"r",
                   "user":{"id":"6f1c1b7e-0000-4000-8000-000000000001","aud":"authenticated","email":"saya@example.com"}}""",
                HttpStatusCode.OK, json,
            ) else respond("""{"code":400,"error_code":"invalid_credentials","msg":"Invalid login credentials"}""", HttpStatusCode.BadRequest, json)
        } else {
            respond("", HttpStatusCode.NoContent, json) // logout
        }
    }

    private fun viewModel(): SyncViewModel {
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().setExecutor(SynchronousExecutor()).build())
        val access = SupabaseAccess.create(
            SupabaseConfig("https://example.supabase.co", "sb_publishable_test"), engine,
            MemorySessionManager(), MemoryCodeVerifierCache(), lifecycleCallbacks = false,
        )
        val state = SyncStateStore(PreferenceDataStoreFactory.create(scope = scope) { File(dir, "s.preferences_pb") })
        return SyncViewModel(AuthRepository(access), state, t.db.syncDao(), SyncScheduler(WorkManager.getInstance(context)))
    }

    @After
    fun tearDown() {
        scope.cancel()
        t.close()
        dir.deleteRecursively()
    }

    private fun waitForText(text: String) {
        val deadline = System.currentTimeMillis() + 5_000
        while (compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isEmpty()) {
            check(System.currentTimeMillis() < deadline) { "\"$text\" not shown within 5 s" }
            shadowOf(Looper.getMainLooper()).idle()
            compose.waitForIdle()
            Thread.sleep(10)
        }
    }

    @Test
    fun `sign in validates, reports a wrong password, then shows the account and pending changes`() {
        runBlocking { t.wallets.createWallet("BCA", WalletType.BANK, 0).requireId() }
        val vm = viewModel()
        compose.setContent { FinTrackTheme { SyncScreen(onBack = {}, viewModel = vm) } }

        waitForText("Masuk sekali dengan akun Supabase Anda")
        compose.onNodeWithText("Masuk").performClick()
        waitForText("Masukkan email.")
        compose.onNodeWithText("Masukkan kata sandi.").assertExists()
        assertEquals(0, signInRequests)

        compose.onNodeWithText("Email").performTextInput("saya@example.com")
        compose.onNodeWithText("Kata sandi").performTextInput("salah")
        compose.onNodeWithText("Masuk").performClick()
        waitForText("Email atau kata sandi salah.")

        passwordOk = true
        compose.onNodeWithText("Masuk").performClick()
        waitForText("Masuk sebagai saya@example.com")
        waitForText("1 perubahan menunggu dikirim")
        compose.onNodeWithText("Belum pernah sinkron.").assertExists()

        compose.onNodeWithText("Keluar").performClick()
        waitForText("Keluar dari sinkronisasi?")
        compose.onAllNodesWithText("Keluar")[1].performClick() // dialog button
        waitForText("Masuk sekali dengan akun Supabase Anda")
        assertEquals(1, runBlocking { t.wallets.observeBalances().first() }.size) // data kept
    }

    @Test
    fun `settings shows the sync item only when configured`() {
        var opened = false
        val configured = androidx.compose.runtime.mutableStateOf(false)
        compose.setContent {
            FinTrackTheme {
                SettingsScreen(onOpenCategories = {}, onOpenPlanning = {}, onOpenImport = {}, onOpenSync = if (configured.value) ({ opened = true }) else null)
            }
        }
        assertTrue(compose.onAllNodesWithText("Sinkronisasi cloud").fetchSemanticsNodes().isEmpty())
        configured.value = true
        compose.onNodeWithText("Sinkronisasi cloud").performClick()
        assertTrue(opened)
    }
}
