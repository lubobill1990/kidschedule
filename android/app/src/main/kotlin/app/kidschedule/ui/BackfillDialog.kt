package app.kidschedule.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import app.kidschedule.KidScheduleApp
import app.kidschedule.data.local.ActivityTypeEntity
import kotlinx.coroutines.launch

@Composable
fun BackfillDialog(
    app: KidScheduleApp,
    familyId: String,
    babyId: String,
    types: List<ActivityTypeEntity>,
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var typeId by remember { mutableStateOf(types.firstOrNull()?.id) }
    val type = types.firstOrNull { it.id == typeId }
    var startText by remember { mutableStateOf(TimeFmt.editText(System.currentTimeMillis())) }
    var endText by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }

    val isDuration = type?.kind == "duration"
    val startMillis = TimeFmt.parseEdit(startText)
    val endMillis = if (isDuration) TimeFmt.parseEdit(endText) else null
    val valid = type != null && startMillis != null &&
        (!isDuration || (endMillis != null && endMillis >= startMillis))

    Dialog(onDismissRequest = onDismiss) {
        Card {
            Column(Modifier.padding(20.dp)) {
                Text("补录", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(12.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(types, key = { it.id }) { t ->
                        FilterChip(
                            selected = t.id == typeId,
                            onClick = { typeId = t.id },
                            label = { Text("${t.icon ?: ""}${t.name}") },
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = startText,
                    onValueChange = { startText = it },
                    label = { Text("开始时间(如 ${TimeFmt.editText(System.currentTimeMillis())})") },
                    isError = startMillis == null,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (isDuration) {
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
                    label = { Text("备注(可空)") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("取消") }
                    TextButton(
                        onClick = {
                            scope.launch {
                                app.recordRepo.backfill(
                                    familyId, babyId, type!!.id,
                                    startedAt = startMillis!!,
                                    endedAt = endMillis,
                                    note = note.trim().ifEmpty { null },
                                )
                                onSaved()
                            }
                        },
                        enabled = valid,
                    ) { Text("保存") }
                }
            }
        }
    }
}
