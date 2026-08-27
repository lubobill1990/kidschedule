package app.kidschedule.data.repo

import androidx.room.withTransaction
import app.kidschedule.data.local.ActivityTypeEntity
import app.kidschedule.data.local.AppDatabase
import app.kidschedule.data.local.BabyEntity
import app.kidschedule.data.sync.SyncEngine
import app.kidschedule.data.sync.SyncEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

// 宝宝与行为类型的本地写入口(无撤销窗口,normalWrite)
class CatalogRepo(
    private val db: AppDatabase,
    syncEngine: SyncEngine,
    private val deviceId: String,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val babyEngine = syncEngine.engines.getValue(SyncEntity.BABIES)
    private val typeEngine = syncEngine.engines.getValue(SyncEntity.ACTIVITY_TYPES)

    suspend fun addBaby(familyId: String, name: String, birthday: String?): String =
        withContext(Dispatchers.IO) {
            val id = UUID.randomUUID().toString()
            val t = now()
            db.withTransaction {
                db.babyDao().upsertBlocking(
                    BabyEntity(
                        id = id, familyId = familyId, name = name, birthday = birthday,
                        avatarPath = null, deletedAt = null, clientUpdatedAt = t, deviceId = deviceId,
                    )
                )
                babyEngine.normalWrite(id, t)
            }
            id
        }

    suspend fun updateBaby(baby: BabyEntity) = withContext(Dispatchers.IO) {
        val t = now()
        db.withTransaction {
            db.babyDao().upsertBlocking(baby.copy(clientUpdatedAt = t, deviceId = deviceId))
            babyEngine.normalWrite(baby.id, t)
        }
    }

    suspend fun addActivityType(
        familyId: String,
        name: String,
        icon: String?,
        color: String?,
        kind: String,
        defaultMaxDurationSec: Long? = null,
        reminderMode: String = "off",
        reminderFixedIntervalSec: Long? = null,
        sortOrder: Int = 0,
        babyId: String? = null,
    ): String = withContext(Dispatchers.IO) {
        val id = UUID.randomUUID().toString()
        val t = now()
        db.withTransaction {
            db.activityTypeDao().upsertBlocking(
                ActivityTypeEntity(
                    id = id, familyId = familyId, babyId = babyId, name = name, icon = icon, color = color,
                    kind = kind, defaultMaxDurationSec = defaultMaxDurationSec,
                    reminderMode = reminderMode, reminderFixedIntervalSec = reminderFixedIntervalSec,
                    sortOrder = sortOrder, deletedAt = null, clientUpdatedAt = t, deviceId = deviceId,
                )
            )
            typeEngine.normalWrite(id, t)
        }
        id
    }

    suspend fun updateActivityType(type: ActivityTypeEntity) = withContext(Dispatchers.IO) {
        val t = now()
        db.withTransaction {
            db.activityTypeDao().upsertBlocking(type.copy(clientUpdatedAt = t, deviceId = deviceId))
            typeEngine.normalWrite(type.id, t)
        }
    }

    /** 建家庭后播种默认行为类型 */
    suspend fun seedDefaultTypes(familyId: String) {
        addActivityType(familyId, "喂奶", "🍼", "#5B8DEF", "duration", 45 * 60L, "auto", sortOrder = 0)
        addActivityType(familyId, "辅食", "🥣", "#F2A65A", "instant", sortOrder = 1)
        addActivityType(familyId, "尿", "💧", "#63C5DA", "instant", sortOrder = 2)
        addActivityType(familyId, "便", "💩", "#A9836F", "instant", sortOrder = 3)
        addActivityType(familyId, "睡觉", "😴", "#8E7CC3", "duration", 5 * 3600L, "off", sortOrder = 4)
    }
}
