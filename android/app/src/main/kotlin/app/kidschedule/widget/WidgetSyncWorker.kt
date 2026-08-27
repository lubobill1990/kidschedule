package app.kidschedule.widget

import android.content.Context
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.appwidget.updateAll
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.kidschedule.KidScheduleApp
import app.kidschedule.data.sync.syncWithRetry

// 撤销窗口到期后释放 outbox 并同步;顺带清理各 widget 实例过期的撤销态。
class WidgetSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as KidScheduleApp
        runCatching { app.recordRepo.autoEndOverdue() }
        runCatching { app.attachmentRepo.uploadPending() }
        val synced = app.syncEngine.syncWithRetry()
        runCatching { app.reminderScheduler.rescheduleAll() }

        val now = System.currentTimeMillis()
        val manager = GlanceAppWidgetManager(applicationContext)
        val allIds = manager.getGlanceIds(KidWidget::class.java) +
            manager.getGlanceIds(TypeWidget::class.java)
        allIds.forEach { gid ->
            updateAppWidgetState(applicationContext, gid) { prefs ->
                val expires = prefs[KidWidget.KEY_UNDO_EXPIRES] ?: return@updateAppWidgetState
                if (expires <= now) {
                    prefs.remove(KidWidget.KEY_UNDO_EVENT_ID)
                    prefs.remove(KidWidget.KEY_UNDO_LABEL)
                    prefs.remove(KidWidget.KEY_UNDO_EXPIRES)
                }
            }
        }
        KidWidget().updateAll(applicationContext)
        TypeWidget().updateAll(applicationContext)
        return if (synced || runAttemptCount >= 3) Result.success() else Result.retry()
    }
}
