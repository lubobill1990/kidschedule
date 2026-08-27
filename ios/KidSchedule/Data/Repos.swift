import Foundation
import GRDB

// 本地写入口。所有写:先写本地行,再走 OutboxEngine(协议 §3 §5),同一事务。

func nowEpochMillis() -> Int64 { Int64(Date().timeIntervalSince1970 * 1000) }

final class RecordRepo {
    private let db: AppDb
    private let deviceId: String
    private let now: () -> Int64

    init(db: AppDb, deviceId: String, now: @escaping () -> Int64 = nowEpochMillis) {
        self.db = db
        self.deviceId = deviceId
        self.now = now
    }

    /// 瞬时行为一键记录(带撤销窗口),返回事件 id
    func quickRecordInstant(familyId: String, babyId: String, typeId: String) async throws -> String {
        let id = UUID().uuidString.lowercased()
        let t = now()
        let deviceId = deviceId
        try await db.dbQueue.write { db in
            try EventRow(
                id: id, familyId: familyId, babyId: babyId, activityTypeId: typeId,
                startedAt: t, endedAt: t, status: "done", autoEnded: false,
                note: nil, createdBy: nil, deletedAt: nil,
                clientUpdatedAt: t, deviceId: deviceId
            ).save(db)
            try outboxEngine(.events, db: db, now: { t }).quickRecord(entityId: id, nowMillis: t)
        }
        notifyDbChanged()
        return id
    }

    /// 持续行为一键开始(带撤销窗口);本地已有 ongoing 返回 nil(协议 §7)
    func quickStartDuration(familyId: String, babyId: String, typeId: String) async throws -> String? {
        let id = UUID().uuidString.lowercased()
        let t = now()
        let deviceId = deviceId
        let created: Bool = try await db.dbQueue.write { db in
            let ongoing = try EventRow
                .filter(Column("babyId") == babyId && Column("activityTypeId") == typeId)
                .filter(Column("status") == "ongoing" && Column("deletedAt") == nil)
                .fetchOne(db)
            if ongoing != nil { return false }
            try EventRow(
                id: id, familyId: familyId, babyId: babyId, activityTypeId: typeId,
                startedAt: t, endedAt: nil, status: "ongoing", autoEnded: false,
                note: nil, createdBy: nil, deletedAt: nil,
                clientUpdatedAt: t, deviceId: deviceId
            ).save(db)
            try outboxEngine(.events, db: db, now: { t }).quickRecord(entityId: id, nowMillis: t)
            return true
        }
        notifyDbChanged()
        return created ? id : nil
    }

    /// 撤销窗口内撤销:物理删本地行,永不上行(协议 §5)
    func undo(eventId: String) async throws -> Bool {
        let t = now()
        let ok = try await db.dbQueue.write { db in
            try outboxEngine(.events, db: db, now: { t }).undo(entityId: eventId) == .ok
        }
        notifyDbChanged()
        return ok
    }

    func endDuration(eventId: String, endedAtMillis: Int64? = nil, autoEnded: Bool = false) async throws {
        let t = now()
        try await db.dbQueue.write { db in
            guard var e = try EventRow.fetchOne(db, key: eventId), e.status != "done" else { return }
            e.status = "done"
            e.endedAt = endedAtMillis ?? t
            e.autoEnded = autoEnded
            e.clientUpdatedAt = t
            try e.save(db)
            try outboxEngine(.events, db: db, now: { t }).normalWrite(entityId: eventId, nowMillis: t)
        }
        notifyDbChanged()
    }

    /// 补录历史记录:直接进同步,无撤销窗口
    func backfill(
        familyId: String, babyId: String, typeId: String,
        startedAt: Int64, endedAt: Int64?, note: String?
    ) async throws -> String {
        let id = UUID().uuidString.lowercased()
        let t = now()
        let deviceId = deviceId
        try await db.dbQueue.write { db in
            try EventRow(
                id: id, familyId: familyId, babyId: babyId, activityTypeId: typeId,
                startedAt: startedAt, endedAt: endedAt ?? startedAt, status: "done",
                autoEnded: false, note: note, createdBy: nil, deletedAt: nil,
                clientUpdatedAt: t, deviceId: deviceId
            ).save(db)
            try outboxEngine(.events, db: db, now: { t }).normalWrite(entityId: id, nowMillis: t)
        }
        notifyDbChanged()
        return id
    }

    /// 编辑(补录/修正/改备注);整行覆盖并 bump client_updated_at
    func update(event: EventRow) async throws {
        var e = event
        let t = now()
        e.clientUpdatedAt = t
        e.deviceId = deviceId
        try await db.dbQueue.write { db in
            try e.save(db)
            try outboxEngine(.events, db: db, now: { t }).normalWrite(entityId: e.id, nowMillis: t)
        }
        notifyDbChanged()
    }

    func softDelete(eventId: String) async throws {
        let t = now()
        let deviceId = deviceId
        try await db.dbQueue.write { db in
            guard var e = try EventRow.fetchOne(db, key: eventId) else { return }
            e.deletedAt = t
            e.clientUpdatedAt = t
            e.deviceId = deviceId
            try e.save(db)
            try outboxEngine(.events, db: db, now: { t }).normalWrite(entityId: eventId, nowMillis: t)
        }
        notifyDbChanged()
    }

    /// 扫描超时未结束的 ongoing,置 auto_ended(协议 §8);返回被自动结束的事件 id
    @discardableResult
    func autoEndOverdue() async throws -> [String] {
        let t = now()
        let ended: [String] = try await db.dbQueue.write { db in
            var ended: [String] = []
            let ongoing = try EventRow
                .filter(Column("status") == "ongoing" && Column("deletedAt") == nil)
                .fetchAll(db)
            for var e in ongoing {
                guard let maxSec = try ActivityTypeRow.fetchOne(db, key: e.activityTypeId)?.defaultMaxDurationSec
                else { continue }
                let deadline = e.startedAt + maxSec * 1000
                if t >= deadline {
                    e.status = "done"
                    e.endedAt = deadline
                    e.autoEnded = true
                    e.clientUpdatedAt = t
                    try e.save(db)
                    try outboxEngine(.events, db: db, now: { t }).normalWrite(entityId: e.id, nowMillis: t)
                    ended.append(e.id)
                }
            }
            return ended
        }
        if !ended.isEmpty { notifyDbChanged() }
        return ended
    }
}

// 宝宝与行为类型的本地写入口(无撤销窗口,normalWrite)
final class CatalogRepo {
    private let db: AppDb
    private let deviceId: String
    private let now: () -> Int64

    init(db: AppDb, deviceId: String, now: @escaping () -> Int64 = nowEpochMillis) {
        self.db = db
        self.deviceId = deviceId
        self.now = now
    }

    @discardableResult
    func addBaby(familyId: String, name: String, birthday: String?) async throws -> String {
        let id = UUID().uuidString.lowercased()
        let t = now()
        let deviceId = deviceId
        try await db.dbQueue.write { db in
            try BabyRow(
                id: id, familyId: familyId, name: name, birthday: birthday,
                avatarPath: nil, deletedAt: nil, clientUpdatedAt: t, deviceId: deviceId
            ).save(db)
            try outboxEngine(.babies, db: db, now: { t }).normalWrite(entityId: id, nowMillis: t)
        }
        notifyDbChanged()
        return id
    }

    func updateBaby(_ baby: BabyRow) async throws {
        var b = baby
        let t = now()
        b.clientUpdatedAt = t
        b.deviceId = deviceId
        try await db.dbQueue.write { db in
            try b.save(db)
            try outboxEngine(.babies, db: db, now: { t }).normalWrite(entityId: b.id, nowMillis: t)
        }
        notifyDbChanged()
    }

    @discardableResult
    func addActivityType(
        familyId: String, name: String, icon: String?, color: String?, kind: String,
        defaultMaxDurationSec: Int64? = nil, reminderMode: String = "off",
        reminderFixedIntervalSec: Int64? = nil, sortOrder: Int = 0
    ) async throws -> String {
        let id = UUID().uuidString.lowercased()
        let t = now()
        let deviceId = deviceId
        try await db.dbQueue.write { db in
            try ActivityTypeRow(
                id: id, familyId: familyId, name: name, icon: icon, color: color,
                kind: kind, defaultMaxDurationSec: defaultMaxDurationSec,
                reminderMode: reminderMode, reminderFixedIntervalSec: reminderFixedIntervalSec,
                sortOrder: sortOrder, deletedAt: nil, clientUpdatedAt: t, deviceId: deviceId
            ).save(db)
            try outboxEngine(.activityTypes, db: db, now: { t }).normalWrite(entityId: id, nowMillis: t)
        }
        notifyDbChanged()
        return id
    }

    func updateActivityType(_ type: ActivityTypeRow) async throws {
        var v = type
        let t = now()
        v.clientUpdatedAt = t
        v.deviceId = deviceId
        try await db.dbQueue.write { db in
            try v.save(db)
            try outboxEngine(.activityTypes, db: db, now: { t }).normalWrite(entityId: v.id, nowMillis: t)
        }
        notifyDbChanged()
    }

    /// 建家庭后播种默认行为类型
    func seedDefaultTypes(familyId: String) async throws {
        try await addActivityType(
            familyId: familyId, name: "喂奶", icon: "🍼", color: "#5B8DEF", kind: "duration",
            defaultMaxDurationSec: 45 * 60, reminderMode: "auto", sortOrder: 0
        )
        try await addActivityType(
            familyId: familyId, name: "辅食", icon: "🥣", color: "#F2A65A", kind: "instant", sortOrder: 1
        )
        try await addActivityType(
            familyId: familyId, name: "尿", icon: "💧", color: "#63C5DA", kind: "instant", sortOrder: 2
        )
        try await addActivityType(
            familyId: familyId, name: "便", icon: "💩", color: "#A9836F", kind: "instant", sortOrder: 3
        )
        try await addActivityType(
            familyId: familyId, name: "睡觉", icon: "😴", color: "#8E7CC3", kind: "duration",
            defaultMaxDurationSec: 5 * 3600, reminderMode: "off", sortOrder: 4
        )
    }
}

struct FamilyDto: Codable, Identifiable {
    var id: String
    var name: String
}

// 家庭/邀请均走 security definer RPC(迁移 0001)
final class FamilyRepo {
    private let supa: SupaClient

    init(supa: SupaClient) {
        self.supa = supa
    }

    var currentFamilyId: String? {
        get { UserDefaults.standard.string(forKey: "current_family_id") }
        set { UserDefaults.standard.set(newValue, forKey: "current_family_id") }
    }

    private func rpcString(_ fn: String, _ params: [String: Any]) async throws -> String {
        let body = try JSONSerialization.data(withJSONObject: params)
        let data = try await supa.rpc(fn, body: body)
        return try JSONDecoder().decode(String.self, from: data)
    }

    func createFamily(name: String, displayName: String?) async throws -> String {
        var params: [String: Any] = ["p_name": name]
        if let displayName { params["p_display_name"] = displayName }
        return try await rpcString("create_family", params)
    }

    func createInvite(familyId: String) async throws -> String {
        try await rpcString("create_invite", ["p_family_id": familyId])
    }

    func acceptInvite(code: String, displayName: String?) async throws -> String {
        var params: [String: Any] = ["p_code": code]
        if let displayName { params["p_display_name"] = displayName }
        return try await rpcString("accept_invite", params)
    }

    /// RLS 限定只返回本人所在家庭
    func myFamilies() async throws -> [FamilyDto] {
        let data = try await supa.select("families", query: "select=id,name")
        return try JSONDecoder().decode([FamilyDto].self, from: data)
    }
}
