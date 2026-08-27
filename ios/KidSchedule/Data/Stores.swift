import Foundation
import GRDB

// :core OutboxEngine 的 GRDB 适配。绑定事务内的 Database 句柄,只在 write/read 闭包内使用。
// 行为须与 InMemory 参考实现一致(由共享向量保证)。

enum SyncEntity: String, CaseIterable {
    case babies
    case activityTypes = "activity_types"
    case events
    case eventAttachments = "event_attachments"

    var table: String { rawValue }
}

struct GrdbOutboxStore: OutboxStore {
    let db: Database
    let entity: SyncEntity
    let now: () -> Int64

    private func toCore(_ row: OutboxRow) -> OutboxItem {
        OutboxItem(
            opId: row.opId!, entityId: row.entityId,
            state: OutboxState(rawValue: row.state)!, holdUntil: row.holdUntil
        )
    }

    func items() throws -> [OutboxItem] {
        try OutboxRow
            .filter(Column("entity") == entity.table)
            .order(Column("opId"))
            .fetchAll(db)
            .map(toCore)
    }

    func itemsFor(entityId: String) throws -> [OutboxItem] {
        try OutboxRow
            .filter(Column("entity") == entity.table && Column("entityId") == entityId)
            .order(Column("opId"))
            .fetchAll(db)
            .map(toCore)
    }

    @discardableResult
    func insert(entityId: String, state: OutboxState, holdUntil: Int64?) throws -> OutboxItem {
        var row = OutboxRow(
            opId: nil, entity: entity.table, entityId: entityId,
            state: state.rawValue, holdUntil: holdUntil, createdAt: now()
        )
        try row.insert(db)
        return OutboxItem(opId: row.opId!, entityId: entityId, state: state, holdUntil: holdUntil)
    }

    func updateState(opId: Int64, state: OutboxState, holdUntil: Int64?) throws {
        try db.execute(
            sql: "UPDATE outbox SET state = ?, holdUntil = ? WHERE opId = ?",
            arguments: [state.rawValue, holdUntil, opId]
        )
    }

    func delete(opId: Int64) throws {
        try db.execute(sql: "DELETE FROM outbox WHERE opId = ?", arguments: [opId])
    }
}

/// 行内容由 repository/pull 先行写入,upsert 在此为 no-op;delete 仅撤销未上行行时物理删
struct GrdbRowStore: LocalRowStore {
    let db: Database
    let entity: SyncEntity

    func exists(entityId: String) throws -> Bool {
        try Row.fetchOne(db, sql: "SELECT 1 FROM \(entity.table) WHERE id = ?", arguments: [entityId]) != nil
    }

    func upsert(entityId: String) throws {}

    func delete(entityId: String) throws {
        try db.execute(sql: "DELETE FROM \(entity.table) WHERE id = ?", arguments: [entityId])
    }
}

func outboxEngine(_ entity: SyncEntity, db: Database, now: @escaping () -> Int64) -> OutboxEngine {
    OutboxEngine(
        outbox: GrdbOutboxStore(db: db, entity: entity, now: now),
        rows: GrdbRowStore(db: db, entity: entity)
    )
}
