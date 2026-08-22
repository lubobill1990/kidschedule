package app.kidschedule.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.kidschedule.data.repo.AuthRepo
import kotlinx.coroutines.launch

@Composable
fun LoginScreen(authRepo: AuthRepo) {
    var phone by rememberSaveable { mutableStateOf("") }
    var code by rememberSaveable { mutableStateOf("") }
    var codeSent by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val fullPhone = "+86${phone.trim()}"

    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("KidSchedule", style = MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.height(32.dp))
        OutlinedTextField(
            value = phone,
            onValueChange = { phone = it.filter(Char::isDigit).take(11) },
            label = { Text("手机号") },
            prefix = { Text("+86 ") },
            singleLine = true,
            enabled = !codeSent,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(16.dp))
        if (!codeSent) {
            Button(
                onClick = {
                    scope.launch {
                        busy = true; error = null
                        runCatching { authRepo.sendOtp(fullPhone) }
                            .onSuccess { codeSent = true }
                            .onFailure { error = it.message }
                        busy = false
                    }
                },
                enabled = phone.length == 11 && !busy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (busy) "发送中…" else "发送验证码") }
        } else {
            OutlinedTextField(
                value = code,
                onValueChange = { code = it.filter(Char::isDigit).take(6) },
                label = { Text("验证码") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = {
                    scope.launch {
                        busy = true; error = null
                        runCatching { authRepo.verifyOtp(fullPhone, code) }
                            .onFailure { error = it.message }
                        busy = false
                    }
                },
                enabled = code.length == 6 && !busy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (busy) "验证中…" else "登录") }
            TextButton(onClick = { codeSent = false; code = "" }) { Text("换个手机号") }
        }
        error?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
    }
}
