package app.kidschedule.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import app.kidschedule.KidScheduleApp
import io.github.jan.supabase.auth.status.SessionStatus

@Composable
fun AppRoot(app: KidScheduleApp) {
    val session by app.authRepo.sessionStatus.collectAsState()
    when (session) {
        is SessionStatus.Initializing -> Loading()
        is SessionStatus.Authenticated -> {
            var familyId by remember { mutableStateOf(app.familyRepo.currentFamilyId) }
            val fid = familyId
            if (fid == null) {
                OnboardingScreen(app, onDone = { familyId = it })
            } else {
                HomeScreen(app, fid)
            }
        }
        else -> LoginScreen(app.authRepo)
    }
}

@Composable
private fun Loading() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}
