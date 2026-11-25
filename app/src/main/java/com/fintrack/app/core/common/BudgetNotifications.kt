package com.fintrack.app.core.common

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.fintrack.app.MainActivity
import com.fintrack.app.R
import com.fintrack.app.analytics.metrics.BudgetAlert
import com.fintrack.app.analytics.metrics.BudgetAlertKind
import com.fintrack.app.core.util.formatRupiah
import com.fintrack.app.data.repository.BudgetAlertSink

/** Posts budget alerts as system notifications when the user allowed notifications. */
class BudgetNotifications(private val context: Context) : BudgetAlertSink {

    fun createChannel() {
        val channel = NotificationChannel(CHANNEL_ID, context.getString(R.string.notification_channel_budget), NotificationManager.IMPORTANCE_DEFAULT)
        channel.description = context.getString(R.string.notification_channel_budget_description)
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    fun canPost(): Boolean =
        NotificationManagerCompat.from(context).areNotificationsEnabled() &&
            (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)

    override fun deliver(alerts: List<BudgetAlert>, categoryNames: Map<String, String>) {
        if (!canPost()) return
        val manager = NotificationManagerCompat.from(context)
        alerts.forEach { alert ->
            val name = alert.categoryId?.let(categoryNames::get) ?: context.getString(R.string.budget_all_expenses)
            val title = context.getString(
                if (alert.kind == BudgetAlertKind.OVER_LIMIT) R.string.notification_budget_over_title else R.string.notification_budget_near_title,
                name,
            )
            val text = context.getString(R.string.notification_budget_text, formatRupiah(alert.spent), formatRupiah(alert.limit))
            // On a secure lock screen only a generic line is shown; names and amounts stay hidden.
            val publicVersion = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_account_balance_wallet)
                .setContentTitle(context.getString(R.string.notification_budget_public_title))
                .build()
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_account_balance_wallet)
                .setContentTitle(title)
                .setContentText(text)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setPublicVersion(publicVersion)
                .setContentIntent(openApp())
                .setAutoCancel(true)
                .build()
            try {
                manager.notify(alert.budgetId.hashCode(), notification)
            } catch (denied: SecurityException) {
                // Permission revoked between canPost() and notify(); nothing else to do for an optional alert.
                return
            }
        }
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        const val CHANNEL_ID = "budget_alerts"
    }
}
