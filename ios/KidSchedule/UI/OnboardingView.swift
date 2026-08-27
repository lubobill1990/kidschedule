import SwiftUI

struct OnboardingView: View {
    private enum Stage {
        case checking, choose, create, join
    }

    @EnvironmentObject private var env: AppEnv
    @State private var stage: Stage = .checking
    @State private var familyName = ""
    @State private var displayName = ""
    @State private var babyName = ""
    @State private var hasBirthday = false
    @State private var birthday = Date()
    @State private var inviteCode = ""
    @State private var busy = false
    @State private var error: String?

    var body: some View {
        NavigationStack {
            Group {
                switch stage {
                case .checking:
                    ProgressView("正在检查家庭…")
                case .choose:
                    chooseView
                case .create:
                    createView
                case .join:
                    joinView
                }
            }
            .navigationTitle("开始使用")
        }
        .task { await checkFamilies() }
    }

    private var chooseView: some View {
        VStack(spacing: 16) {
            Text("你还没有加入任何家庭")
                .foregroundStyle(.secondary)
            Button("创建新家庭") { stage = .create }
                .buttonStyle(.borderedProminent)
            Button("用邀请码加入") { stage = .join }
                .buttonStyle(.bordered)
            if let error {
                Text(error).font(.footnote).foregroundStyle(.red)
            }
        }
        .padding()
    }

    private var createView: some View {
        Form {
            Section("家庭") {
                TextField("家庭名称(如:小明家)", text: $familyName)
                TextField("我的昵称(可选)", text: $displayName)
            }
            Section("宝宝") {
                TextField("宝宝名字", text: $babyName)
                Toggle("填写生日", isOn: $hasBirthday)
                if hasBirthday {
                    DatePicker("生日", selection: $birthday, displayedComponents: .date)
                }
            }
            if let error {
                Text(error).font(.footnote).foregroundStyle(.red)
            }
            Button(action: createFamily) {
                if busy { ProgressView() } else { Text("创建") }
            }
            .disabled(busy || familyName.isEmpty || babyName.isEmpty)
            Button("返回") { stage = .choose }
                .disabled(busy)
        }
    }

    private var joinView: some View {
        Form {
            Section("邀请码") {
                TextField("8 位邀请码", text: $inviteCode)
                    .textInputAutocapitalization(.characters)
                    .autocorrectionDisabled()
                TextField("我的昵称(可选)", text: $displayName)
            }
            if let error {
                Text(error).font(.footnote).foregroundStyle(.red)
            }
            Button(action: joinFamily) {
                if busy { ProgressView() } else { Text("加入") }
            }
            .disabled(busy || inviteCode.count < 6)
            Button("返回") { stage = .choose }
                .disabled(busy)
        }
    }

    private func checkFamilies() async {
        do {
            let families = try await env.familyRepo.myFamilies()
            if let first = families.first {
                await env.syncEngine.syncWithRetry()
                env.onFamilyReady(first.id)
            } else {
                stage = .choose
            }
        } catch {
            self.error = "查询失败:\(error.localizedDescription)"
            stage = .choose
        }
    }

    private func createFamily() {
        busy = true
        error = nil
        Task {
            do {
                let dn = displayName.isEmpty ? nil : displayName
                let fid = try await env.familyRepo.createFamily(name: familyName, displayName: dn)
                try await env.catalogRepo.seedDefaultTypes(familyId: fid)
                let bday = hasBirthday ? isoDate(birthday) : nil
                try await env.catalogRepo.addBaby(familyId: fid, name: babyName, birthday: bday)
                await env.syncEngine.syncWithRetry()
                env.onFamilyReady(fid)
            } catch {
                self.error = "创建失败:\(error.localizedDescription)"
            }
            busy = false
        }
    }

    private func joinFamily() {
        busy = true
        error = nil
        Task {
            do {
                let dn = displayName.isEmpty ? nil : displayName
                let code = inviteCode.trimmingCharacters(in: .whitespaces).uppercased()
                let fid = try await env.familyRepo.acceptInvite(code: code, displayName: dn)
                await env.syncEngine.syncWithRetry()
                env.onFamilyReady(fid)
            } catch {
                self.error = "加入失败:\(error.localizedDescription)"
            }
            busy = false
        }
    }

    private func isoDate(_ date: Date) -> String {
        let f = DateFormatter()
        f.dateFormat = "yyyy-MM-dd"
        return f.string(from: date)
    }
}
