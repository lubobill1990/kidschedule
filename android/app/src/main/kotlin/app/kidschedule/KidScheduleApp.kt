package app.kidschedule

import android.app.Application
import app.kidschedule.data.DeviceId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import app.kidschedule.data.local.AppDatabase
import app.kidschedule.data.remote.Supa
import app.kidschedule.data.repo.AttachmentRepo
import app.kidschedule.data.repo.AuthRepo
import app.kidschedule.data.repo.CatalogRepo
import app.kidschedule.data.repo.FamilyRepo
import app.kidschedule.data.repo.RecordRepo
import app.kidschedule.data.sync.RealtimeSignal
import app.kidschedule.data.sync.SyncEngine
import app.kidschedule.reminder.ReminderScheduler

class KidScheduleApp : Application() {

    // UI 关闭后仍要继续的后台工作(如保存后的同步)挂在这里,别用 composable scope
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val database: AppDatabase by lazy { AppDatabase.build(this) }
    val supabase by lazy { Supa.create() }
    val deviceId: String by lazy { DeviceId.get(this) }
    val syncEngine by lazy { SyncEngine(database, supabase) }
    val realtimeSignal by lazy { RealtimeSignal(supabase) }

    val authRepo by lazy { AuthRepo(supabase) }
    val familyRepo by lazy { FamilyRepo(supabase, this) }
    val recordRepo by lazy { RecordRepo(database, syncEngine, deviceId) }
    val catalogRepo by lazy { CatalogRepo(database, syncEngine, deviceId) }
    val attachmentRepo by lazy { AttachmentRepo(this, database, syncEngine, supabase, deviceId) }
    val reminderScheduler by lazy { ReminderScheduler(this, database) }
}
