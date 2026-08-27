package app.kidschedule.data.sync

import androidx.room.withTransaction
import app.kidschedule.core.Protocol
import app.kidschedule.core.sync.Lww
import app.kidschedule.core.sync.LwwVerdict
import app.kidschedule.core.sync.LwwVersion
import app.kidschedule.core.sync.OutboxEngine
import app.kidschedule.data.local.AppDatabase
import app.kidschedule.data.local.SyncCursorEntity
import app.kidschedule.data.remote.ActivityTypeDto
import app.kidschedule.data.remote.BabyDto
import app.kidschedule.data.remote.EventAttachmentDto
import app.kidschedule.data.remote.EventDto
import app.kidschedule.data.remote.FamilyMemberDto
import app.kidschedule.data.remote.IsoTime
import app.kidschedule.data.remote.PushResultDto
import app.kidschedule.data.remote.toDto
import app.kidschedule.data.remote.toEntity
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.postgrest.result.PostgrestResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// 同步调度总流程(协议 §12):释放过期 held → push 循环 → pull 循环。全程单飞锁。
// 重试/退避由调用方(WorkManager / 前台触发器)负责,本类失败直接抛出。

class SyncEngine(
    private val db: AppDatabase,
    private val client: SupabaseClient,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()
    private val json = Json { encodeDefaults = true; explicitNulls = false; ignoreUnknownKeys = true }

    val engines: Map<SyncEntity, OutboxEngine> = SyncEntity.entries.associateWith {
        OutboxEngine(RoomOutboxStore(db, it, now), RoomRowStore(db, it))
    }

    suspend fun sync() {
        mutex.withLock {
            withContext(Dispatchers.IO) {
                db.outboxDao().releaseExpiredHolds(now())
                for (entity in SyncEntity.entries) pushEntity(entity)
                for (entity in SyncEntity.entries) pullEntity(entity)
                // 成员资料只读缓存,失败不影响主同步
                runCatching { refreshFamilyMembers() }
            }
        }
    }

    /** 全量刷新成员缓存(RLS 限定本人家庭) */
    private suspend fun refreshFamilyMembers() {
        val members = client.postgrest.from("family_members").select()
            .decodeList<FamilyMemberDto>()
        db.withTransaction {
            db.familyMemberDao().deleteAllBlocking()
            members.forEach { db.familyMemberDao().upsertBlocking(it.toEntity()) }
        }
    }

    // ---- push(协议 §4)----

    private suspend fun pushEntity(entity: SyncEntity) {
        while (true) {
            val batch = db.withTransaction { engines.getValue(entity).pushBegin() }
            if (batch.isEmpty()) return
            // 同批同实体去重,一次 upsert;成功后 ack 其全部 opId(协议 §3)
            val opIdsByEntityId = LinkedHashMap<String, MutableList<Long>>()
            for (item in batch) opIdsByEntityId.getOrPut(item.entityId) { mutableListOf() }.add(item.opId)
            try {
                val rows: Map<String, JsonElement> =
                    db.withTransaction { loadRowsAsJson(entity, opIdsByEntityId.keys.toList()) }
                val results: List<PushResultDto> = if (rows.isEmpty()) emptyList() else {
                    client.postgrest.rpc(
                        "push_${entity.table}",
                        buildJsonObject { put("rows", JsonArray(rows.values.toList())) },
                    ).decodeAs()
                }
                db.withTransaction {
                    val eng = engines.getValue(entity)
                    // 本地行已不存在的项(不应发生)直接 ack 丢弃
                    opIdsByEntityId.keys.filter { it !in rows }.forEach { eng.pushAck(opIdsByEntityId.getValue(it)) }
                    for (res in results) {
                        val opIds = opIdsByEntityId[res.id] ?: continue
                        if (res.outcome == "rejected" && res.reason == "ongoing_conflict" && entity == SyncEntity.EVENTS) {
                            db.eventDao().markDeletedLocalBlocking(res.id, now())
                        }
                        eng.pushAck(opIds) // applied / stale / rejected 均视为已处理
                    }
                }
            } catch (e: Exception) {
                db.withTransaction { engines.getValue(entity).pushFail(batch.map { it.opId }) }
                throw e
            }
        }
    }

    private fun loadRowsAsJson(entity: SyncEntity, ids: List<String>): Map<String, JsonElement> =
        when (entity) {
            SyncEntity.BABIES -> db.babyDao().getByIdsBlocking(ids)
                .associate { it.id to json.encodeToJsonElement(BabyDto.serializer(), it.toDto()) }
            SyncEntity.ACTIVITY_TYPES -> db.activityTypeDao().getByIdsBlocking(ids)
                .associate { it.id to json.encodeToJsonElement(ActivityTypeDto.serializer(), it.toDto()) }
            SyncEntity.EVENTS -> db.eventDao().getByIdsBlocking(ids)
                .associate { it.id to json.encodeToJsonElement(EventDto.serializer(), it.toDto()) }
            SyncEntity.EVENT_ATTACHMENTS -> db.eventAttachmentDao().getByIdsBlocking(ids)
                .associate { it.id to json.encodeToJsonElement(EventAttachmentDto.serializer(), it.toDto()) }
        }

    // ---- pull(协议 §6 §9)----

    private suspend fun pullEntity(entity: SyncEntity) {
        while (true) {
            val cursor = db.syncCursorDao().get(entity.table)
            val ts = cursor?.lastUpdatedAt ?: "1970-01-01T00:00:00+00:00"
            val lastId = cursor?.lastId ?: "00000000-0000-0000-0000-000000000000"
            val result = selectPage(entity, ts, lastId)
            val (count, newCursor) = db.withTransaction { applyPage(entity, result) }
            if (newCursor != null) db.syncCursorDao().upsert(newCursor)
            if (count < Protocol.PULL_PAGE_SIZE) return
        }
    }

    private suspend fun selectPage(entity: SyncEntity, ts: String, lastId: String): PostgrestResult =
        client.postgrest.from(entity.table).select {
            filter {
                or {
                    gt("updated_at", ts)
                    and {
                        eq("updated_at", ts)
                        gt("id", lastId)
                    }
                }
            }
            order("updated_at", Order.ASCENDING)
            order("id", Order.ASCENDING)
            limit(Protocol.PULL_PAGE_SIZE.toLong())
        }

    /** 返回 (行数, 新游标);须在事务内调用 */
    private fun applyPage(entity: SyncEntity, result: PostgrestResult): Pair<Int, SyncCursorEntity?> {
        when (entity) {
            SyncEntity.BABIES -> {
                val rows = result.decodeList<BabyDto>()
                rows.forEach { applyRemote(entity, it.id, it.clientUpdatedAt, it.deviceId) { db.babyDao().upsertBlocking(it.toEntity()) } }
                return rows.size to rows.lastOrNull()?.let { SyncCursorEntity(entity.table, it.updatedAt!!, it.id) }
            }
            SyncEntity.ACTIVITY_TYPES -> {
                val rows = result.decodeList<ActivityTypeDto>()
                rows.forEach { applyRemote(entity, it.id, it.clientUpdatedAt, it.deviceId) { db.activityTypeDao().upsertBlocking(it.toEntity()) } }
                return rows.size to rows.lastOrNull()?.let { SyncCursorEntity(entity.table, it.updatedAt!!, it.id) }
            }
            SyncEntity.EVENTS -> {
                val rows = result.decodeList<EventDto>()
                rows.forEach { applyRemote(entity, it.id, it.clientUpdatedAt, it.deviceId) { db.eventDao().upsertBlocking(it.toEntity()) } }
                return rows.size to rows.lastOrNull()?.let { SyncCursorEntity(entity.table, it.updatedAt!!, it.id) }
            }
            SyncEntity.EVENT_ATTACHMENTS -> {
                val rows = result.decodeList<EventAttachmentDto>()
                rows.forEach { dto ->
                    applyRemote(entity, dto.id, dto.clientUpdatedAt, dto.deviceId) {
                        val localPath = db.eventAttachmentDao().getByIdBlocking(dto.id)?.localPath
                        db.eventAttachmentDao().upsertBlocking(dto.toEntity(localPath))
                    }
                }
                return rows.size to rows.lastOrNull()?.let { SyncCursorEntity(entity.table, it.updatedAt!!, it.id) }
            }
        }
    }

    /** 单行远端并入:本地不存在直接写;存在则 LWW,远端赢时清 outbox 并覆盖(协议 §6) */
    private fun applyRemote(
        entity: SyncEntity,
        id: String,
        remoteClientUpdatedAtIso: String,
        remoteDeviceId: String,
        writeRemote: () -> Unit,
    ) {
        val local = localVersion(entity, id)
        if (local == null) {
            writeRemote()
            return
        }
        val remote = LwwVersion(IsoTime.toMillis(remoteClientUpdatedAtIso), remoteDeviceId)
        if (Lww.decide(local, remote) == LwwVerdict.B_WINS) {
            engines.getValue(entity).pullRemoteWins(id)
            writeRemote()
        }
    }

    private fun localVersion(entity: SyncEntity, id: String): LwwVersion? = when (entity) {
        SyncEntity.BABIES -> db.babyDao().getByIdBlocking(id)?.let { LwwVersion(it.clientUpdatedAt, it.deviceId) }
        SyncEntity.ACTIVITY_TYPES -> db.activityTypeDao().getByIdBlocking(id)?.let { LwwVersion(it.clientUpdatedAt, it.deviceId) }
        SyncEntity.EVENTS -> db.eventDao().getByIdBlocking(id)?.let { LwwVersion(it.clientUpdatedAt, it.deviceId) }
        SyncEntity.EVENT_ATTACHMENTS -> db.eventAttachmentDao().getByIdBlocking(id)?.let { LwwVersion(it.clientUpdatedAt, it.deviceId) }
    }
}
