package app.kidschedule.reminder

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.kidschedule.KidScheduleApp
import app.kidschedule.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class ReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val babyId = intent.getStringExtra(ReminderScheduler.EXTRA_BABY_ID) ?: return
        val typeId = intent.getStringExtra(ReminderScheduler.EXTRA_TYPE_ID) ?: return
        val title = intent.getStringExtra(ReminderScheduler.EXTRA_TITLE) ?: return
        val app = context.applicationContext as KidScheduleApp
        val result = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // 触发前复算:数据变化后闹钟可能已过时,过时则不打扰
                val fireAt = runCatching { app.reminderScheduler.computeNextFireAt(babyId, typeId) }
                    .onFailure { android.util.Log.w("Reminder", "recompute failed", it) }
                    .getOrNull()
                if (fireAt != null && fireAt <= System.currentTimeMillis()) {
                    notify(context, babyId, typeId, title)
                }
            } finally {
                result.finish()
            }
        }
    }

    private fun notify(context: Context, babyId: String, typeId: String, title: String) {
        ReminderScheduler.ensureChannel(context)
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = Notification.Builder(context, ReminderScheduler.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(title)
            .setContentText("已超过平时的间隔,点开看看")
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        val nm = context.getSystemService(NotificationManager::class.java)
        runCatching { nm.notify((babyId + typeId).hashCode(), n) }
    }
}
