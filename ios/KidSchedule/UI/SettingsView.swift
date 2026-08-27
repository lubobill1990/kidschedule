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

    var body: some View {
        NavigationStack {
            Form {
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
