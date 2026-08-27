import SwiftUI
import GRDB
import WidgetKit

struct UndoState: Equatable {
    let eventId: String
    let typeName: String
    let deadlineMillis: Int64
}

@MainActor
final class HomeModel: ObservableObject {
    @Published var babies: [BabyRow] = []
    @Published var types: [ActivityTypeRow] = []
    @Published var events: [EventRow] = []
    @Published var members: [FamilyMemberRow] = []
    @Published var myUserId: String?
    @Published var undo: UndoState?
    @Published var isSyncing = false

    var selectedBabyId: String? {
        get { AppGroup.defaults.string(forKey: "selected_baby_id") }
        set {
            AppGroup.defaults.set(newValue, forKey: "selected_baby_id")
            WidgetCenter.shared.reloadAllTimelines()
            objectWillChange.send()
        }
    }

    var selectedBaby: BabyRow? {
        babies.first { $0.id == selectedBabyId } ?? babies.first
    }

    var typesById: [String: ActivityTypeRow] {
        Dictionary(uniqueKeysWithValues: types.map { ($0.id, $0) })
    }

    var membersById: [String: FamilyMemberRow] {
        Dictionary(uniqueKeysWithValues: members.map { ($0.userId, $0) })
    }

    var ongoingEvents: [EventRow] {
        events.filter { $0.status == "ongoing" }
    }

    func reload(env: AppEnv) async {
        guard let fid = env.familyId else { return }
        let sel = selectedBabyId
        myUserId = await env.supa.userId
        do {
            let (babies, types, events, members): ([BabyRow], [ActivityTypeRow], [EventRow], [FamilyMemberRow]) =
                try await env.db.dbQueue.read { db in
                    let babies = try BabyRow
                        .filter(Column("familyId") == fid && Column("deletedAt") == nil)
                        .order(Column("name"))
                        .fetchAll(db)
                    // 选中的宝宝可能已被删除/同步移除,失效时回退到第一个
                    let babyId = babies.first { $0.id == sel }?.id ?? babies.first?.id
                    let types = try ActivityTypeRow
                        .filter(Column("familyId") == fid && Column("deletedAt") == nil)
                        .order(Column("sortOrder"), Column("name"))
                        .fetchAll(db)
                    var events: [EventRow] = []
                    if let babyId {
                        events = try EventRow
                            .filter(Column("babyId") == babyId && Column("deletedAt") == nil)
                            .order(Column("startedAt").desc)
                            .limit(100)
                            .fetchAll(db)
                    }
                    let members = try FamilyMemberRow
                        .filter(Column("familyId") == fid)
                        .fetchAll(db)
                    return (babies, types, events, members)
                }
            self.babies = babies
            self.types = types
            self.events = events
            self.members = members
        } catch {
            // 本地读失败不致命,下次刷新重试
        }
    }

    func record(env: AppEnv, type: ActivityTypeRow) async {
        guard let fid = env.familyId, let baby = selectedBaby else { return }
        do {
            let id: String?
            if type.kind == "duration" {
                id = try await env.recordRepo.quickStartDuration(
                    familyId: fid, babyId: baby.id, typeId: type.id
                )
            } else {
                id = try await env.recordRepo.quickRecordInstant(
                    familyId: fid, babyId: baby.id, typeId: type.id
                )
            }
            if let id {
                undo = UndoState(
                    eventId: id, typeName: type.name,
                    deadlineMillis: nowEpochMillis() + Int64(SyncProtocol.undoWindowSec) * 1000
                )
                scheduleReleaseSync(env: env)
            }
            await reload(env: env)
        } catch {}
    }

    func undoNow(env: AppEnv) async {
        guard let u = undo else { return }
        undo = nil
        _ = try? await env.recordRepo.undo(eventId: u.eventId)
        await reload(env: env)
    }

    func endOngoing(env: AppEnv, eventId: String) async {
        try? await env.recordRepo.endDuration(eventId: eventId)
        await reload(env: env)
        await sync(env: env)
    }

    /// 撤销窗口到期后释放 held 并上行
    private func scheduleReleaseSync(env: AppEnv) {
        Task {
            try? await Task.sleep(nanoseconds: UInt64(SyncProtocol.undoWindowSec + 1) * 1_000_000_000)
            if let u = undo, u.deadlineMillis <= nowEpochMillis() { undo = nil }
            await sync(env: env)
        }
    }

    func sync(env: AppEnv) async {
        isSyncing = true
        try? await env.recordRepo.autoEndOverdue()
        await env.syncEngine.syncWithRetry()
        isSyncing = false
        await reload(env: env)
        WidgetCenter.shared.reloadAllTimelines()
    }
}

struct HomeView: View {
    @EnvironmentObject private var env: AppEnv
    @StateObject private var model = HomeModel()
    @State private var showBackfill = false
    @State private var showSettings = false
    @State private var showStats = false
    @State private var editingEvent: EventRow?

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                recordButtons
                if !model.ongoingEvents.isEmpty {
                    ongoingSection
                }
                timeline
            }
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .principal) { babyPicker }
                ToolbarItemGroup(placement: .topBarTrailing) {
                    Button {
                        Task { await model.sync(env: env) }
                    } label: {
                        if model.isSyncing {
                            ProgressView()
                        } else {
                            Image(systemName: "arrow.triangle.2.circlepath")
                        }
                    }
                    Button { showBackfill = true } label: { Image(systemName: "plus.circle") }
                    Button { showStats = true } label: { Image(systemName: "chart.bar.xaxis") }
                    Button { showSettings = true } label: { Image(systemName: "gearshape") }
                }
            }
            .safeAreaInset(edge: .bottom) {
                if model.undo != nil { undoBar }
            }
        }
        .task {
            await model.reload(env: env)
            await model.sync(env: env)
        }
        .task {
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 30_000_000_000)
                try? await env.recordRepo.autoEndOverdue()
                await model.reload(env: env)
            }
        }
        .onReceive(NotificationCenter.default.publisher(for: .dbChanged)) { _ in
            Task { await model.reload(env: env) }
        }
        .sheet(isPresented: $showBackfill) {
            BackfillView(model: model)
        }
        .sheet(isPresented: $showSettings) {
            SettingsView(model: model)
        }
        .sheet(isPresented: $showStats) {
            StatsView(model: model)
        }
        .sheet(item: $editingEvent) { event in
            EventDetailView(model: model, event: event)
        }
    }

    private var babyPicker: some View {
        Menu {
            ForEach(model.babies) { baby in
                Button {
                    model.selectedBabyId = baby.id
                    Task { await model.reload(env: env) }
                } label: {
                    if baby.id == model.selectedBaby?.id {
                        Label(baby.name, systemImage: "checkmark")
                    } else {
                        Text(baby.name)
                    }
                }
            }
        } label: {
            HStack(spacing: 4) {
                Text(model.selectedBaby?.name ?? "宝宝")
                    .font(.headline)
                Image(systemName: "chevron.down")
                    .font(.caption2)
            }
            .foregroundStyle(.primary)
        }
    }

    private var recordButtons: some View {
        LazyVGrid(columns: [GridItem(.adaptive(minimum: 72), spacing: 12)], spacing: 12) {
            ForEach(model.types) { type in
                Button {
                    Task { await model.record(env: env, type: type) }
                } label: {
                    VStack(spacing: 4) {
                        Text(type.icon ?? "•")
                            .font(.system(size: 28))
                        Text(type.name)
                            .font(.caption)
                            .foregroundStyle(.primary)
                    }
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 10)
                    .background(
                        RoundedRectangle(cornerRadius: 14)
                            .fill(Color(hex: type.color).opacity(0.18))
                    )
                }
                .buttonStyle(.plain)
            }
        }
        .padding(.horizontal)
        .padding(.top, 8)
    }

    private var ongoingSection: some View {
        VStack(spacing: 8) {
            ForEach(model.ongoingEvents) { event in
                let type = model.typesById[event.activityTypeId]
                HStack {
                    Text(type?.icon ?? "•")
                    Text("\(type?.name ?? "进行中")中")
                        .font(.subheadline.bold())
                    TimelineView(.periodic(from: .now, by: 1)) { _ in
                        Text(TimeFmt.duration(max(0, (nowEpochMillis() - event.startedAt) / 1000)))
                            .font(.subheadline.monospacedDigit())
                            .foregroundStyle(.secondary)
                    }
                    Spacer()
                    Button("结束") {
                        Task { await model.endOngoing(env: env, eventId: event.id) }
                    }
                    .buttonStyle(.borderedProminent)
                    .controlSize(.small)
                }
                .padding(.horizontal, 12)
                .padding(.vertical, 8)
                .background(
                    RoundedRectangle(cornerRadius: 12)
                        .fill(Color.accentColor.opacity(0.12))
                )
            }
        }
        .padding(.horizontal)
        .padding(.top, 8)
    }

    /// 按天分组(倒序),日期做 sticky section header
    private var eventsByDay: [(day: Int64, events: [EventRow])] {
        let grouped = Dictionary(grouping: model.events) { TimeFmt.startOfDay($0.startedAt) }
        return grouped.keys.sorted(by: >).map { (day: $0, events: grouped[$0]!) }
    }

    private var timeline: some View {
        List {
            if model.events.isEmpty {
                Text("还没有记录")
                    .foregroundStyle(.secondary)
                    .frame(maxWidth: .infinity, alignment: .center)
                    .listRowSeparator(.hidden)
                    .padding(.top, 40)
            }
            ForEach(eventsByDay, id: \.day) { group in
                Section {
                    ForEach(group.events) { event in
                        timelineRow(event)
                    }
                } header: {
                    Text(TimeFmt.dayLabel(group.day))
                        .font(.footnote.bold())
                        .foregroundStyle(.secondary)
                }
            }
        }
        .listStyle(.plain)
    }

    private func timelineRow(_ event: EventRow) -> some View {
        let type = model.typesById[event.activityTypeId]
        // 本地新建行 createdBy 为空 → 显示为当前用户(服务端插入时才填)
        let creator = model.membersById[event.createdBy ?? model.myUserId ?? ""]
        return Button {
            editingEvent = event
        } label: {
            HStack(spacing: 10) {
                Text(type?.icon ?? "•")
                    .font(.title3)
                VStack(alignment: .leading, spacing: 2) {
                    HStack(spacing: 6) {
                        Text(type?.name ?? "未知")
                            .font(.body)
                        if event.autoEnded {
                            Text("自动结束")
                                .font(.caption2)
                                .padding(.horizontal, 4)
                                .padding(.vertical, 1)
                                .background(Capsule().fill(Color.orange.opacity(0.2)))
                        }
                    }
                    if let note = event.note, !note.isEmpty {
                        Text(note)
                            .font(.caption)
                            .foregroundStyle(.secondary)
                            .lineLimit(1)
                    }
                }
                Spacer()
                if let creator {
                    Text(creator.avatarEmoji ?? String((creator.displayName ?? "👤").prefix(1)))
                        .font(.body)
                }
                VStack(alignment: .trailing, spacing: 2) {
                    Text(TimeFmt.clock(event.startedAt))
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                    if event.status == "ongoing" {
                        Text("进行中")
                            .font(.caption)
                            .foregroundStyle(.tint)
                    } else if let ended = event.endedAt, ended > event.startedAt {
                        Text(TimeFmt.duration((ended - event.startedAt) / 1000))
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }
            }
        }
        .buttonStyle(.plain)
    }

    private var undoBar: some View {
        HStack {
            TimelineView(.periodic(from: .now, by: 0.5)) { _ in
                let remain = max(0, (model.undo?.deadlineMillis ?? 0) - nowEpochMillis()) / 1000
                Text("已记录 \(model.undo?.typeName ?? "")(\(remain)s)")
                    .font(.subheadline)
            }
            Spacer()
            Button("撤销") {
                Task { await model.undoNow(env: env) }
            }
            .buttonStyle(.borderedProminent)
            .tint(.red)
            .controlSize(.small)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 10)
        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 14))
        .padding(.horizontal)
        .padding(.bottom, 4)
    }
}

extension Color {
    /// "#RRGGBB" → Color;非法值回退灰色
    init(hex: String?) {
        guard let hex, hex.hasPrefix("#"), hex.count == 7,
              let v = UInt32(hex.dropFirst(), radix: 16)
        else {
            self = .gray
            return
        }
        self = Color(
            red: Double((v >> 16) & 0xFF) / 255.0,
            green: Double((v >> 8) & 0xFF) / 255.0,
            blue: Double(v & 0xFF) / 255.0
        )
    }
}
