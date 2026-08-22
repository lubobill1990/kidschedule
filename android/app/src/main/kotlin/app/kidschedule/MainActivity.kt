package app.kidschedule

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import app.kidschedule.ui.AppRoot
import app.kidschedule.ui.KidScheduleTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent {
            KidScheduleTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AppRoot(application as KidScheduleApp)
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val app = application as KidScheduleApp
        lifecycleScope.launch {
            runCatching { app.reminderScheduler.rescheduleAll() }
        }
    }
}
