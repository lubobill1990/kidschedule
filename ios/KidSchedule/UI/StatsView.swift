import SwiftUI
import Charts
import GRDB

struct StatsView: View {
    @EnvironmentObject private var env: AppEnv
    @Environment(\.dismiss) private var dismiss
    @ObservedObject var model: HomeModel

    @State private var monthStart = Self.startOfMonth(Date())
    @State private var selectedDate = Calendar.current.startOfDay(for: Date())
    @State private var filterTypeId: String?
    @State private var monthEvents: [EventRow] = []
    @State private var weekEvents: [EventRow] = []

    private var cal: Calendar { Calendar.current }

    private static func startOfMonth(_ d: Date) -> Date {
        let c = Calendar.current
        return c.date(from: c.dateComponents([.year, .month], from: d))!
    }

    private static let userPalette: [Color] = [
        Color(hex: "#5B8DEF"), Color(hex: "#F2A65A"), Color(hex: "#63C5DA"),
        Color(hex: "#8E7CC3"), Color(hex: "#7BC67E"), Color(hex: "#A9836F"),
    ]

    // ---- 派生数据 ----

    private var eventsByDay: [Date: [EventRow]] {
        Dictionary(grouping: monthEvents) {
            cal.startOfDay(for: TimeFmt.date($0.startedAt))
        }
    }

    private var dayEvents: [EventRow] {
        (eventsByDay[selectedDate] ?? []).sorted { $0.startedAt > $1.startedAt }
    }

    private var weekDays: [Date] {
        (0..<7).reversed().map { cal.date(byAdding: .day, value: -$0, to: selectedDate)! }
    }

    private var weekFiltered: [EventRow] {
        weekEvents.filter { filterTypeId == nil || $0.activityTypeId == filterTypeId }
    }

    /// 本地新建行 createdBy 为空 → 归到当前用户
    private func userKey(_ e: EventRow) -> String {
        e.createdBy ?? model.myUserId ?? ""
    }

    private var userOrder: [String] {
        let fromEvents = weekFiltered.map { userKey($0) }.reduce(into: [String]()) {
            if !$0.contains($1) { $0.append($1) }
        }
        var order = model.members.map(\.userId).filter { fromEvents.contains($0) }
        for u in fromEvents where !order.contains(u) { order.append(u) }
        return order
    }

    private func userColor(_ user: String) -> Color {
        let idx = userOrder.firstIndex(of: user) ?? 0
        return Self.userPalette[idx % Self.userPalette.count]
    }

    private func userLabel(_ user: String) -> String {
        let m = model.membersById[user]
        return "\(m?.avatarEmoji ?? "👤")\(m?.displayName ?? "")"
    }

    private struct BarPoint: Identifiable {
        let id: String
        let day: Date
        let user: String
        let count: Int
    }

    private var barPoints: [BarPoint] {
        var counts: [Date: [String: Int]] = [:]
        for e in weekFiltered {
            let day = cal.startOfDay(for: TimeFmt.date(e.startedAt))
            counts[day, default: [:]][userKey(e), default: 0] += 1
        }
        var points: [BarPoint] = []
        for day in weekDays {
            for user in userOrder {
                if let c = counts[day]?[user], c > 0 {
                    points.append(BarPoint(id: "\(day.timeIntervalSince1970)-\(user)", day: day, user: user, count: c))
                }
            }
        }
        return points
    }

    private var userTotals: [String: Int] {
        weekFiltered.reduce(into: [:]) { $0[userKey($1), default: 0] += 1 }
    }

    // ---- 视图 ----

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 12) {
                    monthHeader
                    monthGrid
                    weekSection
                    daySection
                }
                .padding(.horizontal)
            }
            .navigationTitle("统计")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("完成") { dismiss() }
                }
            }
            .task(id: monthKey) { await loadMonth() }
            .task(id: weekKey) { await loadWeek() }
        }
    }

    private var monthKey: String {
        "\(model.selectedBaby?.id ?? "")-\(monthStart.timeIntervalSince1970)"
    }

    private var weekKey: String {
        "\(model.selectedBaby?.id ?? "")-\(selectedDate.timeIntervalSince1970)"
    }

    private var monthHeader: some View {
        HStack {
            Button {
                monthStart = cal.date(byAdding: .month, value: -1, to: monthStart)!
            } label: { Image(systemName: "chevron.left") }
            Spacer()
            Text(monthStart.formatted(.dateTime.year().month()))
                .font(.headline)
            Spacer()
            Button {
                monthStart = cal.date(byAdding: .month, value: 1, to: monthStart)!
            } label: { Image(systemName: "chevron.right") }
            .disabled(monthStart >= Self.startOfMonth(Date()))
        }
        .padding(.top, 4)
    }

    private var monthGrid: some View {
        let daysInMonth = cal.range(of: .day, in: .month, for: monthStart)!.count
        // 周一为第一列
        let leadingBlanks = (cal.component(.weekday, from: monthStart) + 5) % 7
        let today = cal.startOfDay(for: Date())
        let eventDays = Set(eventsByDay.keys)
        let columns = Array(repeating: GridItem(.flexible()), count: 7)
        return VStack(spacing: 4) {
            LazyVGrid(columns: columns, spacing: 4) {
                ForEach(["一", "二", "三", "四", "五", "六", "日"], id: \.self) {
                    Text($0).font(.caption2).foregroundStyle(.secondary)
                }
                ForEach(0..<leadingBlanks, id: \.self) { _ in Text("") }
                ForEach(1...daysInMonth, id: \.self) { day in
                    let date = cal.date(byAdding: .day, value: day - 1, to: monthStart)!
                    let future = date > today
                    Button {
                        selectedDate = date
                    } label: {
                        VStack(spacing: 2) {
                            Text("\(day)")
                                .font(.footnote)
                                .foregroundStyle(future ? Color.secondary.opacity(0.4) : .primary)
                            Circle()
                                .fill(eventDays.contains(date) ? Color.accentColor : .clear)
                                .frame(width: 4, height: 4)
                        }
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 4)
                        .background(
                            Circle().fill(date == selectedDate ? Color.accentColor.opacity(0.18) : .clear)
                        )
                    }
                    .buttonStyle(.plain)
                    .disabled(future)
                }
            }
        }
    }

    private var weekSection: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("近 7 天 · 按记录人")
                .font(.subheadline.bold())
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    filterChip(nil, label: "全部")
                    ForEach(model.types) { t in
                        filterChip(t.id, label: "\(t.icon ?? "")\(t.name)")
                    }
                }
            }
            if weekFiltered.isEmpty {
                Text("该范围内没有记录")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            } else {
                Chart(barPoints) { p in
                    BarMark(
                        x: .value("日期", p.day, unit: .day),
                        y: .value("次数", p.count)
                    )
                    .foregroundStyle(userColor(p.user))
                    .cornerRadius(2)
                }
                .chartXAxis {
                    AxisMarks(values: weekDays) { _ in
                        AxisValueLabel(format: .dateTime.month(.defaultDigits).day(), centered: true)
                    }
                }
                .frame(height: 150)
                legend
            }
        }
    }

    private func filterChip(_ typeId: String?, label: String) -> some View {
        let selected = filterTypeId == typeId
        return Button {
            filterTypeId = typeId
        } label: {
            Text(label)
                .font(.footnote)
                .padding(.horizontal, 10)
                .padding(.vertical, 6)
                .background(
                    Capsule().fill(selected ? Color.accentColor.opacity(0.18) : Color(.systemGray6))
                )
        }
        .buttonStyle(.plain)
    }

    private var legend: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 12) {
                ForEach(userOrder, id: \.self) { u in
                    HStack(spacing: 4) {
                        Circle().fill(userColor(u)).frame(width: 8, height: 8)
                        Text("\(userLabel(u)) ×\(userTotals[u] ?? 0)")
                            .font(.caption)
                    }
                }
            }
        }
    }

    private var daySection: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(selectedDate.formatted(.dateTime.month().day()))
                .font(.subheadline.bold())
            if dayEvents.isEmpty {
                Text("当天没有记录")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            } else {
                daySummary
                ForEach(dayEvents) { e in
                    dayRow(e)
                }
            }
        }
        .padding(.bottom, 24)
    }

    private var daySummary: some View {
        let byType = Dictionary(grouping: dayEvents, by: \.activityTypeId)
        return VStack(alignment: .leading, spacing: 2) {
            ForEach(byType.keys.sorted(), id: \.self) { typeId in
                let list = byType[typeId]!
                let type = model.typesById[typeId]
                let durationSec = list
                    .filter { $0.status == "done" && $0.endedAt != nil }
                    .map { max(0, ($0.endedAt! - $0.startedAt) / 1000) }
                    .reduce(0, +)
                let suffix = (type?.kind == "duration" && durationSec > 0)
                    ? " 共\(TimeFmt.duration(durationSec))" : ""
                Text("\(type?.icon ?? "")\(type?.name ?? "未知") ×\(list.count)\(suffix)")
                    .font(.subheadline)
            }
        }
    }

    private func dayRow(_ e: EventRow) -> some View {
        let type = model.typesById[e.activityTypeId]
        let timeText: String
        if e.status == "ongoing" {
            timeText = "\(TimeFmt.clock(e.startedAt)) 起,进行中"
        } else if let ended = e.endedAt, ended != e.startedAt {
            timeText = "\(TimeFmt.clock(e.startedAt)) - \(TimeFmt.clock(ended))"
                + " (\(TimeFmt.duration((ended - e.startedAt) / 1000)))"
        } else {
            timeText = TimeFmt.clock(e.startedAt)
        }
        return HStack(spacing: 8) {
            Text(type?.icon ?? "·")
            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: 6) {
                    Text(type?.name ?? "未知").font(.subheadline)
                    if e.autoEnded {
                        Text("自动结束")
                            .font(.caption2)
                            .foregroundStyle(.orange)
                    }
                }
                Text(timeText).font(.caption).foregroundStyle(.secondary)
            }
            Spacer()
            if let note = e.note, !note.isEmpty {
                Text(note).font(.caption).foregroundStyle(.secondary).lineLimit(1)
            }
        }
        .padding(.vertical, 2)
    }

    // ---- 数据加载 ----

    private func loadMonth() async {
        guard let babyId = model.selectedBaby?.id else { return }
        let from = TimeFmt.millis(monthStart)
        let to = TimeFmt.millis(cal.date(byAdding: .month, value: 1, to: monthStart)!) - 1
        monthEvents = (try? await env.db.dbQueue.read { db in
            try EventRow
                .filter(Column("babyId") == babyId && Column("deletedAt") == nil)
                .filter(Column("startedAt") >= from && Column("startedAt") <= to)
                .fetchAll(db)
        }) ?? []
    }

    private func loadWeek() async {
        guard let babyId = model.selectedBaby?.id else { return }
        let from = TimeFmt.millis(cal.date(byAdding: .day, value: -6, to: selectedDate)!)
        let to = TimeFmt.millis(cal.date(byAdding: .day, value: 1, to: selectedDate)!) - 1
        weekEvents = (try? await env.db.dbQueue.read { db in
            try EventRow
                .filter(Column("babyId") == babyId && Column("deletedAt") == nil)
                .filter(Column("startedAt") >= from && Column("startedAt") <= to)
                .fetchAll(db)
        }) ?? []
    }
}
