import Foundation
import GRDB

// 本地 SQLite,schema 与 android/.../data/local/Entities.kt 对应。

final class AppDb {
    let dbQueue: DatabaseQueue

    init(path: String) throws {
        dbQueue = try DatabaseQueue(path: path)
        try Self.migrator.migrate(dbQueue)
    }

    static func defaultPath() throws -> String {
        try AppGroup.databasePath()
    }

    static var migrator: DatabaseMigrator {
        var m = DatabaseMigrator()
        m.registerMigration("v1") { db in
            try db.create(table: "babies") { t in
                t.primaryKey("id", .text)
                t.column("familyId", .text).notNull()
                t.column("name", .text).notNull()
                t.column("birthday", .text)
                t.column("avatarPath", .text)
                t.column("deletedAt", .integer)
                t.column("clientUpdatedAt", .integer).notNull()
                t.column("deviceId", .text).notNull()
            }
            try db.create(table: "activity_types") { t in
                t.primaryKey("id", .text)
                t.column("familyId", .text).notNull()
                t.column("name", .text).notNull()
                t.column("icon", .text)
                t.column("color", .text)
                t.column("kind", .text).notNull()
                t.column("defaultMaxDurationSec", .integer)
                t.column("reminderMode", .text).notNull()
                t.column("reminderFixedIntervalSec", .integer)
                t.column("sortOrder", .integer).notNull()
                t.column("deletedAt", .integer)
                t.column("clientUpdatedAt", .integer).notNull()
                t.column("deviceId", .text).notNull()
            }
            try db.create(table: "events") { t in
                t.primaryKey("id", .text)
                t.column("familyId", .text).notNull()
                t.column("babyId", .text).notNull()
                t.column("activityTypeId", .text).notNull()
                t.column("startedAt", .integer).notNull()
                t.column("endedAt", .integer)
                t.column("status", .text).notNull()
                t.column("autoEnded", .boolean).notNull()
                t.column("note", .text)
                t.column("createdBy", .text)
                t.column("deletedAt", .integer)
                t.column("clientUpdatedAt", .integer).notNull()
                t.column("deviceId", .text).notNull()
            }
            try db.create(
                index: "idx_events_baby_type_started", on: "events",
                columns: ["babyId", "activityTypeId", "startedAt"]
            )
            try db.create(index: "idx_events_baby_started", on: "events", columns: ["babyId", "startedAt"])
            try db.create(table: "event_attachments") { t in
                t.primaryKey("id", .text)
                t.column("eventId", .text).notNull()
                t.column("familyId", .text).notNull()
                t.column("storagePath", .text)
                t.column("uploadState", .text).notNull()
                t.column("localPath", .text)
                t.column("deletedAt", .integer)
                t.column("clientUpdatedAt", .integer).notNull()
                t.column("deviceId", .text).notNull()
            }
            try db.create(index: "idx_attachments_event", on: "event_attachments", columns: ["eventId"])
            try db.create(table: "outbox") { t in
                t.autoIncrementedPrimaryKey("opId")
                t.column("entity", .text).notNull()
                t.column("entityId", .text).notNull()
                t.column("state", .text).notNull()
                t.column("holdUntil", .integer)
                t.column("createdAt", .integer).notNull()
            }
            try db.create(index: "idx_outbox_entityId", on: "outbox", columns: ["entityId"])
            try db.create(index: "idx_outbox_state", on: "outbox", columns: ["state"])
            try db.create(table: "sync_cursors") { t in
                t.primaryKey("entity", .text)
                t.column("lastUpdatedAt", .text).notNull()
                t.column("lastId", .text).notNull()
            }
        }
        return m
    }
}

enum DeviceId {
    static func get() -> String {
        let key = "device_id"
        if let v = AppGroup.defaults.string(forKey: key) { return v }
        let v = UUID().uuidString.lowercased()
        AppGroup.defaults.set(v, forKey: key)
        return v
    }
}

extension Notification.Name {
    /// 本地库有变更(写入或同步拉取),UI 应重新加载
    static let dbChanged = Notification.Name("kidschedule.dbChanged")
}

func notifyDbChanged() {
    DispatchQueue.main.async {
        NotificationCenter.default.post(name: .dbChanged, object: nil)
    }
}
