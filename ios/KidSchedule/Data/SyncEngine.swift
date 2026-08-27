import Foundation
import GRDB

// 同步调度总流程(协议 §12):释放过期 held → push 循环 → pull 循环。全程单飞。
// 与 android/.../data/sync/SyncEngine.kt 行为一致。

protocol SyncDto: Decodable {
    var id: String { get }
    var clientUpdatedAt: String { get }
    var deviceId: String { get }
    var updatedAt: String? { get }
}

extension BabyDto: SyncDto {}
extension ActivityTypeDto: SyncDto {}
extension EventDto: SyncDto {}
extension EventAttachmentDto: SyncDto {}

actor SyncEngine {
    private let db: AppDb
    private let supa: SupaClient
    private let nowMillis: () -> Int64
    private var current: Task<Void, Error>?

    init(
        db: AppDb, supa: SupaClient,
        now: @escaping () -> Int64 = { Int64(Date().timeIntervalSince1970 * 1000) }
    ) {
        self.db = db
        self.supa = supa
        self.nowMillis = now
    }

    /// 单飞:并发调用合并到同一次同步
    func sync() async throws {
        if let t = current {
            try await t.value
            return
        }
        let t = Task { try await self.doSync() }
        current = t
        defer { current = nil }
        try await t.value
    }

    /// 断网恢复后首个请求常失败,带退避重试;最终失败静默(下次触发再试)
    func syncWithRetry(attempts: Int = 3) async {
        for attempt in 1...attempts {
            do {
                try await sync()
                return
            } catch {
                if attempt == attempts { return }
                try? await Task.sleep(nanoseconds: UInt64(attempt) * 2_000_000_000)
            }
        }
    }

    private func doSync() async throws {
        let now = nowMillis()
        try await db.dbQueue.write { db in
            try db.execute(
                sql: "UPDATE outbox SET state = 'pending', holdUntil = NULL WHERE state = 'held' AND holdUntil <= ?",
                arguments: [now]
            )
        }
        for entity in SyncEntity.allCases { try await pushEntity(entity) }
        for entity in SyncEntity.allCases { try await pullEntity(entity) }
        // 成员资料只读缓存,失败不影响主同步
        try? await refreshFamilyMembers()
        notifyDbChanged()
    }

    /// 全量刷新成员缓存(RLS 限定本人家庭)
    private func refreshFamilyMembers() async throws {
        let data = try await supa.select("family_members", query: "select=*")
        let dtos = try Json.decoder.decode([FamilyMemberDto].self, from: data)
        try await db.dbQueue.write { db in
            try db.execute(sql: "DELETE FROM family_members")
            for dto in dtos { try dto.toRow().save(db) }
        }
    }

    // ---- push(协议 §4)----

    private func pushEntity(_ entity: SyncEntity) async throws {
        switch entity {
        case .babies:
            try await pushLoop(entity, BabyRow.self) { $0.toDto() }
        case .activityTypes:
            try await pushLoop(entity, ActivityTypeRow.self) { $0.toDto() }
        case .events:
            try await pushLoop(entity, EventRow.self) { $0.toDto() }
        case .eventAttachments:
            try await pushLoop(entity, EventAttachmentRow.self) { $0.toDto() }
        }
    }

    private struct PushBody<D: Encodable>: Encodable {
        let rows: [D]
    }

    private func pushLoop<R: LwwRecord, D: Encodable>(
        _ entity: SyncEntity, _ rowType: R.Type, toDto: @escaping (R) -> D
    ) async throws {
        while true {
            let now = nowMillis()
            let batch = try await db.dbQueue.write { db in
                try outboxEngine(entity, db: db, now: { now }).pushBegin()
            }
            if batch.isEmpty { return }

            // 同批同实体去重,一次 upsert;成功后 ack 其全部 opId(协议 §3)
            var order: [String] = []
            var opIdsByEntityId: [String: [Int64]] = [:]
            for item in batch {
                if opIdsByEntityId[item.entityId] == nil {
                    order.append(item.entityId)
                    opIdsByEntityId[item.entityId] = []
                }
                opIdsByEntityId[item.entityId]!.append(item.opId)
            }

            do {
                let rows: [R] = try await db.dbQueue.read { db in
                    try R.filter(keys: order).fetchAll(db)
                }
                let present = Set(rows.map(\.id))
                var results: [PushResultDto] = []
                if !rows.isEmpty {
                    let body = try Json.encoder.encode(PushBody(rows: rows.map(toDto)))
                    let data = try await supa.rpc("push_\(entity.table)", body: body)
                    results = try Json.decoder.decode([PushResultDto].self, from: data)
                }
                let ackNow = nowMillis()
                try await db.dbQueue.write { db in
                    let eng = outboxEngine(entity, db: db, now: { ackNow })
                    // 本地行已不存在的项(不应发生)直接 ack 丢弃
                    for id in order where !present.contains(id) {
                        try eng.pushAck(opIds: opIdsByEntityId[id]!)
                    }
                    for res in results {
                        guard let opIds = opIdsByEntityId[res.id] else { continue }
                        if res.outcome == "rejected", res.reason == "ongoing_conflict", entity == .events {
                            // ongoing_conflict 时纯本地标记删除,不入 outbox(协议 §4)
                            try db.execute(
                                sql: "UPDATE events SET deletedAt = ?, clientUpdatedAt = ? WHERE id = ?",
                                arguments: [ackNow, ackNow, res.id]
                            )
                        }
                        try eng.pushAck(opIds: opIds) // applied / stale / rejected 均视为已处理
                    }
                }
            } catch {
                let failNow = nowMillis()
                let opIds = batch.map(\.opId)
                try await db.dbQueue.write { db in
                    try outboxEngine(entity, db: db, now: { failNow }).pushFail(opIds: opIds)
                }
                throw error
            }
        }
    }

    // ---- pull(协议 §6 §9)----

    private func pullEntity(_ entity: SyncEntity) async throws {
        switch entity {
        case .babies:
            try await pullLoop(entity, BabyDto.self) { dto, _ in dto.toRow() }
        case .activityTypes:
            try await pullLoop(entity, ActivityTypeDto.self) { dto, _ in dto.toRow() }
        case .events:
            try await pullLoop(entity, EventDto.self) { dto, _ in dto.toRow() }
        case .eventAttachments:
            try await pullLoop(entity, EventAttachmentDto.self) { dto, db in
                // localPath 是本地专属字段,pull 覆盖时保留
                let localPath = try EventAttachmentRow.fetchOne(db, key: dto.id)?.localPath
                return dto.toRow(localPath: localPath)
            }
        }
    }

    private func pullLoop<D: SyncDto, R: LwwRecord>(
        _ entity: SyncEntity, _ dtoType: D.Type, toRow: @escaping (D, Database) throws -> R
    ) async throws {
        while true {
            let cursor = try await db.dbQueue.read { db in
                try SyncCursorRow.fetchOne(db, key: entity.table)
            }
            let ts = cursor?.lastUpdatedAt ?? "1970-01-01T00:00:00+00:00"
            let lastId = cursor?.lastId ?? "00000000-0000-0000-0000-000000000000"
            let e = UrlEnc.enc(ts)
            let query = "select=*"
                + "&or=(updated_at.gt.\(e),and(updated_at.eq.\(e),id.gt.\(lastId)))"
                + "&order=updated_at.asc,id.asc&limit=\(SyncProtocol.pullPageSize)"
            let data = try await supa.select(entity.table, query: query)
            let dtos = try Json.decoder.decode([D].self, from: data)

            let now = nowMillis()
            try await db.dbQueue.write { db in
                for dto in dtos {
                    try Self.applyRemote(entity, dto: dto, db: db, now: now, toRow: toRow)
                }
                if let last = dtos.last, let updatedAt = last.updatedAt {
                    try SyncCursorRow(entity: entity.table, lastUpdatedAt: updatedAt, lastId: last.id).save(db)
                }
            }
            if dtos.count < SyncProtocol.pullPageSize { return }
        }
    }

    /// 单行远端并入:本地不存在直接写;存在则 LWW,远端赢时清 outbox 并覆盖(协议 §6)
    private static func applyRemote<D: SyncDto, R: LwwRecord>(
        _ entity: SyncEntity, dto: D, db: Database, now: Int64,
        toRow: (D, Database) throws -> R
    ) throws {
        if let local = try Row.fetchOne(
            db,
            sql: "SELECT clientUpdatedAt, deviceId FROM \(entity.table) WHERE id = ?",
            arguments: [dto.id]
        ) {
            let a = LwwVersion(clientUpdatedAt: local["clientUpdatedAt"], deviceId: local["deviceId"])
            let b = LwwVersion(clientUpdatedAt: IsoTime.toMillis(dto.clientUpdatedAt), deviceId: dto.deviceId)
            guard Lww.decide(a: a, b: b) == .bWins else { return }
            try outboxEngine(entity, db: db, now: { now }).pullRemoteWins(entityId: dto.id)
        }
        try toRow(dto, db).save(db)
    }
}
