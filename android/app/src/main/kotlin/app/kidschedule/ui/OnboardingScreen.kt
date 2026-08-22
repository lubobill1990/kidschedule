package app.kidschedule.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.kidschedule.KidScheduleApp
import app.kidschedule.data.sync.syncWithRetry
import kotlinx.coroutines.launch

private enum class Mode { CHECKING, CHOOSE, CREATE, JOIN }

@Composable
fun OnboardingScreen(app: KidScheduleApp, onDone: (String) -> Unit) {
    var mode by rememberSaveable { mutableStateOf(Mode.CHECKING) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // 老账号新设备:已有家庭则直接选第一个并拉数据
    LaunchedEffect(Unit) {
        runCatching { app.familyRepo.myFamilies() }
            .onSuccess { families ->
                if (families.isNotEmpty()) {
                    app.familyRepo.currentFamilyId = families.first().id
                    app.syncEngine.syncWithRetry()
                    onDone(families.first().id)
                } else {
                    mode = Mode.CHOOSE
                }
            }
            .onFailure { error = it.message; mode = Mode.CHOOSE }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        when (mode) {
            Mode.CHECKING -> CircularProgressIndicator()
            Mode.CHOOSE -> {
                Text("欢迎!", style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.height(24.dp))
                Button(onClick = { mode = Mode.CREATE }, modifier = Modifier.fillMaxWidth()) { Text("创建新家庭") }
                Spacer(Modifier.height(12.dp))
                OutlinedButton(onClick = { mode = Mode.JOIN }, modifier = Modifier.fillMaxWidth()) { Text("用邀请码加入家庭") }
            }
            Mode.CREATE -> {
                var familyName by rememberSaveable { mutableStateOf("我们家") }
                var babyName by rememberSaveable { mutableStateOf("") }
                var birthday by rememberSaveable { mutableStateOf("") }
                Text("创建家庭", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(familyName, { familyName = it }, label = { Text("家庭名称") },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(babyName, { babyName = it }, label = { Text("宝宝名字") },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(birthday, { birthday = it }, label = { Text("生日(可选,如 2026-01-31)") },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = {
                        scope.launch {
                            busy = true; error = null
                            runCatching {
                                val fid = app.familyRepo.createFamily(familyName.trim(), null)
                                app.catalogRepo.seedDefaultTypes(fid)
                                app.catalogRepo.addBaby(fid, babyName.trim(), birthday.trim().ifEmpty { null })
                                app.familyRepo.currentFamilyId = fid
                                app.syncEngine.syncWithRetry()
                                fid
                            }.onSuccess(onDone).onFailure { error = it.message }
                            busy = false
                        }
                    },
                    enabled = familyName.isNotBlank() && babyName.isNotBlank() && !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (busy) "创建中…" else "创建") }
                TextButton(onClick = { mode = Mode.CHOOSE }) { Text("返回") }
            }
            Mode.JOIN -> {
                var codeInput by rememberSaveable { mutableStateOf("") }
                Text("加入家庭", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = codeInput,
                    onValueChange = { codeInput = it.uppercase().take(8) },
                    label = { Text("8 位邀请码") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = {
                        scope.launch {
                            busy = true; error = null
                            runCatching {
                                val fid = app.familyRepo.acceptInvite(codeInput.trim(), null)
                                app.familyRepo.currentFamilyId = fid
                                app.syncEngine.syncWithRetry()
                                fid
                            }.onSuccess(onDone).onFailure { error = it.message }
                            busy = false
                        }
                    },
                    enabled = codeInput.length == 8 && !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (busy) "加入中…" else "加入") }
                TextButton(onClick = { mode = Mode.CHOOSE }) { Text("返回") }
            }
        }
        error?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
    }
}
