package com.fintrack.app.core.common

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Emits when the user or network changes the device time, date or time zone. These are protected
 * system broadcasts, which the system still delivers to a not-exported receiver. The receiver is
 * registered only while the flow is collected.
 */
fun systemTimeChanges(context: Context): Flow<Unit> = callbackFlow {
    val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            trySend(Unit)
        }
    }
    val filter = IntentFilter().apply {
        addAction(Intent.ACTION_TIME_CHANGED)
        addAction(Intent.ACTION_DATE_CHANGED)
        addAction(Intent.ACTION_TIMEZONE_CHANGED)
    }
    ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    awaitClose { context.unregisterReceiver(receiver) }
}
