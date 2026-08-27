package app.kidschedule.widget

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.appwidget.updateAll
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import app.kidschedule.KidScheduleApp
import app.kidschedule.core.Protocol
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

class RecordAction : ActionCallback {
    companion object {
        val TYPE_ID = ActionParameters.Key<String>("typeId")
        val BABY_ID = ActionParameters.Key<String>("babyId")
    }

    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val app = context.applicationContext as KidScheduleApp
        val typeId = parameters[TYPE_ID] ?: return
        val familyId = app.familyRepo.currentFamilyId ?: return
        val baby = parameters[BABY_ID]?.let { app.database.babyDao().getById(it) }
            ?: app.database.babyDao().observeAll().first().firstOrNull() ?: return
        val type = app.database.activityTypeDao().getById(typeId) ?: return

        val undo: Pair<String, String>? = when {
            type.kind == "instant" ->
                app.recordRepo.quickRecordInstant(familyId, baby.id, typeId) to type.name
            else -> {
                val ongoing = app.database.eventDao().ongoingFor(baby.id, typeId)
                if (ongoing != null) {
                    app.recordRepo.endDuration(ongoing.id)
                    enqueueWidgetSync(context, delayMillis = 0)
                    null
                } else {
                    app.recordRepo.quickStartDuration(familyId, baby.id, typeId)
                        ?.let { it to "开始${type.name}" }
                }
            }
        }

        if (undo != null) {
            updateAppWidgetState(context, glanceId) { prefs ->
                prefs[KidWidget.KEY_UNDO_EVENT_ID] = undo.first
                prefs[KidWidget.KEY_UNDO_LABEL] = undo.second
                prefs[KidWidget.KEY_UNDO_EXPIRES] =
                    System.currentTimeMillis() + Protocol.UNDO_WINDOW_SEC * 1000L
            }
            enqueueWidgetSync(context, delayMillis = Protocol.UNDO_WINDOW_SEC * 1000L + 500)
        }
        KidWidget().updateAll(context)
        TypeWidget().updateAll(context)
    }
}

class UndoAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val app = context.applicationContext as KidScheduleApp
        var eventId: String? = null
        updateAppWidgetState(context, glanceId) { prefs ->
            eventId = prefs[KidWidget.KEY_UNDO_EVENT_ID]
            prefs.remove(KidWidget.KEY_UNDO_EVENT_ID)
            prefs.remove(KidWidget.KEY_UNDO_LABEL)
            prefs.remove(KidWidget.KEY_UNDO_EXPIRES)
        }
        eventId?.let { app.recordRepo.undo(it) }
        KidWidget().updateAll(context)
        TypeWidget().updateAll(context)
    }
}

internal fun enqueueWidgetSync(context: Context, delayMillis: Long) {
    val request = OneTimeWorkRequestBuilder<WidgetSyncWorker>()
        .setInitialDelay(delayMillis, TimeUnit.MILLISECONDS)
        .build()
    WorkManager.getInstance(context).enqueue(request)
}
