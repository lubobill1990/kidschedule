package app.kidschedule.ui

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import app.kidschedule.KidScheduleApp
import app.kidschedule.data.local.EventAttachmentEntity
import app.kidschedule.data.local.EventEntity
import app.kidschedule.data.sync.syncWithRetry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun EventDetailDialog(
    app: KidScheduleApp,
    familyId: String,
    event: EventEntity,
    typeName: String,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var note by remember { mutableStateOf(event.note ?: "") }
    var confirmDelete by remember { mutableStateOf(false) }
    // 瞬时事件 endedAt == startedAt,改开始时间时两者同步移动
    val isInstantLike = event.endedAt == event.startedAt
    val showEnd = event.status == "done" && !isInstantLike
    var startText by remember { mutableStateOf(TimeFmt.editText(event.startedAt)) }
    var endText by remember {
        mutableStateOf(if (showEnd) event.endedAt?.let { TimeFmt.editText(it) } ?: "" else "")
    }
    val startMillis = TimeFmt.parseEdit(startText)
    val endMillis = if (showEnd) TimeFmt.parseEdit(endText) else null
    val timeValid = startMillis != null && (!showEnd || (endMillis != null && endMillis >= startMillis))
    val attachments by app.database.eventAttachmentDao().observeForEvent(event.id)
        .collectAsState(initial = emptyList())
    val members by app.database.familyMemberDao().observeAll(familyId)
        .collectAsState(initial = emptyList())
    // 本地新建行 createdBy 为空 → 归到当前用户
    val myUserId = remember { app.authRepo.currentUserId() }
    val creator = members.firstOrNull { it.userId == (event.createdBy ?: myUserId) }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(5)
    ) { uris ->
        if (uris.isNotEmpty()) scope.launch {
            uris.forEach { app.attachmentRepo.add(familyId, event.id, it) }
            runCatching { app.attachmentRepo.uploadPending() }
            app.syncEngine.syncWithRetry()
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Card {
            Column(Modifier.padding(20.dp)) {
                Text(typeName, style = MaterialTheme.typography.titleMedium)
                if (event.status == "ongoing") {
                    Text("进行中", style = MaterialTheme.typography.bodySmall)
                }
                creator?.let {
                    Text(
                        "记录人:${it.avatarEmoji ?: ""}${it.displayName ?: ""}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
                Spacer(Modifier.height(12.dp))

                OutlinedTextField(
                    value = startText,
                    onValueChange = { startText = it },
                    label = { Text("开始时间") },
                    isError = startMillis == null,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (showEnd) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = endText,
                        onValueChange = { endText = it },
                        label = { Text("结束时间") },
                        isError = endMillis == null || (startMillis != null && endMillis < startMillis),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Spacer(Modifier.height(8.dp))

                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("备注") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                )
                Spacer(Modifier.height(12.dp))

                if (attachments.isNotEmpty()) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(attachments, key = { it.id }) { a ->
                            AttachmentThumb(app, a, onDelete = {
                                scope.launch { app.attachmentRepo.softDelete(a.id) }
                            })
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
                TextButton(onClick = {
                    picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }) { Text("添加图片") }

                Spacer(Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    if (confirmDelete) {
                        TextButton(onClick = {
                            scope.launch {
                                app.recordRepo.softDelete(event.id)
                                app.syncEngine.syncWithRetry()
                                onDismiss()
                            }
                        }) { Text("确认删除", color = MaterialTheme.colorScheme.error) }
                        TextButton(onClick = { confirmDelete = false }) { Text("取消") }
                    } else {
                        TextButton(onClick = { confirmDelete = true }) {
                            Text("删除记录", color = MaterialTheme.colorScheme.error)
                        }
                        Spacer(Modifier.size(8.dp))
                        TextButton(onClick = onDismiss) { Text("取消") }
                        TextButton(
                            onClick = {
                                scope.launch {
                                    val newStart = startMillis ?: event.startedAt
                                    val newEnd = when {
                                        event.status == "ongoing" -> null
                                        isInstantLike -> newStart
                                        else -> endMillis ?: event.endedAt
                                    }
                                    app.recordRepo.update(
                                        event.copy(
                                            startedAt = newStart,
                                            endedAt = newEnd,
                                            note = note.trim().ifEmpty { null },
                                        )
                                    )
                                    app.syncEngine.syncWithRetry()
                                    onDismiss()
                                }
                            },
                            enabled = timeValid,
                        ) { Text("保存") }
                    }
                }
            }
        }
    }
}

@Composable
private fun AttachmentThumb(
    app: KidScheduleApp,
    a: EventAttachmentEntity,
    onDelete: () -> Unit,
) {
    var confirm by remember { mutableStateOf(false) }
    val bitmap by produceState<ImageBitmap?>(initialValue = null, a.id, a.uploadState) {
        val path = app.attachmentRepo.ensureLocal(a)
        value = path?.let { withContext(Dispatchers.IO) { decodeThumb(it) } }
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(RoundedCornerShape(8.dp))
                .clickable { confirm = !confirm },
            contentAlignment = Alignment.Center,
        ) {
            bitmap?.let {
                Image(it, contentDescription = null, contentScale = ContentScale.Crop)
            } ?: CircularProgressIndicator(Modifier.size(24.dp))
        }
        if (confirm) {
            TextButton(onClick = onDelete) {
                Text("删除", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
            }
        } else if (a.uploadState == "pending") {
            Text("待上传", style = MaterialTheme.typography.labelSmall)
        }
    }
}

private fun decodeThumb(path: String, target: Int = 512): ImageBitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    if (bounds.outWidth <= 0) return null
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= target || bounds.outHeight / (sample * 2) >= target) sample *= 2
    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
    return BitmapFactory.decodeFile(path, opts)?.asImageBitmap()
}
