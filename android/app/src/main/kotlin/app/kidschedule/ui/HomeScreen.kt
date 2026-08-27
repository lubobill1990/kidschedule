package app.kidschedule.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.kidschedule.KidScheduleApp
import app.kidschedule.core.Protocol
import app.kidschedule.data.local.ActivityTypeEntity
import app.kidschedule.data.local.EventEntity
import app.kidschedule.data.local.FamilyMemberEntity
import app.kidschedule.data.sync.syncWithRetry
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private data class UndoUi(val eventId: String, val label: String, val expiresAt: Long)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(app: KidScheduleApp, familyId: String) {
    val scope = rememberCoroutineScope()
    val babies by app.database.babyDao().observeAll().collectAsState(initial = emptyList())
    val types by app.database.activityTypeDao().observeAll().collectAsState(initial = emptyList())
    var selectedBabyId by rememberSaveable { mutableStateOf<String?>(null) }
    // 选中的宝宝可能已被删除/同步移除,失效时回退到第一个
    val babyId = babies.firstOrNull { it.id == selectedBabyId }?.id ?: babies.firstOrNull()?.id
    var undo by remember { mutableStateOf<UndoUi?>(null) }
    var showStats by rememberSaveable { mutableStateOf(false) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    // 记录按钮只展示通用 + 当前宝宝专属的类型;timeline 名称映射仍用全量
    val visibleTypes = remember(types, babyId) {
        types.filter { it.babyId == null || it.babyId == babyId }
    }
    var detailEvent by remember { mutableStateOf<EventEntity?>(null) }
    var showBackfill by rememberSaveable { mutableStateOf(false) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }

    // 秒级心跳:驱动撤销倒计时与 ongoing 计时
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            undo?.let { if (now >= it.expiresAt) undo = null }
            delay(1000)
        }
    }

    // 挂在 appScope:离开页面也不中断同步
    fun requestSync(delayMillis: Long = 0) {
        app.appScope.launch {
            if (delayMillis > 0) delay(delayMillis)
            runCatching { app.recordRepo.autoEndOverdue() }
            runCatching { app.attachmentRepo.uploadPending() }
            app.syncEngine.syncWithRetry()
            runCatching { app.reminderScheduler.rescheduleAll() }
        }
    }

    // 启动同步 + Realtime 变化信号(协议 §10 §12)
    LaunchedEffect(familyId) {
        requestSync()
        runCatching {
            app.realtimeSignal.subscribe(familyId).collect { requestSync() }
        }
    }

    fun onQuickRecorded(eventId: String, typeName: String) {
        undo = UndoUi(eventId, typeName, System.currentTimeMillis() + Protocol.UNDO_WINDOW_SEC * 1000L)
        requestSync(delayMillis = Protocol.UNDO_WINDOW_SEC * 1000L + 500)
    }

    if (showStats && babyId != null) {
        StatsScreen(app, familyId, babyId, onBack = { showStats = false })
        return
    }
    if (showSettings) {
        SettingsScreen(app, familyId, onBack = { showSettings = false; requestSync() })
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("KidSchedule") },
                actions = {
                    TextButton(onClick = { showStats = true }) { Text("统计") }
                    TextButton(onClick = { requestSync() }) { Text("同步") }
                    TextButton(onClick = { showSettings = true }) { Text("设置") }
                },
            )
        },
        bottomBar = {
            undo?.let { u ->
                val remain = ((u.expiresAt - now) / 1000).coerceAtLeast(0)
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("已记录「${u.label}」", modifier = Modifier.weight(1f))
                    Button(onClick = {
                        scope.launch {
                            app.recordRepo.undo(u.eventId)
                            undo = null
                        }
                    }) { Text("撤销 (${remain}s)") }
                }
            }
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
            if (babies.size > 1) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    babies.forEach { b ->
                        FilterChip(
                            selected = b.id == babyId,
                            onClick = { selectedBabyId = b.id },
                            label = { Text(b.name) },
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
            if (babyId == null) {
                Text("同步中…", style = MaterialTheme.typography.bodyMedium)
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    items(visibleTypes, key = { it.id }) { type ->
                        TypeCard(app, familyId, babyId, type, now, ::onQuickRecorded, onEnded = { requestSync() })
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("最近记录", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    TextButton(onClick = { showBackfill = true }) { Text("补录") }
                }
                Timeline(app, familyId, babyId, types, now, onEventClick = { detailEvent = it }, modifier = Modifier.weight(1f))
            }
        }
    }

    if (showBackfill && babyId != null) {
        BackfillDialog(
            app, familyId, babyId, visibleTypes,
            onDismiss = { showBackfill = false },
            onSaved = { showBackfill = false; requestSync() },
        )
    }

    detailEvent?.let { e ->
        val typeName = types.firstOrNull { it.id == e.activityTypeId }
            ?.let { "${it.icon ?: ""}${it.name}" } ?: "记录"
        EventDetailDialog(app, familyId, e, typeName, onDismiss = { detailEvent = null })
    }

}

@Composable
private fun TypeCard(
    app: KidScheduleApp,
    familyId: String,
    babyId: String,
    type: ActivityTypeEntity,
    now: Long,
    onQuickRecorded: (String, String) -> Unit,
    onEnded: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val last by app.database.eventDao().observeLast(babyId, type.id).collectAsState(initial = null)
    val ongoing = last?.takeIf { it.status == "ongoing" }

    Card(
        onClick = {
            scope.launch {
                when {
                    type.kind == "instant" -> {
                        val id = app.recordRepo.quickRecordInstant(familyId, babyId, type.id)
                        onQuickRecorded(id, type.name)
                    }
                    ongoing != null -> {
                        app.recordRepo.endDuration(ongoing.id)
                        onEnded()
                    }
                    else -> {
                        app.recordRepo.quickStartDuration(familyId, babyId, type.id)
                            ?.let { onQuickRecorded(it, "开始${type.name}") }
                    }
                }
            }
        },
        colors = if (ongoing != null) {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        } else {
            CardDefaults.cardColors()
        },
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(type.icon ?: "", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.width(8.dp))
                Text(type.name, style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.height(8.dp))
            when {
                ongoing != null -> Text(
                    "进行中 ${TimeFmt.elapsed(ongoing.startedAt, now)} · 点击结束",
                    style = MaterialTheme.typography.bodySmall,
                )
                last != null -> Text(
                    "上次 ${TimeFmt.relative(last!!.endedAt ?: last!!.startedAt, now)}",
                    style = MaterialTheme.typography.bodySmall,
                )
                else -> Text("还没有记录", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Timeline(
    app: KidScheduleApp,
    familyId: String,
    babyId: String,
    types: List<ActivityTypeEntity>,
    now: Long,
    onEventClick: (EventEntity) -> Unit,
    modifier: Modifier = Modifier,
) {
    val events by app.database.eventDao().observeTimeline(babyId, 50).collectAsState(initial = emptyList())
    val members by app.database.familyMemberDao().observeAll(familyId).collectAsState(initial = emptyList())
    val typeById = remember(types) { types.associateBy { it.id } }
    val memberById = remember(members) { members.associateBy { it.userId } }
    val myUserId = remember { app.authRepo.currentUserId() }
    val listState = rememberLazyListState()
    // 有 key 的 LazyColumn 会锚定旧首项;贴近顶部时新记录到来应自动滚回顶部
    LaunchedEffect(events.firstOrNull()?.id) {
        if (listState.firstVisibleItemIndex <= 1) listState.animateScrollToItem(0)
    }
    val groups = remember(events) {
        events.groupBy { TimeFmt.startOfDay(it.startedAt) }.entries.sortedByDescending { it.key }
    }
    LazyColumn(state = listState, modifier = modifier) {
        groups.forEach { (day, dayEvents) ->
            stickyHeader(key = "day-$day") {
                Text(
                    TimeFmt.dayLabel(day),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(vertical = 4.dp),
                )
            }
            items(dayEvents, key = { it.id }) { e ->
                // 本地新建行 createdBy 为空 → 显示为当前用户(服务端插入时才填)
                val creator = memberById[e.createdBy ?: myUserId]
                TimelineRow(e, typeById[e.activityTypeId], creator, now, onClick = { onEventClick(e) })
            }
        }
    }
}

@Composable
private fun TimelineRow(
    e: EventEntity,
    type: ActivityTypeEntity?,
    creator: FamilyMemberEntity?,
    now: Long,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(type?.icon ?: "·")
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Row {
                Text(type?.name ?: "未知", style = MaterialTheme.typography.bodyMedium)
                if (e.autoEnded) {
                    Spacer(Modifier.width(6.dp))
                    Text("自动结束", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary)
                }
            }
            val timeText = when {
                e.status == "ongoing" -> "${TimeFmt.clock(e.startedAt)} 起,进行中 ${TimeFmt.elapsed(e.startedAt, now)}"
                e.endedAt != null && e.endedAt != e.startedAt ->
                    "${TimeFmt.clock(e.startedAt)} - ${TimeFmt.clock(e.endedAt)}"
                else -> TimeFmt.clock(e.startedAt)
            }
            Text(timeText, style = MaterialTheme.typography.bodySmall)
        }
        e.note?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        creator?.let {
            Spacer(Modifier.width(8.dp))
            Text(
                it.avatarEmoji ?: it.displayName?.take(1) ?: "👤",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}
