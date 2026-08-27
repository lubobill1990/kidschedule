package app.kidschedule.ui

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
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import app.kidschedule.KidScheduleApp
import app.kidschedule.data.local.ActivityTypeEntity
import app.kidschedule.data.local.BabyEntity
import app.kidschedule.data.local.FamilyMemberEntity
import app.kidschedule.data.sync.syncWithRetry
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(app: KidScheduleApp, familyId: String, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val babies by app.database.babyDao().observeAll().collectAsState(initial = emptyList())
    val types by app.database.activityTypeDao().observeAll().collectAsState(initial = emptyList())
    var editBaby by remember { mutableStateOf<BabyEntity?>(null) }
    var addingBaby by remember { mutableStateOf(false) }
    var editType by remember { mutableStateOf<ActivityTypeEntity?>(null) }
    var addingType by remember { mutableStateOf(false) }
    var inviteCode by remember { mutableStateOf<String?>(null) }
    var editingProfile by remember { mutableStateOf(false) }
    val myUserId = remember { app.authRepo.currentUserId() }
    val members by app.database.familyMemberDao().observeAll(familyId).collectAsState(initial = emptyList())
    val me = members.firstOrNull { it.userId == myUserId }

    fun syncAndReschedule() {
        scope.launch {
            app.syncEngine.syncWithRetry()
            runCatching { app.reminderScheduler.rescheduleAll() }
        }
    }

    Scaffold(
        topBar = {
            androidx.compose.material3.TopAppBar(
                title = { Text("设置") },
                navigationIcon = { TextButton(onClick = onBack) { Text("返回") } },
            )
        },
    ) { padding ->
        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
            item {
                SectionTitle("宝宝")
            }
            items(babies, key = { it.id }) { b ->
                SettingsRow(
                    leading = b.name,
                    trailing = b.birthday ?: "",
                    onClick = { editBaby = b },
                )
            }
            item {
                TextButton(onClick = { addingBaby = true }) { Text("添加宝宝") }
                Spacer(Modifier.height(8.dp))
                SectionTitle("行为类型")
            }
            items(types, key = { it.id }) { t ->
                SettingsRow(
                    leading = "${t.icon ?: ""} ${t.name}",
                    trailing = buildString {
                        t.babyId?.let { bid ->
                            babies.firstOrNull { it.id == bid }?.let { append("${it.name} · ") }
                        }
                        append(if (t.kind == "duration") "持续" else "瞬时")
                        when (t.reminderMode) {
                            "auto" -> append(" · 智能提醒")
                            "fixed" -> append(" · 每${(t.reminderFixedIntervalSec ?: 0) / 60}分钟提醒")
                        }
                    },
                    onClick = { editType = t },
                )
            }
            item {
                TextButton(onClick = { addingType = true }) { Text("添加行为") }
                Spacer(Modifier.height(8.dp))
                SectionTitle("我的资料")
                SettingsRow(
                    leading = "${me?.avatarEmoji ?: "👤"} ${me?.displayName ?: "未设置昵称"}",
                    trailing = "编辑",
                    onClick = { editingProfile = true },
                )
                Spacer(Modifier.height(8.dp))
                SectionTitle("家庭")
                TextButton(onClick = {
                    scope.launch {
                        runCatching { app.familyRepo.createInvite(familyId) }
                            .onSuccess { inviteCode = it }
                    }
                }) { Text("生成邀请码") }
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    if (addingBaby) {
        BabyDialog(
            title = "添加宝宝",
            onDismiss = { addingBaby = false },
            onSave = { name, birthday ->
                scope.launch {
                    app.catalogRepo.addBaby(familyId, name, birthday)
                    addingBaby = false
                    syncAndReschedule()
                }
            },
        )
    }
    editBaby?.let { baby ->
        BabyDialog(
            title = "编辑宝宝",
            initialName = baby.name,
            initialBirthday = baby.birthday ?: "",
            onDismiss = { editBaby = null },
            onSave = { name, birthday ->
                scope.launch {
                    app.catalogRepo.updateBaby(baby.copy(name = name, birthday = birthday))
                    editBaby = null
                    syncAndReschedule()
                }
            },
        )
    }
    if (addingType) {
        TypeDialog(
            title = "添加行为",
            babies = babies,
            onDismiss = { addingType = false },
            onSave = { form ->
                scope.launch {
                    app.catalogRepo.addActivityType(
                        familyId, form.name, form.icon.ifBlank { null }, null, form.kind,
                        form.maxDurationSec, form.reminderMode, form.reminderIntervalSec,
                        sortOrder = types.size, babyId = form.babyId,
                    )
                    addingType = false
                    syncAndReschedule()
                }
            },
        )
    }
    editType?.let { type ->
        TypeDialog(
            title = "编辑行为",
            babies = babies,
            initial = type,
            onDismiss = { editType = null },
            onSave = { form ->
                scope.launch {
                    app.catalogRepo.updateActivityType(
                        type.copy(
                            name = form.name,
                            icon = form.icon.ifBlank { null },
                            defaultMaxDurationSec = form.maxDurationSec,
                            reminderMode = form.reminderMode,
                            reminderFixedIntervalSec = form.reminderIntervalSec,
                            babyId = form.babyId,
                        )
                    )
                    editType = null
                    syncAndReschedule()
                }
            },
            onDelete = {
                scope.launch {
                    app.catalogRepo.updateActivityType(type.copy(deletedAt = System.currentTimeMillis()))
                    editType = null
                    syncAndReschedule()
                }
            },
        )
    }
    if (editingProfile) {
        ProfileDialog(
            initialEmoji = me?.avatarEmoji ?: "",
            initialName = me?.displayName ?: "",
            onDismiss = { editingProfile = false },
            onSave = { emoji, name ->
                scope.launch {
                    // 在线操作(security definer RPC),成功后回写本地缓存
                    runCatching {
                        app.familyRepo.updateMyProfile(familyId, name, emoji)
                        myUserId?.let {
                            app.database.familyMemberDao().upsert(
                                FamilyMemberEntity(
                                    familyId = familyId, userId = it,
                                    role = me?.role ?: "member",
                                    displayName = name, avatarEmoji = emoji,
                                )
                            )
                        }
                    }
                    editingProfile = false
                }
            },
        )
    }
    inviteCode?.let { code ->
        Dialog(onDismissRequest = { inviteCode = null }) {
            Card {
                Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("邀请码(72 小时内有效)", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(12.dp))
                    Text(code, style = MaterialTheme.typography.headlineLarge)
                    Spacer(Modifier.height(12.dp))
                    TextButton(onClick = { inviteCode = null }) { Text("关闭") }
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Spacer(Modifier.height(8.dp))
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
    Spacer(Modifier.height(4.dp))
}

@Composable
private fun SettingsRow(leading: String, trailing: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(leading, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(trailing, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
    }
}

@Composable
private fun BabyDialog(
    title: String,
    initialName: String = "",
    initialBirthday: String = "",
    onDismiss: () -> Unit,
    onSave: (name: String, birthday: String?) -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }
    var birthday by remember { mutableStateOf(initialBirthday) }
    Dialog(onDismissRequest = onDismiss) {
        Card {
            Column(Modifier.padding(20.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(name, { name = it }, label = { Text("名字") },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(birthday, { birthday = it }, label = { Text("生日(如 2026-01-31,可空)") },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("取消") }
                    TextButton(
                        onClick = { onSave(name.trim(), birthday.trim().ifEmpty { null }) },
                        enabled = name.isNotBlank(),
                    ) { Text("保存") }
                }
            }
        }
    }
}

@Composable
private fun ProfileDialog(
    initialEmoji: String,
    initialName: String,
    onDismiss: () -> Unit,
    onSave: (emoji: String?, name: String?) -> Unit,
) {
    var emoji by remember { mutableStateOf(initialEmoji) }
    var name by remember { mutableStateOf(initialName) }
    Dialog(onDismissRequest = onDismiss) {
        Card {
            Column(Modifier.padding(20.dp)) {
                Text("我的资料", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(emoji, { emoji = it.take(4) }, label = { Text("头像 emoji") },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(name, { name = it }, label = { Text("昵称") },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("取消") }
                    TextButton(onClick = {
                        onSave(emoji.trim().ifEmpty { null }, name.trim().ifEmpty { null })
                    }) { Text("保存") }
                }
            }
        }
    }
}

private data class TypeForm(
    val name: String,
    val icon: String,
    val kind: String,
    val maxDurationSec: Long?,
    val reminderMode: String,
    val reminderIntervalSec: Long?,
    val babyId: String?,
)

@Composable
private fun TypeDialog(
    title: String,
    babies: List<BabyEntity>,
    initial: ActivityTypeEntity? = null,
    onDismiss: () -> Unit,
    onSave: (TypeForm) -> Unit,
    onDelete: (() -> Unit)? = null,
) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var icon by remember { mutableStateOf(initial?.icon ?: "") }
    var kind by remember { mutableStateOf(initial?.kind ?: "instant") }
    var babyId by remember { mutableStateOf(initial?.babyId) }
    var maxDurationMin by remember {
        mutableStateOf(initial?.defaultMaxDurationSec?.let { (it / 60).toString() } ?: "")
    }
    var reminderMode by remember { mutableStateOf(initial?.reminderMode ?: "off") }
    var reminderIntervalMin by remember {
        mutableStateOf(initial?.reminderFixedIntervalSec?.let { (it / 60).toString() } ?: "")
    }
    var confirmDelete by remember { mutableStateOf(false) }
    val intervalValid = reminderMode != "fixed" || reminderIntervalMin.toLongOrNull()?.let { it > 0 } == true

    Dialog(onDismissRequest = onDismiss) {
        Card {
            Column(Modifier.padding(20.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(name, { name = it }, label = { Text("名称") },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(icon, { icon = it.take(4) }, label = { Text("图标(emoji,可空)") },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                // 已有记录后改类别会让统计口径混乱,编辑时锁定
                if (initial == null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = kind == "instant", onClick = { kind = "instant" },
                            label = { Text("瞬时") })
                        FilterChip(selected = kind == "duration", onClick = { kind = "duration" },
                            label = { Text("持续") })
                    }
                    Spacer(Modifier.height(8.dp))
                }
                if (kind == "duration") {
                    OutlinedTextField(maxDurationMin, { maxDurationMin = it.filter(Char::isDigit) },
                        label = { Text("最长时长(分钟,超时自动结束)") },
                        singleLine = true, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                }
                if (babies.size > 1) {
                    Text("适用宝宝", style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = babyId == null, onClick = { babyId = null },
                            label = { Text("通用") })
                        babies.forEach { b ->
                            FilterChip(selected = babyId == b.id, onClick = { babyId = b.id },
                                label = { Text(b.name) })
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
                Text("提醒", style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = reminderMode == "off", onClick = { reminderMode = "off" },
                        label = { Text("关") })
                    FilterChip(selected = reminderMode == "auto", onClick = { reminderMode = "auto" },
                        label = { Text("智能") })
                    FilterChip(selected = reminderMode == "fixed", onClick = { reminderMode = "fixed" },
                        label = { Text("固定间隔") })
                }
                if (reminderMode == "fixed") {
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(reminderIntervalMin, { reminderIntervalMin = it.filter(Char::isDigit) },
                        label = { Text("间隔(分钟)") },
                        singleLine = true, modifier = Modifier.fillMaxWidth())
                }
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    if (onDelete != null) {
                        if (confirmDelete) {
                            TextButton(onClick = onDelete) {
                                Text("确认删除", color = MaterialTheme.colorScheme.error)
                            }
                        } else {
                            TextButton(onClick = { confirmDelete = true }) {
                                Text("删除", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text("取消") }
                    Spacer(Modifier.width(4.dp))
                    TextButton(
                        onClick = {
                            onSave(
                                TypeForm(
                                    name = name.trim(),
                                    icon = icon.trim(),
                                    kind = kind,
                                    maxDurationSec = maxDurationMin.toLongOrNull()?.times(60),
                                    reminderMode = reminderMode,
                                    reminderIntervalSec = if (reminderMode == "fixed") {
                                        reminderIntervalMin.toLongOrNull()?.times(60)
                                    } else null,
                                    babyId = babyId,
                                )
                            )
                        },
                        enabled = name.isNotBlank() && intervalValid,
                    ) { Text("保存") }
                }
            }
        }
    }
}
