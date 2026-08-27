package app.kidschedule.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.provideContent
import androidx.glance.action.actionParametersOf
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import app.kidschedule.KidScheduleApp
import app.kidschedule.MainActivity
import app.kidschedule.data.local.ActivityTypeEntity
import app.kidschedule.data.local.BabyEntity
import app.kidschedule.data.local.EventEntity
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

class TypeWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TypeWidget()
}

// 单行为 widget:配置活动选定「宝宝 + 行为」,一键开始/结束,展示上次时间与今日次数
class TypeWidget : GlanceAppWidget() {

    companion object {
        val KEY_TYPE_ID = stringPreferencesKey("type_id")
        val KEY_BABY_ID = stringPreferencesKey("baby_id")
    }

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val app = context.applicationContext as KidScheduleApp
        provideContent {
            val prefs = currentState<Preferences>()
            val typeId = prefs[KEY_TYPE_ID]
            val configBabyId = prefs[KEY_BABY_ID]

            val type by remember(typeId) {
                typeId?.let { app.database.activityTypeDao().observeById(it) }
                    ?: flowOf<ActivityTypeEntity?>(null)
            }.collectAsState(initial = null)
            val baby by remember(configBabyId) {
                configBabyId?.let { app.database.babyDao().observeById(it) }
                    ?: app.database.babyDao().observeAll().map { it.firstOrNull() }
            }.collectAsState(initial = null)
            val lastEvent by remember(baby?.id, typeId) {
                val bid = baby?.id
                if (bid != null && typeId != null) app.database.eventDao().observeLast(bid, typeId)
                else flowOf<EventEntity?>(null)
            }.collectAsState(initial = null)
            val todayStart = remember { startOfToday() }
            val todayCount by remember(baby?.id, typeId, todayStart) {
                val bid = baby?.id
                if (bid != null && typeId != null) {
                    app.database.eventDao().observeCountSince(bid, typeId, todayStart)
                } else flowOf(0)
            }.collectAsState(initial = 0)

            GlanceTheme {
                Content(type, baby, lastEvent, todayCount)
            }
        }
    }

    @Composable
    private fun Content(
        type: ActivityTypeEntity?,
        baby: BabyEntity?,
        lastEvent: EventEntity?,
        todayCount: Int,
    ) {
        val prefs = currentState<Preferences>()
        val undoEventId = prefs[KidWidget.KEY_UNDO_EVENT_ID]
        val undoExpires = prefs[KidWidget.KEY_UNDO_EXPIRES] ?: 0L
        val undoActive = undoEventId != null && undoExpires > System.currentTimeMillis()

        Column(
            modifier = GlanceModifier.fillMaxSize()
                .background(GlanceTheme.colors.widgetBackground)
                .padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (type == null || baby == null) {
                Text(
                    "打开 KidSchedule 完成配置",
                    modifier = GlanceModifier.fillMaxWidth()
                        .clickable(actionStartActivity<MainActivity>()),
                )
                return@Column
            }
            Text(
                "${type.icon ?: ""}${type.name}",
                style = TextStyle(fontWeight = FontWeight.Bold, fontSize = 16.sp),
            )
            Text(
                baby.name,
                style = TextStyle(fontSize = 11.sp, color = GlanceTheme.colors.onSurfaceVariant),
            )
            Spacer(GlanceModifier.defaultWeight())
            if (undoActive) {
                androidx.glance.Button(
                    text = "撤销",
                    onClick = actionRunCallback<UndoAction>(),
                    modifier = GlanceModifier.fillMaxWidth(),
                )
            } else {
                val ongoing = type.kind == "duration" && lastEvent?.status == "ongoing"
                androidx.glance.Button(
                    text = when {
                        ongoing -> "结束"
                        type.kind == "duration" -> "开始"
                        else -> "记录"
                    },
                    onClick = actionRunCallback<RecordAction>(
                        actionParametersOf(
                            RecordAction.TYPE_ID to type.id,
                            RecordAction.BABY_ID to baby.id,
                        )
                    ),
                    modifier = GlanceModifier.fillMaxWidth(),
                )
            }
            Spacer(GlanceModifier.height(6.dp))
            Text(
                summaryLine(type, lastEvent, todayCount),
                style = TextStyle(fontSize = 11.sp, color = GlanceTheme.colors.onSurfaceVariant),
            )
        }
    }

    private fun summaryLine(type: ActivityTypeEntity, last: EventEntity?, todayCount: Int): String {
        val lastPart = when {
            last == null -> "还没有记录"
            type.kind == "duration" && last.status == "ongoing" ->
                "进行中 · ${formatTime(last.startedAt)} 开始"
            else -> "上次 ${formatTime(last.startedAt)}"
        }
        return "$lastPart · 今天 ${todayCount} 次"
    }
}

private fun startOfToday(): Long = Calendar.getInstance().apply {
    set(Calendar.HOUR_OF_DAY, 0)
    set(Calendar.MINUTE, 0)
    set(Calendar.SECOND, 0)
    set(Calendar.MILLISECOND, 0)
}.timeInMillis

private fun formatTime(millis: Long): String {
    val pattern = if (millis >= startOfToday()) "HH:mm" else "M/d HH:mm"
    return SimpleDateFormat(pattern, Locale.getDefault()).format(Date(millis))
}
