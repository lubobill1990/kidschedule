import AppIntents
import Foundation
import GRDB
import WidgetKit

// widget 一键记录/撤销:AppIntent 在 widget 进程内直写 App Group 共享库(协议 §5 撤销窗口)。

struct WidgetUndoState: Codable {
    var eventId: String
    var typeName: String
    var deadlineMillis: Int64
}

enum WidgetUndoStore {
    private static let key = "widget_undo_state"

    static func load(now: Int64 = nowEpochMillis()) -> WidgetUndoState? {
        guard let data = AppGroup.defaults.data(forKey: key),
              let s = try? JSONDecoder().decode(WidgetUndoState.self, from: data),
              s.deadlineMillis > now
        else { return nil }
        return s
    }

    static func save(_ s: WidgetUndoState) {
        if let data = try? JSONEncoder().encode(s) {
            AppGroup.defaults.set(data, forKey: key)
        }
    }

    static func clear() {
        AppGroup.defaults.removeObject(forKey: key)
    }
}

struct QuickRecordIntent: AppIntent {
    static let title: LocalizedStringResource = "一键记录"

    @Parameter(title: "行为类型")
    var typeId: String

    init() {}

    init(typeId: String) {
        self.typeId = typeId
    }

    func perform() async throws -> some IntentResult {
        guard let familyId = AppGroup.defaults.string(forKey: "current_family_id") else {
            return .result()
        }
        let db = try AppDb(path: AppGroup.databasePath())
        let selected = AppGroup.defaults.string(forKey: "selected_baby_id")
        let typeId = typeId
        let pick: (babyId: String, type: ActivityTypeRow)? = try await db.dbQueue.read { dbc in
            let babies = try BabyRow
                .filter(GRDB.Column("familyId") == familyId && GRDB.Column("deletedAt") == nil)
                .order(GRDB.Column("name"))
                .fetchAll(dbc)
            guard let baby = babies.first(where: { $0.id == selected }) ?? babies.first,
                  let type = try ActivityTypeRow.fetchOne(dbc, key: typeId)
            else { return nil }
            return (baby.id, type)
        }
        guard let pick else { return .result() }

        let repo = RecordRepo(db: db, deviceId: DeviceId.get())
        let eventId: String?
        if pick.type.kind == "duration" {
            eventId = try await repo.quickStartDuration(
                familyId: familyId, babyId: pick.babyId, typeId: pick.type.id
            )
        } else {
            eventId = try await repo.quickRecordInstant(
                familyId: familyId, babyId: pick.babyId, typeId: pick.type.id
            )
        }
        if let eventId {
            WidgetUndoStore.save(WidgetUndoState(
                eventId: eventId,
                typeName: pick.type.name,
                deadlineMillis: nowEpochMillis() + Int64(SyncProtocol.undoWindowSec) * 1000
            ))
        }
        return .result()
    }
}

struct UndoRecordIntent: AppIntent {
    static let title: LocalizedStringResource = "撤销记录"

    func perform() async throws -> some IntentResult {
        if let s = WidgetUndoStore.load() {
            let db = try AppDb(path: AppGroup.databasePath())
            let repo = RecordRepo(db: db, deviceId: DeviceId.get())
            _ = try await repo.undo(eventId: s.eventId)
        }
        WidgetUndoStore.clear()
        return .result()
    }
}
