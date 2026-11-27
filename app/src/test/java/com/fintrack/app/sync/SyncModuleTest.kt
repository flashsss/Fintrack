package com.fintrack.app.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/** The production client setup works without App Startup (which does not run under Robolectric). */
@RunWith(AndroidJUnit4::class)
class SyncModuleTest {
    @Test
    fun `production access is created with app-private storage and starts signed out`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        // Lifecycle callbacks wait for the main looper, which Robolectric keeps paused.
        val access = SyncModule.createAccess(SupabaseConfig("https://example.supabase.co", "sb_publishable_test"), context, lifecycleCallbacks = false)
        assertNotNull(access.client)
        assertEquals(null, AuthRepository(access).currentUserId())
    }

    @Test
    fun `no client without configuration`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertEquals(null, SyncModule.createAccess(SupabaseConfig("", ""), context, lifecycleCallbacks = false).client)
    }
}
