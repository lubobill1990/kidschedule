import GRDB
import SwiftUI
import WidgetKit

@main
struct KidScheduleWidgetBundle: WidgetBundle {
    var body: some Widget {
        QuickRecordWidget()
    }
}

struct WidgetType: Identifiable {
    let id: String
    let name: String
    let icon: String?
    let color: String?
}

struct QuickEntry: TimelineEntry {
    let date: Date
    let types: [WidgetType]
    let undo: WidgetUndoState?
}

struct QuickProvider: TimelineProvider {
    func placeholder(in context: Context) -> QuickEntry {
        QuickEntry(
            date: .now,
            types: [
                WidgetType(id: "1", name: "喂奶", icon: "🍼", color: "#5B8DEF"),
                WidgetType(id: "2", name: "尿", icon: "💧", color: "#63C5DA"),
                WidgetType(id: "3", name: "便", icon: "💩", color: "#A9836F"),
                WidgetType(id: "4", name: "睡觉", icon: "😴", color: "#8E7CC3"),
            ],
            undo: nil
        )
    }

    func getSnapshot(in context: Context, completion: @escaping (QuickEntry) -> Void) {
        completion(loadEntry())
    }

    func getTimeline(in context: Context, completion: @escaping (Timeline<QuickEntry>) -> Void) {
        let entry = loadEntry()
        if let undo = entry.undo {
            // 撤销窗口到期时自动切回按钮面板
            let deadline = Date(timeIntervalSince1970: Double(undo.deadlineMillis) / 1000)
            let after = QuickEntry(date: deadline, types: entry.types, undo: nil)
            completion(Timeline(entries: [entry, after], policy: .atEnd))
        } else {
            completion(Timeline(entries: [entry], policy: .after(.now.addingTimeInterval(1800))))
        }
    }

    private func loadEntry() -> QuickEntry {
        guard let familyId = AppGroup.defaults.string(forKey: "current_family_id"),
              let path = try? AppGroup.databasePath(),
              let db = try? AppDb(path: path)
        else {
            return QuickEntry(date: .now, types: [], undo: nil)
        }
        let t = nowEpochMillis()
        // 顺手释放已过期的撤销 hold,让记录在下次 app 同步时上行
        try? db.dbQueue.write { dbc in
            for entity in SyncEntity.allCases {
                try outboxEngine(entity, db: dbc, now: { t }).releaseExpiredHolds(nowMillis: t)
            }
        }
        let types: [ActivityTypeRow] = (try? db.dbQueue.read { dbc in
            try ActivityTypeRow
                .filter(Column("familyId") == familyId && Column("deletedAt") == nil)
                .order(Column("sortOrder"), Column("name"))
                .limit(8)
                .fetchAll(dbc)
        }) ?? []
        return QuickEntry(
            date: .now,
            types: types.map { WidgetType(id: $0.id, name: $0.name, icon: $0.icon, color: $0.color) },
            undo: WidgetUndoStore.load(now: t)
        )
    }
}

struct QuickRecordWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: "QuickRecord", provider: QuickProvider()) { entry in
            QuickRecordView(entry: entry)
                .containerBackground(.fill.tertiary, for: .widget)
        }
        .configurationDisplayName("一键记录")
        .description("不解锁进 app,直接记录宝宝行为,10 秒内可撤销。")
        .supportedFamilies([.systemSmall, .systemMedium])
    }
}

struct QuickRecordView: View {
    @Environment(\.widgetFamily) private var family
    let entry: QuickEntry

    var body: some View {
        if let undo = entry.undo {
            undoView(undo)
        } else if entry.types.isEmpty {
            VStack(spacing: 4) {
                Text("亲选带娃记")
                    .font(.subheadline.bold())
                Text("打开 App 登录后使用")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        } else {
            grid
        }
    }

    private var grid: some View {
        let cols = family == .systemSmall ? 2 : 4
        let shown = Array(entry.types.prefix(cols * 2))
        return LazyVGrid(
            columns: Array(repeating: GridItem(.flexible(), spacing: 6), count: cols),
            spacing: 6
        ) {
            ForEach(shown) { type in
                Button(intent: QuickRecordIntent(typeId: type.id)) {
                    VStack(spacing: 2) {
                        Text(type.icon ?? "•")
                            .font(.system(size: 22))
                        Text(type.name)
                            .font(.system(size: 10))
                            .lineLimit(1)
                            .foregroundStyle(.primary)
                    }
                    .frame(maxWidth: .infinity, minHeight: 52)
                    .background(
                        RoundedRectangle(cornerRadius: 10)
                            .fill(widgetColor(type.color).opacity(0.22))
                    )
                }
                .buttonStyle(.plain)
            }
        }
    }

    private func undoView(_ undo: WidgetUndoState) -> some View {
        VStack(spacing: 6) {
            Text("已记录 \(undo.typeName)")
                .font(.subheadline.bold())
                .lineLimit(1)
            Text(
                Date(timeIntervalSince1970: Double(undo.deadlineMillis) / 1000),
                style: .timer
            )
            .font(.title3.monospacedDigit())
            .multilineTextAlignment(.center)
            .foregroundStyle(.secondary)
            Button(intent: UndoRecordIntent()) {
                Text("撤销")
                    .font(.subheadline.bold())
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
            .tint(.red)
        }
    }
}

private func widgetColor(_ hex: String?) -> Color {
    guard let hex, hex.hasPrefix("#"), hex.count == 7,
          let v = UInt32(hex.dropFirst(), radix: 16)
    else { return .gray }
    return Color(
        red: Double((v >> 16) & 0xFF) / 255.0,
        green: Double((v >> 8) & 0xFF) / 255.0,
        blue: Double(v & 0xFF) / 255.0
    )
}
