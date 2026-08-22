package app.kidschedule.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.kidschedule.KidScheduleApp
import app.kidschedule.data.local.ActivityTypeEntity
import app.kidschedule.data.local.EventEntity
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsScreen(app: KidScheduleApp, babyId: String, onBack: () -> Unit) {
    val zone = remember { ZoneId.systemDefault() }
    var month by remember { mutableStateOf(YearMonth.now()) }
    var selectedDate by remember { mutableStateOf(LocalDate.now()) }

    val monthFrom = remember(month) { month.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli() }
    val monthTo = remember(month) {
        month.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1
    }
    val monthEvents by remember(babyId, monthFrom) {
        app.database.eventDao().observeRange(babyId, monthFrom, monthTo)
    }.collectAsState(initial = emptyList())
    val types by app.database.activityTypeDao().observeAll().collectAsState(initial = emptyList())
    val typeById = remember(types) { types.associateBy { it.id } }

    val eventsByDay = remember(monthEvents) {
        monthEvents.groupBy { Instant.ofEpochMilli(it.startedAt).atZone(zone).toLocalDate() }
    }
    val dayEvents = eventsByDay[selectedDate].orEmpty()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("统计") },
                navigationIcon = { TextButton(onClick = onBack) { Text("返回") } },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
            MonthHeader(month, onPrev = { month = month.minusMonths(1) }, onNext = { month = month.plusMonths(1) })
            MonthGrid(month, selectedDate, eventsByDay.keys) { selectedDate = it }
            Spacer(Modifier.height(12.dp))
            Text(
                "${selectedDate.monthValue}月${selectedDate.dayOfMonth}日",
                style = MaterialTheme.typography.titleSmall,
            )
            Spacer(Modifier.height(4.dp))
            if (dayEvents.isEmpty()) {
                Text("当天没有记录", style = MaterialTheme.typography.bodyMedium)
            } else {
                DaySummary(dayEvents, typeById)
                Spacer(Modifier.height(8.dp))
                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(dayEvents, key = { it.id }) { e ->
                        DayEventRow(e, typeById[e.activityTypeId])
                    }
                }
            }
        }
    }
}

@Composable
private fun MonthHeader(month: YearMonth, onPrev: () -> Unit, onNext: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        TextButton(onClick = onPrev) { Text("◀") }
        Text("${month.year}年${month.monthValue}月", style = MaterialTheme.typography.titleMedium)
        TextButton(
            onClick = onNext,
            enabled = month < YearMonth.now(),
        ) { Text("▶") }
    }
}

@Composable
private fun MonthGrid(
    month: YearMonth,
    selected: LocalDate,
    daysWithEvents: Set<LocalDate>,
    onSelect: (LocalDate) -> Unit,
) {
    Row(Modifier.fillMaxWidth()) {
        listOf("一", "二", "三", "四", "五", "六", "日").forEach {
            Text(
                it,
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
    Spacer(Modifier.height(4.dp))
    val firstDay = month.atDay(1)
    val leadingBlanks = firstDay.dayOfWeek.value - 1 // 周一为第一列
    val cells = leadingBlanks + month.lengthOfMonth()
    val today = LocalDate.now()
    for (week in 0 until (cells + 6) / 7) {
        Row(Modifier.fillMaxWidth()) {
            for (col in 0 until 7) {
                val idx = week * 7 + col
                val day = idx - leadingBlanks + 1
                Box(modifier = Modifier.weight(1f).aspectRatio(1.2f), contentAlignment = Alignment.Center) {
                    if (day in 1..month.lengthOfMonth()) {
                        val date = month.atDay(day)
                        val isSelected = date == selected
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(
                                    if (isSelected) MaterialTheme.colorScheme.primaryContainer
                                    else MaterialTheme.colorScheme.surface
                                )
                                .clickable(enabled = date <= today) { onSelect(date) }
                                .padding(6.dp),
                        ) {
                            Text(
                                "$day",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (date > today) MaterialTheme.colorScheme.outline
                                else MaterialTheme.colorScheme.onSurface,
                            )
                            Box(
                                modifier = Modifier
                                    .size(4.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (date in daysWithEvents) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.surface.copy(alpha = 0f)
                                    ),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DaySummary(events: List<EventEntity>, typeById: Map<String, ActivityTypeEntity>) {
    val byType = events.groupBy { it.activityTypeId }
    Column {
        byType.forEach { (typeId, list) ->
            val type = typeById[typeId]
            val durationSec = list
                .filter { it.status == "done" && it.endedAt != null }
                .sumOf { ((it.endedAt!! - it.startedAt) / 1000).coerceAtLeast(0) }
            val text = buildString {
                append("${type?.icon ?: ""}${type?.name ?: "未知"} ×${list.size}")
                if (type?.kind == "duration" && durationSec > 0) {
                    append(" 共${TimeFmt.durationText(durationSec)}")
                }
            }
            Text(text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun DayEventRow(e: EventEntity, type: ActivityTypeEntity?) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(type?.icon ?: "·")
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Row {
                Text(type?.name ?: "未知", style = MaterialTheme.typography.bodyMedium)
                if (e.autoEnded) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "自动结束", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
            }
            val timeText = when {
                e.status == "ongoing" -> "${TimeFmt.clock(e.startedAt)} 起,进行中"
                e.endedAt != null && e.endedAt != e.startedAt ->
                    "${TimeFmt.clock(e.startedAt)} - ${TimeFmt.clock(e.endedAt!!)}" +
                        " (${TimeFmt.durationText((e.endedAt!! - e.startedAt) / 1000)})"
                else -> TimeFmt.clock(e.startedAt)
            }
            Text(timeText, style = MaterialTheme.typography.bodySmall)
        }
        e.note?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}
