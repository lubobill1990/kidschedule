package app.kidschedule.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.lifecycle.lifecycleScope
import app.kidschedule.KidScheduleApp
import app.kidschedule.ui.KidScheduleTheme
import kotlinx.coroutines.launch

// 添加单行为 widget 时的配置页:选宝宝(多宝宝时)+ 选行为
class TypeWidgetConfigActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val appWidgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID
        setResult(RESULT_CANCELED)
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }
        val app = application as KidScheduleApp

        setContent {
            KidScheduleTheme {
                val babies by app.database.babyDao().observeAll().collectAsState(initial = emptyList())
                val types by app.database.activityTypeDao().observeAll().collectAsState(initial = emptyList())
                var selectedBabyId by remember { mutableStateOf<String?>(null) }
                val babyId = babies.firstOrNull { it.id == selectedBabyId }?.id ?: babies.firstOrNull()?.id
                val visibleTypes = types.filter { it.babyId == null || it.babyId == babyId }

                Scaffold(
                    topBar = {
                        Text(
                            "选择这个 widget 记录的行为",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(16.dp),
                        )
                    },
                ) { padding ->
                    Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
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
                        LazyColumn {
                            items(visibleTypes, key = { it.id }) { t ->
                                Row(
                                    modifier = Modifier.fillMaxWidth()
                                        .clickable(enabled = babyId != null) {
                                            babyId?.let { save(appWidgetId, it, t.id) }
                                        }
                                        .padding(vertical = 14.dp),
                                ) {
                                    Text(
                                        "${t.icon ?: ""} ${t.name}",
                                        style = MaterialTheme.typography.bodyLarge,
                                        modifier = Modifier.weight(1f),
                                    )
                                    Text(
                                        if (t.kind == "duration") "持续" else "瞬时",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.outline,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun save(appWidgetId: Int, babyId: String, typeId: String) {
        lifecycleScope.launch {
            val glanceId = GlanceAppWidgetManager(this@TypeWidgetConfigActivity)
                .getGlanceIdBy(appWidgetId)
            updateAppWidgetState(this@TypeWidgetConfigActivity, glanceId) { prefs ->
                prefs[TypeWidget.KEY_TYPE_ID] = typeId
                prefs[TypeWidget.KEY_BABY_ID] = babyId
            }
            TypeWidget().update(this@TypeWidgetConfigActivity, glanceId)
            setResult(
                RESULT_OK,
                Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId),
            )
            finish()
        }
    }
}
