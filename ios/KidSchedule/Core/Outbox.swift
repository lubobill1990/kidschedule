import Foundation

// Outbox 状态机,协议 §3 §5。存储由调用方提供(app 层用 GRDB 适配,测试用内存实现)。

enum OutboxState: String {
    case held, pending, inflight
}

struct OutboxItem {
    let opId: Int64
    let entityId: String
    var state: OutboxState
    var holdUntil: Int64?
}

enum UndoResult {
    case ok, rejected
}

protocol OutboxStore {
    func items() throws -> [OutboxItem]
    func itemsFor(entityId: String) throws -> [OutboxItem]
    @discardableResult
    func insert(entityId: String, state: OutboxState, holdUntil: Int64?) throws -> OutboxItem
    func updateState(opId: Int64, state: OutboxState, holdUntil: Int64?) throws
    func delete(opId: Int64) throws
}

protocol LocalRowStore {
    func exists(entityId: String) throws -> Bool
    /// 写入/覆盖本地行(引擎只关心行存在性,行内容由调用方管理)
    func upsert(entityId: String) throws
    /// 物理删除,仅用于撤销未上行的行
    func delete(entityId: String) throws
}

struct OutboxEngine {
    let outbox: OutboxStore
    let rows: LocalRowStore

    /// 带撤销窗口的快速记录(协议 §5)
    func quickRecord(entityId: String, nowMillis: Int64) throws {
        try rows.upsert(entityId: entityId)
        try outbox.insert(
            entityId: entityId,
            state: .held,
            holdUntil: nowMillis + Int64(SyncProtocol.undoWindowSec) * 1000
        )
    }

    /// 普通写入(协议 §3):与既有 held/pending 合并;仅 inflight 时新建 pending
    func normalWrite(entityId: String, nowMillis: Int64) throws {
        try rows.upsert(entityId: entityId)
        let existing = try outbox.itemsFor(entityId: entityId)
        let hasMergeable = existing.contains { $0.state == .held || $0.state == .pending }
        if !hasMergeable {
            try outbox.insert(entityId: entityId, state: .pending, holdUntil: nil)
        }
    }

    /// 撤销:仅 held 有效,物理删本地行 + outbox 项(协议 §5)
    func undo(entityId: String) throws -> UndoResult {
        guard let held = try outbox.itemsFor(entityId: entityId).first(where: { $0.state == .held }) else {
            return .rejected
        }
        try outbox.delete(opId: held.opId)
        try rows.delete(entityId: entityId)
        return .ok
    }

    /// 释放过期 held(协议 §3)
    func releaseExpiredHolds(nowMillis: Int64) throws {
        for item in try outbox.items()
        where item.state == .held && item.holdUntil != nil && item.holdUntil! <= nowMillis {
            try outbox.updateState(opId: item.opId, state: .pending, holdUntil: nil)
        }
    }

    /// 组装推送批次:pending → inflight,返回批次(按 opId 升序,批大小上限)
    func pushBegin() throws -> [OutboxItem] {
        let batch = try outbox.items()
            .filter { $0.state == .pending }
            .sorted { $0.opId < $1.opId }
            .prefix(SyncProtocol.pushBatchSize)
        for item in batch {
            try outbox.updateState(opId: item.opId, state: .inflight, holdUntil: nil)
        }
        return batch.map { OutboxItem(opId: $0.opId, entityId: $0.entityId, state: .inflight, holdUntil: nil) }
    }

    /// 推送成功:acked = 删除 inflight 项
    func pushAck(opIds: [Int64]) throws {
        for opId in opIds {
            try outbox.delete(opId: opId)
        }
    }

    /// 推送失败:整批回退 pending;若同实体已有 pending 则合并(删除回退项)
    func pushFail(opIds: [Int64]) throws {
        let all = try outbox.items()
        let byId = Dictionary(uniqueKeysWithValues: all.map { ($0.opId, $0) })
        for opId in opIds {
            guard let item = byId[opId] else { continue }
            let hasPending = all.contains {
                $0.entityId == item.entityId && $0.state == .pending && $0.opId != opId
            }
            if hasPending {
                try outbox.delete(opId: opId)
            } else {
                try outbox.updateState(opId: opId, state: .pending, holdUntil: nil)
            }
        }
    }

    /// 拉取时远端赢(协议 §6):删除该实体全部未 acked 项;本地行保留为远端版本
    func pullRemoteWins(entityId: String) throws {
        for item in try outbox.itemsFor(entityId: entityId) {
            try outbox.delete(opId: item.opId)
        }
        try rows.upsert(entityId: entityId)
    }
}

// 参考实现,测试与内存场景使用;GRDB 适配须与之行为一致(由共享向量保证)
final class InMemoryOutboxStore: OutboxStore {
    private var nextId: Int64 = 1
    private var order: [Int64] = []
    private var map: [Int64: OutboxItem] = [:]

    func items() throws -> [OutboxItem] { order.compactMap { map[$0] } }

    func itemsFor(entityId: String) throws -> [OutboxItem] {
        try items().filter { $0.entityId == entityId }
    }

    @discardableResult
    func insert(entityId: String, state: OutboxState, holdUntil: Int64?) throws -> OutboxItem {
        let item = OutboxItem(opId: nextId, entityId: entityId, state: state, holdUntil: holdUntil)
        nextId += 1
        order.append(item.opId)
        map[item.opId] = item
        return item
    }

    func updateState(opId: Int64, state: OutboxState, holdUntil: Int64?) throws {
        guard var item = map[opId] else { return }
        item.state = state
        item.holdUntil = holdUntil
        map[opId] = item
    }

    func delete(opId: Int64) throws {
        map.removeValue(forKey: opId)
        order.removeAll { $0 == opId }
    }
}

final class InMemoryLocalRowStore: LocalRowStore {
    private var rows: Set<String> = []
    func exists(entityId: String) throws -> Bool { rows.contains(entityId) }
    func upsert(entityId: String) throws { rows.insert(entityId) }
    func delete(entityId: String) throws { rows.remove(entityId) }
}
