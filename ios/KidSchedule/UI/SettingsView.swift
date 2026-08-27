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
                        LabeledContent(baby.name, value: baby.birthday ?? "")
                    }
                    Button("添加宝宝") { showAddBaby = true }
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
            .alert("编辑资料", isPresented: $showEditProfile) {
                TextField("头像 emoji", text: $profileEmoji)
                TextField("昵称", text: $profileName)
                Button("保存") { saveProfile() }
                Button("取消", role: .cancel) {}
            }
        }
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
