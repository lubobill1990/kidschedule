package app.kidschedule.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.kidschedule.KidScheduleApp
import app.kidschedule.data.local.ActivityTypeEntity
import app.kidschedule.data.local.EventEntity
import app.kidschedule.data.local.FamilyMemberEntity
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsScreen(app: KidScheduleApp, familyId: String, babyId: String, onBack: () -> Unit) {
    val zone = remember { ZoneId.systemDefault() }
    var month by remember { mutableStateOf(YearMonth.now()) }
    var selectedDate by remember { mutableStateOf(LocalDate.now()) }
    var filterTypeId by remember { mutableStateOf<String?>(null) }

    val monthFrom = remember(month) { month.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli() }
    val monthTo = remember(month) {
        month.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1
    }
    val monthEvents by remember(babyId, monthFrom) {
        app.database.eventDao().observeRange(babyId, monthFrom, monthTo)
    }.collectAsState(initial = emptyList())
    val types by app.database.activityTypeDao().observeAll().collectAsState(initial = emptyList())
    val typeById = remember(types) { types.associateBy { it.id } }
    val members by app.database.familyMemberDao().observeAll(familyId).collectAsState(initial = emptyList())
    val memberById = remember(members) { members.associateBy { it.userId } }
    val myUserId = remember { app.authRepo.currentUserId() }

    val eventsByDay = remember(monthEvents) {
        monthEvents.groupBy { Instant.ofEpochMilli(it.startedAt).atZone(zone).toLocalDate() }
    }
    val dayEvents = eventsByDay[selectedDate].orEmpty()

    // 近 7 天(至所选日期)按记录人堆叠计数
    val weekDays = remember(selectedDate) { (6 downTo 0).map { selectedDate.minusDays(it.toLong()) } }
    val weekFrom = remember(weekDays) { weekDays.first().atStartOfDay(zone).toInstant().toEpochMilli() }
    val weekTo = remember(selectedDate) {
        selectedDate.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1
    }
    val weekEvents by remember(babyId, weekFrom, weekTo) {
        app.database.eventDao().observeRange(babyId, weekFrom, weekTo)
    }.collectAsState(initial = emptyList())
    val weekFiltered = remember(weekEvents, filterTypeId) {
        weekEvents.filter { filterTypeId == null || it.activityTypeId == filterTypeId }
    }
    // 本地新建行 createdBy 为空 → 归到当前用户
    fun userKey(e: EventEntity) = e.createdBy ?: myUserId ?: ""
    val countsByDay = remember(weekFiltered) {
        weekFiltered.groupBy { Instant.ofEpochMilli(it.startedAt).atZone(zone).toLocalDate() }
            .mapValues { (_, list) -> list.groupingBy { userKey(it) }.eachCount() }
    }
    val userOrder = remember(weekFiltered, members) {
        val fromEvents = weekFiltered.map { userKey(it) }.distinct()
        (members.map { it.userId }.filter { it in fromEvents } + fromEvents).distinct()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("统计") },
                navigationIcon = { TextButton(onClick = onBack) { Text("返回") } },
            )
        },
    ) { padding ->
        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
            item {
                MonthHeader(month, onPrev = { month = month.minusMonths(1) }, onNext = { month = month.plusMonths(1) })
                MonthGrid(month, selectedDate, eventsByDay.keys) { selectedDate = it }
                Spacer(Modifier.height(12.dp))
            }
            item {
                Text("近 7 天 · 按记录人", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = filterTypeId == null,
                        onClick = { filterTypeId = null },
                        label = { Text("全部") },
                    )
                    types.filter { it.babyId == null || it.babyId == babyId }.forEach { t ->
                        FilterChip(
                            selected = filterTypeId == t.id,
                            onClick = { filterTypeId = t.id },
                            label = { Text("${t.icon ?: ""}${t.name}") },
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                if (weekFiltered.isEmpty()) {
                    Text("该范围内没有记录", style = MaterialTheme.typography.bodyMedium)
                } else {
                    WeekBars(weekDays, countsByDay, userOrder)
                    Spacer(Modifier.height(8.dp))
                    UserLegend(userOrder, weekFiltered.groupingBy { userKey(it) }.eachCount(), memberById)
                }
                Spacer(Modifier.height(12.dp))
            }
            item {
                Text(
                    "${selectedDate.monthValue}月${selectedDate.dayOfMonth}日",
                    style = MaterialTheme.typography.titleSmall,
                )
                Spacer(Modifier.height(4.dp))
                if (dayEvents.isEmpty()) {
                    Text("当天没有记录", style = MaterialTheme.typography.bodyMedium)
                } else {
                    DaySummary(dayEvents, typeById)
                }
                Spacer(Modifier.height(8.dp))
            }
            items(dayEvents, key = { it.id }) { e ->
                DayEventRow(e, typeById[e.activityTypeId])
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

private val UserPalette = listOf(
    Color(0xFF5B8DEF), Color(0xFFF2A65A), Color(0xFF63C5DA),
    Color(0xFF8E7CC3), Color(0xFF7BC67E), Color(0xFFA9836F),
)

private fun userColor(userOrder: List<String>, user: String): Color =
    UserPalette[userOrder.indexOf(user).coerceAtLeast(0) % UserPalette.size]

@Composable
private fun WeekBars(
    days: List<LocalDate>,
    countsByDay: Map<LocalDate, Map<String, Int>>,
    userOrder: List<String>,
) {
    val maxTotal = days.maxOf { countsByDay[it]?.values?.sum() ?: 0 }.coerceAtLeast(1)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
        days.forEach { day ->
            val counts = countsByDay[day].orEmpty()
            val total = counts.values.sum()
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                if (total > 0) Text("$total", style = MaterialTheme.typography.labelSmall)
                Column(Modifier.width(20.dp).clip(RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp))) {
                    // 倒序绘制,userOrder 首位落在底部
                    userOrder.reversed().forEach { u ->
                        val c = counts[u] ?: 0
                        if (c > 0) {
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .height((110f * c / maxTotal).dp)
                                    .background(userColor(userOrder, u)),
                            )
                        }
                    }
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    "${day.monthValue}/${day.dayOfMonth}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
    }
}

@Composable
private fun UserLegend(
    userOrder: List<String>,
    totals: Map<String, Int>,
    memberById: Map<String, FamilyMemberEntity>,
) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        userOrder.forEach { u ->
            val m = memberById[u]
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(userColor(userOrder, u)))
                Spacer(Modifier.width(4.dp))
                Text(
                    "${m?.avatarEmoji ?: "👤"}${m?.displayName ?: ""} ×${totals[u] ?: 0}",
                    style = MaterialTheme.typography.bodySmall,
                )
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
