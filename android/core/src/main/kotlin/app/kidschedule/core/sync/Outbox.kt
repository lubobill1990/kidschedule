package app.kidschedule.core.sync

import app.kidschedule.core.Protocol

// Outbox 状态机,协议 §3 §5。存储由调用方提供(app 层用 Room 适配,测试用内存实现)。

enum class OutboxState { HELD, PENDING, INFLIGHT }

data class OutboxItem(
    val opId: Long,
    val entityId: String,
    val state: OutboxState,
    val holdUntil: Long? = null,
)

enum class UndoResult { OK, REJECTED }

interface OutboxStore {
    fun items(): List<OutboxItem>
    fun itemsFor(entityId: String): List<OutboxItem>
    fun insert(entityId: String, state: OutboxState, holdUntil: Long?): OutboxItem
    fun updateState(opId: Long, state: OutboxState, holdUntil: Long?)
    fun delete(opId: Long)
}

interface LocalRowStore {
    fun exists(entityId: String): Boolean
    /** 写入/覆盖本地行(引擎只关心行存在性,行内容由调用方管理) */
    fun upsert(entityId: String)
    /** 物理删除,仅用于撤销未上行的行 */
    fun delete(entityId: String)
}

class OutboxEngine(
    private val outbox: OutboxStore,
    private val rows: LocalRowStore,
) {
    /** 带撤销窗口的快速记录(协议 §5) */
    fun quickRecord(entityId: String, nowMillis: Long) {
        rows.upsert(entityId)
        outbox.insert(entityId, OutboxState.HELD, nowMillis + Protocol.UNDO_WINDOW_SEC * 1000L)
    }

    /** 普通写入(协议 §3):与既有 held/pending 合并;仅 inflight 时新建 pending */
    fun normalWrite(entityId: String, nowMillis: Long) {
        rows.upsert(entityId)
        val existing = outbox.itemsFor(entityId)
        val hasMergeable = existing.any { it.state == OutboxState.HELD || it.state == OutboxState.PENDING }
        if (!hasMergeable) {
            outbox.insert(entityId, OutboxState.PENDING, null)
        }
    }

    /** 撤销:仅 held 有效,物理删本地行 + outbox 项(协议 §5) */
    fun undo(entityId: String): UndoResult {
        val held = outbox.itemsFor(entityId).firstOrNull { it.state == OutboxState.HELD }
            ?: return UndoResult.REJECTED
        outbox.delete(held.opId)
        rows.delete(entityId)
        return UndoResult.OK
    }

    /** 释放过期 held(协议 §3) */
    fun releaseExpiredHolds(nowMillis: Long) {
        outbox.items()
            .filter { it.state == OutboxState.HELD && it.holdUntil != null && it.holdUntil <= nowMillis }
            .forEach { outbox.updateState(it.opId, OutboxState.PENDING, null) }
    }

    /** 组装推送批次:pending → inflight,返回批次(按 opId 升序,批大小上限) */
    fun pushBegin(): List<OutboxItem> {
        val batch = outbox.items()
            .filter { it.state == OutboxState.PENDING }
            .sortedBy { it.opId }
            .take(Protocol.PUSH_BATCH_SIZE)
        batch.forEach { outbox.updateState(it.opId, OutboxState.INFLIGHT, null) }
        return batch.map { it.copy(state = OutboxState.INFLIGHT) }
    }

    /** 推送成功:acked = 删除 inflight 项 */
    fun pushAck(opIds: List<Long>) {
        opIds.forEach { outbox.delete(it) }
    }

    /** 推送失败:整批回退 pending;若同实体已有 pending 则合并(删除回退项) */
    fun pushFail(opIds: List<Long>) {
        val all = outbox.items()
        val byId = all.associateBy { it.opId }
        for (opId in opIds) {
            val item = byId[opId] ?: continue
            val hasPending = all.any {
                it.entityId == item.entityId && it.state == OutboxState.PENDING && it.opId != opId
            }
            if (hasPending) outbox.delete(opId)
            else outbox.updateState(opId, OutboxState.PENDING, null)
        }
    }

    /** 拉取时远端赢(协议 §6):删除该实体全部未 acked 项;本地行保留为远端版本 */
    fun pullRemoteWins(entityId: String) {
        outbox.itemsFor(entityId).forEach { outbox.delete(it.opId) }
        rows.upsert(entityId)
    }
}

// 参考实现,测试与内存场景使用;Room 适配须与之行为一致(由共享向量保证)
class InMemoryOutboxStore : OutboxStore {
    private var nextId = 1L
    private val map = LinkedHashMap<Long, OutboxItem>()

    override fun items(): List<OutboxItem> = map.values.toList()
    override fun itemsFor(entityId: String): List<OutboxItem> =
        map.values.filter { it.entityId == entityId }

    override fun insert(entityId: String, state: OutboxState, holdUntil: Long?): OutboxItem {
        val item = OutboxItem(nextId++, entityId, state, holdUntil)
        map[item.opId] = item
        return item
    }

    override fun updateState(opId: Long, state: OutboxState, holdUntil: Long?) {
        val item = map[opId] ?: return
        map[opId] = item.copy(state = state, holdUntil = holdUntil)
    }

    override fun delete(opId: Long) {
        map.remove(opId)
    }
}

class InMemoryLocalRowStore : LocalRowStore {
    private val rows = HashSet<String>()
    override fun exists(entityId: String): Boolean = entityId in rows
    override fun upsert(entityId: String) { rows.add(entityId) }
    override fun delete(entityId: String) { rows.remove(entityId) }
}
