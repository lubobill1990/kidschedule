import SwiftUI

struct SettingsView: View {
    @EnvironmentObject private var env: AppEnv
    @Environment(\.dismiss) private var dismiss
    @ObservedObject var model: HomeModel

    @State private var familyName: String?
    @State private var inviteCode: String?
    @State private var busy = false
    @State private var error: String?
    @State private var showAddBaby = false
    @State private var newBabyName = ""
    @State private var editingBaby: BabyRow?
    @State private var showEditBaby = false
    @State private var editBabyName = ""
    @State private var editBabyBirthday = ""
    @State private var showAddType = false
    @State private var editingType: ActivityTypeRow?
    @State private var showEditProfile = false
    @State private var profileEmoji = ""
    @State private var profileName = ""
    @State private var profileBusy = false

    private var me: FamilyMemberRow? {
        model.members.first { $0.userId == model.myUserId }
    }

    var body: some View {
        NavigationStack {
            Form {
                Section("我的资料") {
                    LabeledContent("头像", value: me?.avatarEmoji ?? "👤")
                    LabeledContent("昵称", value: me?.displayName ?? "未设置")
                    Button("编辑资料") {
                        profileEmoji = me?.avatarEmoji ?? ""
                        profileName = me?.displayName ?? ""
                        showEditProfile = true
                    }
                }
                Section("家庭") {
                    LabeledContent("名称", value: familyName ?? "…")
                    if let code = inviteCode {
                        LabeledContent("邀请码") {
                            Text(code)
                                .font(.body.monospaced().bold())
                                .textSelection(.enabled)
                        }
                        Button("复制邀请码") {
                            UIPasteboard.general.string = code
                        }
                    } else {
                        Button {
                            generateInvite()
                        } label: {
                            if busy { ProgressView() } else { Text("生成邀请码") }
                        }
                        .disabled(busy)
                    }
                }
                Section("宝宝") {
                    ForEach(model.babies) { baby in
                        Button {
                            editingBaby = baby
                            editBabyName = baby.name
                            editBabyBirthday = baby.birthday ?? ""
                            showEditBaby = true
                        } label: {
                            HStack {
                                Text(baby.name).foregroundStyle(.primary)
                                Spacer()
                                Text(baby.birthday ?? "")
                                    .font(.footnote)
                                    .foregroundStyle(.secondary)
                            }
                        }
                    }
                    Button("添加宝宝") { showAddBaby = true }
                }
                Section("行为类型") {
                    ForEach(model.types) { t in
                        Button {
                            editingType = t
                        } label: {
                            HStack {
                                Text("\(t.icon ?? "") \(t.name)")
                                    .foregroundStyle(.primary)
                                Spacer()
                                Text(typeTrailing(t))
                                    .font(.footnote)
                                    .foregroundStyle(.secondary)
                            }
                        }
                    }
                    Button("添加行为") { showAddType = true }
                }
                Section("账号") {
                    Button("退出登录", role: .destructive) {
                        Task {
                            await env.signOut()
                            dismiss()
                        }
                    }
                }
                if let error {
                    Text(error).font(.footnote).foregroundStyle(.red)
                }
            }
            .navigationTitle("设置")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("完成") { dismiss() }
                }
            }
            .task { await loadFamilyName() }
            .alert("添加宝宝", isPresented: $showAddBaby) {
                TextField("宝宝名字", text: $newBabyName)
                Button("添加") { addBaby() }
                Button("取消", role: .cancel) {}
            }
            .alert("编辑宝宝", isPresented: $showEditBaby) {
                TextField("名字", text: $editBabyName)
                TextField("生日(2026-01-31,可空)", text: $editBabyBirthday)
                Button("保存") { saveBaby() }
                Button("取消", role: .cancel) {}
            }
            .alert("编辑资料", isPresented: $showEditProfile) {
                TextField("头像 emoji", text: $profileEmoji)
                TextField("昵称", text: $profileName)
                Button("保存") { saveProfile() }
                Button("取消", role: .cancel) {}
            }
            .sheet(isPresented: $showAddType) {
                TypeEditView(
                    familyId: env.familyId ?? "", babies: model.babies,
                    initial: nil, sortOrderForNew: model.types.count
                ) { Task { await model.sync(env: env) } }
            }
            .sheet(item: $editingType) { t in
                TypeEditView(
                    familyId: env.familyId ?? "", babies: model.babies,
                    initial: t, sortOrderForNew: model.types.count
                ) { Task { await model.sync(env: env) } }
            }
        }
    }

    private func typeTrailing(_ t: ActivityTypeRow) -> String {
        var parts: [String] = []
        if let bid = t.babyId, let b = model.babies.first(where: { $0.id == bid }) {
            parts.append(b.name)
        }
        parts.append(t.kind == "duration" ? "持续" : "瞬时")
        switch t.reminderMode {
        case "auto": parts.append("智能提醒")
        case "fixed": parts.append("每\((t.reminderFixedIntervalSec ?? 0) / 60)分钟提醒")
        default: break
        }
        return parts.joined(separator: " · ")
    }

    /// 在线操作(security definer RPC),成功后回写本地缓存
    private func saveProfile() {
        guard let fid = env.familyId, !profileBusy else { return }
        let emoji = profileEmoji.trimmingCharacters(in: .whitespaces)
        let name = profileName.trimmingCharacters(in: .whitespaces)
        profileBusy = true
        error = nil
        Task {
            do {
                try await env.familyRepo.updateMyProfile(
                    familyId: fid,
                    displayName: name.isEmpty ? nil : name,
                    avatarEmoji: emoji.isEmpty ? nil : emoji
                )
                if let uid = model.myUserId {
                    try? await env.db.dbQueue.write { db in
                        try FamilyMemberRow(
                            familyId: fid, userId: uid,
                            role: me?.role ?? "member",
                            displayName: name.isEmpty ? nil : name,
                            avatarEmoji: emoji.isEmpty ? nil : emoji
                        ).save(db)
                    }
                }
                await model.reload(env: env)
            } catch {
                self.error = "保存失败:\(error.localizedDescription)"
            }
            profileBusy = false
        }
    }

    private func loadFamilyName() async {
        guard let fid = env.familyId else { return }
        let families = try? await env.familyRepo.myFamilies()
        familyName = families?.first { $0.id == fid }?.name ?? "我的家庭"
    }

    private func generateInvite() {
        guard let fid = env.familyId else { return }
        busy = true
        error = nil
        Task {
            do {
                inviteCode = try await env.familyRepo.createInvite(familyId: fid)
            } catch {
                self.error = "生成失败:\(error.localizedDescription)"
            }
            busy = false
        }
    }

    private func saveBaby() {
        guard var b = editingBaby else { return }
        editingBaby = nil
        let name = editBabyName.trimmingCharacters(in: .whitespaces)
        guard !name.isEmpty else { return }
        let birthday = editBabyBirthday.trimmingCharacters(in: .whitespaces)
        b.name = name
        b.birthday = birthday.isEmpty ? nil : birthday
        Task {
            try? await env.catalogRepo.updateBaby(b)
            await model.sync(env: env)
        }
    }

    private func addBaby() {
        guard let fid = env.familyId else { return }
        let name = newBabyName.trimmingCharacters(in: .whitespaces)
        newBabyName = ""
        guard !name.isEmpty else { return }
        Task {
            try? await env.catalogRepo.addBaby(familyId: fid, name: name, birthday: nil)
            await model.sync(env: env)
        }
    }
}

// 与 Android 端共用同一套预设色板
private let typeColors = [
    "#5B8DEF", "#63C5DA", "#7BC47F", "#F4B860",
    "#E88B8B", "#8E7CC3", "#F49AC1", "#A9836F",
]

private func paletteColor(_ hex: String) -> Color {
    guard let v = UInt32(hex.dropFirst(), radix: 16) else { return .gray }
    return Color(
        red: Double((v >> 16) & 0xFF) / 255.0,
        green: Double((v >> 8) & 0xFF) / 255.0,
        blue: Double(v & 0xFF) / 255.0
    )
}

private struct TypeEditView: View {
    @EnvironmentObject private var env: AppEnv
    @Environment(\.dismiss) private var dismiss

    let familyId: String
    let babies: [BabyRow]
    let initial: ActivityTypeRow? // nil = 新建
    let sortOrderForNew: Int
    let onSaved: () -> Void

    @State private var name: String
    @State private var icon: String
    @State private var kind: String
    @State private var maxDurationMin: String
    @State private var reminderMode: String
    @State private var reminderIntervalMin: String
    @State private var babyId: String?
    @State private var color: String?
    @State private var busy = false
    @State private var confirmDelete = false
    @State private var error: String?

    init(
        familyId: String, babies: [BabyRow], initial: ActivityTypeRow?,
        sortOrderForNew: Int, onSaved: @escaping () -> Void
    ) {
        self.familyId = familyId
        self.babies = babies
        self.initial = initial
        self.sortOrderForNew = sortOrderForNew
        self.onSaved = onSaved
        _name = State(initialValue: initial?.name ?? "")
        _icon = State(initialValue: initial?.icon ?? "")
        _kind = State(initialValue: initial?.kind ?? "instant")
        _maxDurationMin = State(initialValue: initial?.defaultMaxDurationSec.map { String($0 / 60) } ?? "")
        _reminderMode = State(initialValue: initial?.reminderMode ?? "off")
        _reminderIntervalMin = State(initialValue: initial?.reminderFixedIntervalSec.map { String($0 / 60) } ?? "")
        _babyId = State(initialValue: initial?.babyId)
        _color = State(initialValue: initial?.color)
    }

    private var intervalValid: Bool {
        reminderMode != "fixed" || (Int64(reminderIntervalMin) ?? 0) > 0
    }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField("名称", text: $name)
                    TextField("图标(emoji,可空)", text: $icon)
                    // 已有记录后改类别会让统计口径混乱,编辑时锁定
                    if initial == nil {
                        Picker("类别", selection: $kind) {
                            Text("瞬时").tag("instant")
                            Text("持续").tag("duration")
                        }
                    } else {
                        LabeledContent("类别", value: kind == "duration" ? "持续" : "瞬时")
                    }
                    if kind == "duration" {
                        TextField("最长时长(分钟,超时自动结束)", text: $maxDurationMin)
                            .keyboardType(.numberPad)
                    }
                }
                Section("颜色(widget 底色)") {
                    HStack(spacing: 10) {
                        ForEach(typeColors, id: \.self) { hex in
                            Circle()
                                .fill(paletteColor(hex))
                                .frame(width: 28, height: 28)
                                .overlay {
                                    if color == hex {
                                        Circle().strokeBorder(.primary, lineWidth: 2.5)
                                    }
                                }
                                .onTapGesture { color = color == hex ? nil : hex }
                        }
                    }
                }
                if babies.count > 1 {
                    Section {
                        Picker("适用宝宝", selection: $babyId) {
                            Text("通用").tag(String?.none)
                            ForEach(babies) { b in
                                Text(b.name).tag(b.id as String?)
                            }
                        }
                    }
                }
                Section("提醒") {
                    Picker("提醒", selection: $reminderMode) {
                        Text("关").tag("off")
                        Text("智能").tag("auto")
                        Text("固定间隔").tag("fixed")
                    }
                    .pickerStyle(.segmented)
                    if reminderMode == "fixed" {
                        TextField("间隔(分钟)", text: $reminderIntervalMin)
                            .keyboardType(.numberPad)
                    }
                }
                if initial != nil {
                    Section {
                        Button(confirmDelete ? "确认删除" : "删除", role: .destructive) {
                            if confirmDelete { delete() } else { confirmDelete = true }
                        }
                        .disabled(busy)
                    }
                }
                if let error {
                    Text(error).font(.footnote).foregroundStyle(.red)
                }
            }
            .navigationTitle(initial == nil ? "添加行为" : "编辑行为")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("取消") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("保存") { save() }
                        .disabled(
                            busy || !intervalValid
                                || name.trimmingCharacters(in: .whitespaces).isEmpty
                        )
                }
            }
        }
    }

    private func save() {
        guard !busy else { return }
        busy = true
        error = nil
        let trimmedName = name.trimmingCharacters(in: .whitespaces)
        let iconV = icon.trimmingCharacters(in: .whitespaces)
        let maxSec = Int64(maxDurationMin).map { $0 * 60 }
        let intervalSec = reminderMode == "fixed" ? Int64(reminderIntervalMin).map { $0 * 60 } : nil
        Task {
            do {
                if var t = initial {
                    t.name = trimmedName
                    t.icon = iconV.isEmpty ? nil : iconV
                    t.color = color
                    t.defaultMaxDurationSec = kind == "duration" ? maxSec : nil
                    t.reminderMode = reminderMode
                    t.reminderFixedIntervalSec = intervalSec
                    t.babyId = babyId
                    try await env.catalogRepo.updateActivityType(t)
                } else {
                    try await env.catalogRepo.addActivityType(
                        familyId: familyId, name: trimmedName,
                        icon: iconV.isEmpty ? nil : iconV, color: color, kind: kind,
                        defaultMaxDurationSec: kind == "duration" ? maxSec : nil,
                        reminderMode: reminderMode, reminderFixedIntervalSec: intervalSec,
                        sortOrder: sortOrderForNew, babyId: babyId
                    )
                }
                onSaved()
                dismiss()
            } catch {
                self.error = "保存失败:\(error.localizedDescription)"
            }
            busy = false
        }
    }

    private func delete() {
        guard var t = initial, !busy else { return }
        busy = true
        error = nil
        t.deletedAt = nowEpochMillis()
        Task {
            do {
                try await env.catalogRepo.updateActivityType(t)
                onSaved()
                dismiss()
            } catch {
                self.error = "删除失败:\(error.localizedDescription)"
            }
            busy = false
        }
    }
}
