package app.kidschedule.data.sync

import app.kidschedule.core.sync.LocalRowStore
import app.kidschedule.core.sync.OutboxItem
import app.kidschedule.core.sync.OutboxState
import app.kidschedule.core.sync.OutboxStore
import app.kidschedule.data.local.AppDatabase
import app.kidschedule.data.local.OutboxItemEntity

// :core OutboxEngine 的 Room 适配。阻塞 DAO,只在 IO 线程 / Room 事务内调用。
// 行为须与 InMemory 参考实现一致(由共享向量保证)。

enum class SyncEntity(val table: String) {
    BABIES("babies"),
    ACTIVITY_TYPES("activity_types"),
    EVENTS("events"),
    EVENT_ATTACHMENTS("event_attachments"),
}

private fun OutboxState.toDb(): String = name.lowercase()
private fun String.toState(): OutboxState = OutboxState.valueOf(uppercase())

private fun OutboxItemEntity.toCore() = OutboxItem(
    opId = opId, entityId = entityId, state = state.toState(), holdUntil = holdUntil,
)

class RoomOutboxStore(
    private val db: AppDatabase,
    private val entity: SyncEntity,
    private val now: () -> Long = System::currentTimeMillis,
) : OutboxStore {
    private val dao get() = db.outboxDao()

    override fun items(): List<OutboxItem> =
        dao.allForEntityBlocking(entity.table).map { it.toCore() }

    override fun itemsFor(entityId: String): List<OutboxItem> =
        dao.forEntityIdBlocking(entity.table, entityId).map { it.toCore() }

    override fun insert(entityId: String, state: OutboxState, holdUntil: Long?): OutboxItem {
        val opId = dao.insertBlocking(
            OutboxItemEntity(
                entity = entity.table, entityId = entityId,
                state = state.toDb(), holdUntil = holdUntil, createdAt = now(),
            )
        )
        return OutboxItem(opId, entityId, state, holdUntil)
    }

    override fun updateState(opId: Long, state: OutboxState, holdUntil: Long?) {
        dao.updateStateBlocking(opId, state.toDb(), holdUntil)
    }

    override fun delete(opId: Long) {
        dao.deleteBlocking(opId)
    }
}

/** 行内容由 repository/pull 先行写入,upsert 在此为 no-op;delete 仅撤销未上行行时物理删 */
class RoomRowStore(
    private val db: AppDatabase,
    private val entity: SyncEntity,
) : LocalRowStore {

    override fun exists(entityId: String): Boolean = when (entity) {
        SyncEntity.BABIES -> db.babyDao().getByIdBlocking(entityId) != null
        SyncEntity.ACTIVITY_TYPES -> db.activityTypeDao().getByIdBlocking(entityId) != null
        SyncEntity.EVENTS -> db.eventDao().getByIdBlocking(entityId) != null
        SyncEntity.EVENT_ATTACHMENTS -> db.eventAttachmentDao().getByIdBlocking(entityId) != null
    }

    override fun upsert(entityId: String) = Unit

    override fun delete(entityId: String) = when (entity) {
        SyncEntity.BABIES -> db.babyDao().physicalDeleteBlocking(entityId)
        SyncEntity.ACTIVITY_TYPES -> db.activityTypeDao().physicalDeleteBlocking(entityId)
        SyncEntity.EVENTS -> db.eventDao().physicalDeleteBlocking(entityId)
        SyncEntity.EVENT_ATTACHMENTS -> db.eventAttachmentDao().physicalDeleteBlocking(entityId)
    }
}
