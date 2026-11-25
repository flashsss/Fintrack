package com.fintrack.app.core.common

import android.content.Context
import android.content.Intent
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/** The real broadcast path: register, receive each system action, unregister on cancel. */
@RunWith(AndroidJUnit4::class)
class SystemTimeChangesTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `time, date and time-zone broadcasts are delivered while collected and not after`() {
        val scope = CoroutineScope(Dispatchers.Main.immediate)
        var received = 0
        scope.launch { systemTimeChanges(context).collect { received++ } }
        shadowOf(Looper.getMainLooper()).idle()

        for (action in listOf(Intent.ACTION_TIME_CHANGED, Intent.ACTION_DATE_CHANGED, Intent.ACTION_TIMEZONE_CHANGED)) {
            context.sendBroadcast(Intent(action))
            shadowOf(Looper.getMainLooper()).idle()
        }
        assertEquals(3, received)

        scope.cancel()
        shadowOf(Looper.getMainLooper()).idle()
        context.sendBroadcast(Intent(Intent.ACTION_TIME_CHANGED))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("receiver must be unregistered when collection stops", 3, received)
    }
}
