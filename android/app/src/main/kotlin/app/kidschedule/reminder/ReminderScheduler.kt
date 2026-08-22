package app.kidschedule.reminder

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import app.kidschedule.core.Protocol
import app.kidschedule.core.reminder.ActivityKind
import app.kidschedule.core.reminder.ReminderCalculator
import app.kidschedule.core.reminder.ReminderEvent
import app.kidschedule.core.reminder.ReminderMode
import app.kidschedule.data.local.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

// MVP 提醒策略(计划 §M4):不依赖后台任务,在前台/同步完成后一次性重排
// 未来 24h 内的本地提醒;用 setWindow(±10min) 免 SCHEDULE_EXACT_ALARM 权限。
class ReminderScheduler(private val context: Context, private val db: AppDatabase) {

    companion object {
        const val CHANNEL_ID = "reminders"
        const val ACTION = "app.kidschedule.REMINDER"
        const val EXTRA_BABY_ID = "babyId"
        const val EXTRA_TYPE_ID = "typeId"
        const val EXTRA_TITLE = "title"
        private const val WINDOW_MS = 10 * 60 * 1000L
        private const val HORIZON_MS = 24 * 3600 * 1000L

        fun ensureChannel(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "日常提醒", NotificationManager.IMPORTANCE_DEFAULT)
                    .apply { description = "根据宝宝的日常规律提醒喂奶等行为" }
            )
        }
    }

    suspend fun rescheduleAll() = withContext(Dispatchers.IO) {
        ensureChannel(context)
        val alarm = context.getSystemService(AlarmManager::class.java)
        val now = System.currentTimeMillis()
        val babies = db.babyDao().observeAll().first()
        val types = db.activityTypeDao().observeAll().first()
        for (baby in babies) {
            for (type in types) {
                val pi = pendingIntent(baby.id, type.id, "${baby.name}:该${type.name}了")
                val fireAt = computeNextFireAt(baby.id, type.id)
                if (fireAt != null && fireAt > now && fireAt <= now + HORIZON_MS) {
                    alarm.setWindow(AlarmManager.RTC_WAKEUP, fireAt, WINDOW_MS, pi)
                } else {
                    alarm.cancel(pi)
                }
            }
        }
    }

    /** 返回 null 表示不需提醒。供 Receiver 触发前复算校验。 */
    suspend fun computeNextFireAt(babyId: String, typeId: String): Long? {
        val type = db.activityTypeDao().getById(typeId) ?: return null
        if (type.deletedAt != null) return null
        val mode = when (type.reminderMode) {
            "auto" -> ReminderMode.AUTO
            "fixed" -> ReminderMode.FIXED
            else -> return null
        }
        val kind = if (type.kind == "duration") ActivityKind.DURATION else ActivityKind.INSTANT
        val events = db.eventDao().recentFor(babyId, typeId, Protocol.REMINDER_SAMPLE_N)
            .map { ReminderEvent(it.startedAt, it.endedAt, ongoing = it.status == "ongoing") }
        return ReminderCalculator
            .compute(kind, mode, type.reminderFixedIntervalSec, events)
            .nextFireAtMillis
    }

    private fun pendingIntent(babyId: String, typeId: String, title: String): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java)
            .setAction(ACTION)
            .setData(android.net.Uri.parse("kidschedule://reminder/$babyId/$typeId"))
            .putExtra(EXTRA_BABY_ID, babyId)
            .putExtra(EXTRA_TYPE_ID, typeId)
            .putExtra(EXTRA_TITLE, title)
        return PendingIntent.getBroadcast(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
