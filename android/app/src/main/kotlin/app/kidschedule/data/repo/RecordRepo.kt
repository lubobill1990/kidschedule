package app.kidschedule.data.repo

import androidx.room.withTransaction
import app.kidschedule.core.sync.UndoResult
import app.kidschedule.data.local.AppDatabase
import app.kidschedule.data.local.EventEntity
import app.kidschedule.data.sync.SyncEngine
import app.kidschedule.data.sync.SyncEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

// 事件写入口。所有写:先写本地行,再走 OutboxEngine(协议 §3 §5),同一事务。
class RecordRepo(
    private val db: AppDatabase,
    syncEngine: SyncEngine,
    private val deviceId: String,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val engine = syncEngine.engines.getValue(SyncEntity.EVENTS)

    /** 瞬时行为一键记录(带撤销窗口),返回事件 id */
    suspend fun quickRecordInstant(familyId: String, babyId: String, typeId: String): String =
        withContext(Dispatchers.IO) {
            val id = UUID.randomUUID().toString()
            val t = now()
            db.withTransaction {
                db.eventDao().upsertBlocking(
                    EventEntity(
                        id = id, familyId = familyId, babyId = babyId, activityTypeId = typeId,
                        startedAt = t, endedAt = t, status = "done", autoEnded = false,
                        note = null, createdBy = null, deletedAt = null,
                        clientUpdatedAt = t, deviceId = deviceId,
                    )
                )
                engine.quickRecord(id, t)
            }
            id
        }

    /** 持续行为一键开始(带撤销窗口);本地已有 ongoing 返回 null(协议 §7) */
    suspend fun quickStartDuration(familyId: String, babyId: String, typeId: String): String? =
        withContext(Dispatchers.IO) {
            db.withTransaction {
                if (db.eventDao().ongoingForBlocking(babyId, typeId) != null) return@withTransaction null
                val id = UUID.randomUUID().toString()
                val t = now()
                db.eventDao().upsertBlocking(
                    EventEntity(
                        id = id, familyId = familyId, babyId = babyId, activityTypeId = typeId,
                        startedAt = t, endedAt = null, status = "ongoing", autoEnded = false,
                        note = null, createdBy = null, deletedAt = null,
                        clientUpdatedAt = t, deviceId = deviceId,
                    )
                )
                engine.quickRecord(id, t)
                id
            }
        }

    /** 撤销窗口内撤销:物理删本地行,永不上行(协议 §5) */
    suspend fun undo(eventId: String): Boolean = withContext(Dispatchers.IO) {
        db.withTransaction { engine.undo(eventId) == UndoResult.OK }
    }

    suspend fun endDuration(eventId: String, endedAtMillis: Long? = null, autoEnded: Boolean = false) =
        withContext(Dispatchers.IO) {
            val t = now()
            db.withTransaction {
                val e = db.eventDao().getByIdBlocking(eventId) ?: return@withTransaction
                if (e.status == "done") return@withTransaction
                db.eventDao().upsertBlocking(
                    e.copy(status = "done", endedAt = endedAtMillis ?: t, autoEnded = autoEnded, clientUpdatedAt = t)
                )
                engine.normalWrite(eventId, t)
            }
        }

    /** 补录历史记录:直接进同步,无撤销窗口 */
    suspend fun backfill(
        familyId: String, babyId: String, typeId: String,
        startedAt: Long, endedAt: Long?, note: String?,
    ): String = withContext(Dispatchers.IO) {
        val id = UUID.randomUUID().toString()
        val t = now()
        db.withTransaction {
            db.eventDao().upsertBlocking(
                EventEntity(
                    id = id, familyId = familyId, babyId = babyId, activityTypeId = typeId,
                    startedAt = startedAt, endedAt = endedAt ?: startedAt, status = "done",
                    autoEnded = false, note = note, createdBy = null, deletedAt = null,
                    clientUpdatedAt = t, deviceId = deviceId,
                )
            )
            engine.normalWrite(id, t)
        }
        id
    }

    /** 编辑(补录/修正/改备注);整行覆盖并 bump client_updated_at */
    suspend fun update(event: EventEntity) = withContext(Dispatchers.IO) {
        val t = now()
        db.withTransaction {
            db.eventDao().upsertBlocking(event.copy(clientUpdatedAt = t, deviceId = deviceId))
            engine.normalWrite(event.id, t)
        }
    }

    suspend fun softDelete(eventId: String) = withContext(Dispatchers.IO) {
        val t = now()
        db.withTransaction {
            val e = db.eventDao().getByIdBlocking(eventId) ?: return@withTransaction
            db.eventDao().upsertBlocking(e.copy(deletedAt = t, clientUpdatedAt = t, deviceId = deviceId))
            engine.normalWrite(eventId, t)
        }
    }

    /** 扫描超时未结束的 ongoing,置 auto_ended(协议 §8);返回被自动结束的事件 id */
    suspend fun autoEndOverdue(): List<String> = withContext(Dispatchers.IO) {
        val t = now()
        db.withTransaction {
            val ended = mutableListOf<String>()
            for (e in db.eventDao().allOngoingBlocking()) {
                val maxSec = db.activityTypeDao().getByIdBlocking(e.activityTypeId)
                    ?.defaultMaxDurationSec ?: continue
                val deadline = e.startedAt + maxSec * 1000
                if (t >= deadline) {
                    db.eventDao().upsertBlocking(
                        e.copy(status = "done", endedAt = deadline, autoEnded = true, clientUpdatedAt = t)
                    )
                    engine.normalWrite(e.id, t)
                    ended += e.id
                }
            }
            ended
        }
    }
}
