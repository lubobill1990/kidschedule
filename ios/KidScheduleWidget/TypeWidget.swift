import AppIntents
import GRDB
import SwiftUI
import WidgetKit

// 单行为 widget:长按配置绑定某个行为(+宝宝),一键开始/结束/记录,显示上次时间与今日次数。

private func widgetDb() -> (familyId: String, db: AppDb)? {
    guard let familyId = AppGroup.defaults.string(forKey: "current_family_id"),
          let path = try? AppGroup.databasePath(),
          let db = try? AppDb(path: path)
    else { return nil }
    return (familyId, db)
}

struct WidgetTypeEntity: AppEntity {
    static let typeDisplayRepresentation: TypeDisplayRepresentation = "行为类型"
    static let defaultQuery = WidgetTypeQuery()

    var id: String
    var name: String
    var icon: String?
    var babyId: String?
    var babyName: String?

    var displayRepresentation: DisplayRepresentation {
        let scope = babyName.map { "(\($0))" } ?? ""
        return DisplayRepresentation(title: "\(icon ?? "") \(name)\(scope)")
    }
}

struct WidgetTypeQuery: EntityQuery {
    func entities(for identifiers: [String]) async throws -> [WidgetTypeEntity] {
        try await all().filter { identifiers.contains($0.id) }
    }

    func suggestedEntities() async throws -> [WidgetTypeEntity] {
        try await all()
    }

    func defaultResult() async -> WidgetTypeEntity? {
        try? await all().first
    }

    private func all() async throws -> [WidgetTypeEntity] {
        guard let (familyId, db) = widgetDb() else { return [] }
        return try await db.dbQueue.read { dbc in
            let babies = try BabyRow
                .filter(GRDB.Column("familyId") == familyId && GRDB.Column("deletedAt") == nil)
                .fetchAll(dbc)
            let names = Dictionary(uniqueKeysWithValues: babies.map { ($0.id, $0.name) })
            return try ActivityTypeRow
                .filter(GRDB.Column("familyId") == familyId && GRDB.Column("deletedAt") == nil)
                .order(GRDB.Column("sortOrder"), GRDB.Column("name"))
                .fetchAll(dbc)
                .map { t in
                    WidgetTypeEntity(
                        id: t.id, name: t.name, icon: t.icon,
                        babyId: t.babyId, babyName: t.babyId.flatMap { names[$0] }
                    )
                }
        }
    }
}

struct WidgetBabyEntity: AppEntity {
    static let typeDisplayRepresentation: TypeDisplayRepresentation = "宝宝"
    static let defaultQuery = WidgetBabyQuery()

    var id: String
    var name: String

    var displayRepresentation: DisplayRepresentation {
        DisplayRepresentation(title: "\(name)")
    }
}

struct WidgetBabyQuery: EntityQuery {
    func entities(for identifiers: [String]) async throws -> [WidgetBabyEntity] {
        try await all().filter { identifiers.contains($0.id) }
    }

    func suggestedEntities() async throws -> [WidgetBabyEntity] {
        try await all()
    }

    private func all() async throws -> [WidgetBabyEntity] {
        guard let (familyId, db) = widgetDb() else { return [] }
        return try await db.dbQueue.read { dbc in
            try BabyRow
                .filter(GRDB.Column("familyId") == familyId && GRDB.Column("deletedAt") == nil)
                .order(GRDB.Column("name"))
                .fetchAll(dbc)
                .map { WidgetBabyEntity(id: $0.id, name: $0.name) }
        }
    }
}

struct TypeWidgetConfigIntent: WidgetConfigurationIntent {
    static let title: LocalizedStringResource = "单行为记录"
    static let description = IntentDescription("绑定一个行为,一键开始/结束并查看摘要。")

    @Parameter(title: "行为")
    var type: WidgetTypeEntity?

    @Parameter(title: "宝宝(默认跟随 App 选中)")
    var baby: WidgetBabyEntity?
}

struct TypeRecordIntent: AppIntent {
    static let title: LocalizedStringResource = "单行为一键记录"

    @Parameter(title: "行为类型")
    var typeId: String

    @Parameter(title: "宝宝")
    var babyId: String

    init() {}

    init(typeId: String, babyId: String) {
        self.typeId = typeId
        self.babyId = babyId
    }

    func perform() async throws -> some IntentResult {
        defer { WidgetCenter.shared.reloadAllTimelines() }
        guard let (familyId, db) = widgetDb() else { return .result() }
        let typeId = typeId
        let babyId = babyId
        guard let type = try await db.dbQueue.read({ try ActivityTypeRow.fetchOne($0, key: typeId) })
        else { return .result() }

        let repo = RecordRepo(db: db, deviceId: DeviceId.get())
        if type.kind == "duration" {
            let ongoing = try await db.dbQueue.read { dbc in
                try EventRow
                    .filter(GRDB.Column("babyId") == babyId && GRDB.Column("activityTypeId") == typeId)
                    .filter(GRDB.Column("status") == "ongoing" && GRDB.Column("deletedAt") == nil)
                    .fetchOne(dbc)
            }
            if let ongoing {
                try await repo.endDuration(eventId: ongoing.id)
                return .result()
            }
            if let eventId = try await repo.quickStartDuration(
                familyId: familyId, babyId: babyId, typeId: typeId
            ) {
                WidgetUndoStore.save(WidgetUndoState(
                    eventId: eventId,
                    typeName: "开始\(type.name)",
                    deadlineMillis: nowEpochMillis() + Int64(SyncProtocol.undoWindowSec) * 1000,
                    typeId: typeId
                ))
            }
        } else {
            let eventId = try await repo.quickRecordInstant(
                familyId: familyId, babyId: babyId, typeId: typeId
            )
            WidgetUndoStore.save(WidgetUndoState(
                eventId: eventId,
                typeName: type.name,
                deadlineMillis: nowEpochMillis() + Int64(SyncProtocol.undoWindowSec) * 1000,
                typeId: typeId
            ))
        }
        return .result()
    }
}

struct TypeEntry: TimelineEntry {
    let date: Date
    let type: WidgetType?
    let kind: String
    let babyId: String?
    let babyName: String
    let ongoingStart: Int64?
    let last: Int64?
    let todayCount: Int
    let undo: WidgetUndoState?
}

struct TypeProvider: AppIntentTimelineProvider {
    func placeholder(in context: Context) -> TypeEntry {
        TypeEntry(
            date: .now,
            type: WidgetType(id: "1", name: "喂奶", icon: "🍼", color: "#5B8DEF"),
            kind: "duration", babyId: nil, babyName: "宝宝",
            ongoingStart: nil, last: nowEpochMillis() - 3 * 3600 * 1000,
            todayCount: 3, undo: nil
        )
    }

    func snapshot(for configuration: TypeWidgetConfigIntent, in context: Context) async -> TypeEntry {
        await loadEntry(configuration)
    }

    func timeline(
        for configuration: TypeWidgetConfigIntent, in context: Context
    ) async -> Timeline<TypeEntry> {
        let entry = await loadEntry(configuration)
        if let undo = entry.undo {
            // 撤销窗口到期时自动切回开始/结束按钮
            let deadline = Date(timeIntervalSince1970: Double(undo.deadlineMillis) / 1000)
            let after = TypeEntry(
                date: deadline, type: entry.type, kind: entry.kind,
                babyId: entry.babyId, babyName: entry.babyName,
                ongoingStart: entry.ongoingStart, last: entry.last,
                todayCount: entry.todayCount, undo: nil
            )
            return Timeline(entries: [entry, after], policy: .atEnd)
        }
        return Timeline(entries: [entry], policy: .after(.now.addingTimeInterval(1800)))
    }

    private func loadEntry(_ config: TypeWidgetConfigIntent) async -> TypeEntry {
        let empty = TypeEntry(
            date: .now, type: nil, kind: "instant", babyId: nil, babyName: "",
            ongoingStart: nil, last: nil, todayCount: 0, undo: nil
        )
        guard let (familyId, db) = widgetDb(), let picked = config.type else { return empty }
        let t = nowEpochMillis()
        // 顺手释放已过期的撤销 hold,让记录在下次 app 同步时上行
        try? await db.dbQueue.write { dbc in
            for entity in SyncEntity.allCases {
                try outboxEngine(entity, db: dbc, now: { t }).releaseExpiredHolds(nowMillis: t)
            }
        }
        let configBabyId = config.baby?.id
        let selected = AppGroup.defaults.string(forKey: "selected_baby_id")
        let loaded: TypeEntry? = try? await db.dbQueue.read { dbc in
            guard let type = try ActivityTypeRow.fetchOne(dbc, key: picked.id),
                  type.deletedAt == nil
            else { return nil }
            let babies = try BabyRow
                .filter(GRDB.Column("familyId") == familyId && GRDB.Column("deletedAt") == nil)
                .order(GRDB.Column("name"))
                .fetchAll(dbc)
            // 专属行为绑定其宝宝;通用行为用配置的宝宝,否则跟随 app 选中(失效回退第一个)
            let baby = babies.first { $0.id == type.babyId }
                ?? babies.first { $0.id == configBabyId }
                ?? babies.first { $0.id == selected }
                ?? babies.first
            guard let baby else { return nil }
            let last = try EventRow
                .filter(GRDB.Column("babyId") == baby.id && GRDB.Column("activityTypeId") == type.id)
                .filter(GRDB.Column("deletedAt") == nil)
                .order(GRDB.Column("startedAt").desc)
                .fetchOne(dbc)
            let startOfToday = Int64(Calendar.current.startOfDay(for: .now).timeIntervalSince1970 * 1000)
            let todayCount = try EventRow
                .filter(GRDB.Column("babyId") == baby.id && GRDB.Column("activityTypeId") == type.id)
                .filter(GRDB.Column("deletedAt") == nil && GRDB.Column("startedAt") >= startOfToday)
                .fetchCount(dbc)
            let undo = WidgetUndoStore.load(now: t)
            return TypeEntry(
                date: .now,
                type: WidgetType(id: type.id, name: type.name, icon: type.icon, color: type.color),
                kind: type.kind,
                babyId: baby.id,
                babyName: baby.name,
                ongoingStart: last?.status == "ongoing" ? last?.startedAt : nil,
                last: last?.startedAt,
                todayCount: todayCount,
                undo: undo?.typeId == type.id ? undo : nil
            )
        }
        return loaded ?? empty
    }
}

struct SingleTypeWidget: Widget {
    var body: some WidgetConfiguration {
        AppIntentConfiguration(
            kind: "SingleTypeRecord",
            intent: TypeWidgetConfigIntent.self,
            provider: TypeProvider()
        ) { entry in
            SingleTypeView(entry: entry)
                .containerBackground(.fill.tertiary, for: .widget)
        }
        .configurationDisplayName("单行为记录")
        .description("绑定一个行为:一键开始/结束,显示上次时间与今日次数。")
        .supportedFamilies([.systemSmall])
    }
}

struct SingleTypeView: View {
    let entry: TypeEntry

    var body: some View {
        if let type = entry.type {
            content(type)
        } else {
            VStack(spacing: 4) {
                Text("单行为记录")
                    .font(.subheadline.bold())
                Text("长按 widget 编辑,选择要绑定的行为")
                    .font(.caption2)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
            }
        }
    }

    private func content(_ type: WidgetType) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            HStack(spacing: 4) {
                Text(type.icon ?? "•")
                    .font(.system(size: 20))
                Text(type.name)
                    .font(.subheadline.bold())
                    .lineLimit(1)
            }
            Text(entry.babyName)
                .font(.caption2)
                .foregroundStyle(.secondary)
            Spacer(minLength: 4)
            if let undo = entry.undo {
                Button(intent: UndoRecordIntent()) {
                    HStack(spacing: 4) {
                        Text("撤销")
                        Text(
                            Date(timeIntervalSince1970: Double(undo.deadlineMillis) / 1000),
                            style: .timer
                        )
                        .monospacedDigit()
                    }
                    .font(.subheadline.bold())
                    .frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                .tint(.red)
            } else if let babyId = entry.babyId {
                Button(intent: TypeRecordIntent(typeId: type.id, babyId: babyId)) {
                    Text(buttonLabel)
                        .font(.subheadline.bold())
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                .tint(widgetColor(type.color))
            }
            Text(summary)
                .font(.caption2)
                .foregroundStyle(.secondary)
                .lineLimit(1)
                .minimumScaleFactor(0.8)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading)
    }

    private var buttonLabel: String {
        if entry.kind != "duration" { return "记录" }
        return entry.ongoingStart != nil ? "结束" : "开始"
    }

    private var summary: String {
        var line: String
        if let start = entry.ongoingStart {
            line = "进行中 · \(formatMillis(start)) 开始"
        } else if let last = entry.last {
            line = "上次 \(formatMillis(last))"
        } else {
            line = "还没有记录"
        }
        if entry.todayCount > 0 {
            line += " · 今天 \(entry.todayCount) 次"
        }
        return line
    }

    private func formatMillis(_ millis: Int64) -> String {
        let date = Date(timeIntervalSince1970: Double(millis) / 1000)
        let fmt = DateFormatter()
        fmt.dateFormat = Calendar.current.isDateInToday(date) ? "HH:mm" : "M/d HH:mm"
        return fmt.string(from: date)
    }
}
