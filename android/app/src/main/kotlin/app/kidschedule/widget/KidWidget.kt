package app.kidschedule.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.provideContent
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.action.actionStartActivity
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import app.kidschedule.KidScheduleApp
import app.kidschedule.MainActivity
import kotlinx.coroutines.flow.map

class KidWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = KidWidget()
}

private data class TypeUi(val id: String, val name: String, val icon: String, val ongoing: Boolean)

class KidWidget : GlanceAppWidget() {

    companion object {
        val KEY_UNDO_EVENT_ID = stringPreferencesKey("undo_event_id")
        val KEY_UNDO_LABEL = stringPreferencesKey("undo_label")
        val KEY_UNDO_EXPIRES = longPreferencesKey("undo_expires")
    }

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val app = context.applicationContext as KidScheduleApp
        // 数据读取必须在 composition 内响应式进行:Glance 会话存活期间 updateAll
        // 只触发重组,不会重新执行 provideContent 之前的代码。
        provideContent {
            val baby by remember { app.database.babyDao().observeAll().map { it.firstOrNull() } }
                .collectAsState(initial = null)
            val typeEntities by remember { app.database.activityTypeDao().observeAll() }
                .collectAsState(initial = emptyList())
            val ongoing by remember { app.database.eventDao().observeOngoing() }
                .collectAsState(initial = emptyList())
            val types = typeEntities
                .filter { it.babyId == null || it.babyId == baby?.id }
                .map { t ->
                    val isOngoing = t.kind == "duration" &&
                        ongoing.any { it.babyId == baby?.id && it.activityTypeId == t.id }
                    TypeUi(t.id, t.name, t.icon ?: "", isOngoing)
                }
            GlanceTheme {
                Content(baby?.name, types)
            }
        }
    }

    @Composable
    private fun Content(babyName: String?, types: List<TypeUi>) {
        val prefs = currentState<androidx.datastore.preferences.core.Preferences>()
        val undoEventId = prefs[KEY_UNDO_EVENT_ID]
        val undoLabel = prefs[KEY_UNDO_LABEL]
        val undoExpires = prefs[KEY_UNDO_EXPIRES] ?: 0L
        val undoActive = undoEventId != null && undoExpires > System.currentTimeMillis()

        Column(
            modifier = GlanceModifier.fillMaxSize()
                .background(GlanceTheme.colors.widgetBackground)
                .padding(12.dp),
        ) {
            if (babyName == null) {
                Text(
                    "打开 KidSchedule 完成设置",
                    modifier = GlanceModifier.fillMaxWidth()
                        .clickable(actionStartActivity<MainActivity>()),
                )
                return@Column
            }
            Text(babyName, style = TextStyle(fontWeight = FontWeight.Bold))
            Spacer(GlanceModifier.height(8.dp))
            if (undoActive) {
                Row(
                    modifier = GlanceModifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("已记录「${undoLabel}」", modifier = GlanceModifier.defaultWeight())
                    androidx.glance.Button(
                        text = "撤销",
                        onClick = actionRunCallback<UndoAction>(),
                    )
                }
            } else {
                types.chunked(2).forEach { rowTypes ->
                    Row(modifier = GlanceModifier.fillMaxWidth()) {
                        rowTypes.forEachIndexed { i, t ->
                            if (i > 0) Spacer(GlanceModifier.width(8.dp))
                            androidx.glance.Button(
                                text = if (t.ongoing) "结束${t.name}" else "${t.icon}${t.name}",
                                onClick = actionRunCallback<RecordAction>(
                                    actionParametersOf(RecordAction.TYPE_ID to t.id)
                                ),
                                modifier = GlanceModifier.defaultWeight(),
                            )
                        }
                        if (rowTypes.size == 1) {
                            Spacer(GlanceModifier.width(8.dp))
                            Spacer(GlanceModifier.defaultWeight())
                        }
                    }
                    Spacer(GlanceModifier.height(6.dp))
                }
            }
        }
    }
}
