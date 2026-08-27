import SwiftUI

@main
struct KidScheduleApp: App {
    @StateObject private var env = AppEnv()

    var body: some Scene {
        WindowGroup {
            AppRootView()
                .environmentObject(env)
        }
    }
}

enum AppPhase {
    case loading, login, onboarding, home
}

@MainActor
final class AppEnv: ObservableObject {
    let db: AppDb
    let supa: SupaClient
    let syncEngine: SyncEngine
    let recordRepo: RecordRepo
    let catalogRepo: CatalogRepo
    let familyRepo: FamilyRepo
    let deviceId: String

    @Published var phase: AppPhase = .loading
    @Published var familyId: String?

    init() {
        // 本地库打不开属于不可恢复错误,直接崩溃暴露
        db = try! AppDb(path: try! AppDb.defaultPath())
        supa = SupaClient()
        deviceId = DeviceId.get()
        syncEngine = SyncEngine(db: db, supa: supa)
        recordRepo = RecordRepo(db: db, deviceId: deviceId)
        catalogRepo = CatalogRepo(db: db, deviceId: deviceId)
        familyRepo = FamilyRepo(supa: supa)
    }

    func bootstrap() async {
        let loggedIn = await supa.isLoggedIn
        guard loggedIn else {
            phase = .login
            return
        }
        if let fid = familyRepo.currentFamilyId {
            familyId = fid
            phase = .home
            await syncEngine.syncWithRetry()
        } else {
            phase = .onboarding
        }
    }

    func onLoggedIn() {
        phase = .onboarding
    }

    func onFamilyReady(_ fid: String) {
        familyRepo.currentFamilyId = fid
        familyId = fid
        phase = .home
    }

    func signOut() async {
        await supa.signOut()
        familyRepo.currentFamilyId = nil
        familyId = nil
        phase = .login
    }
}

struct AppRootView: View {
    @EnvironmentObject private var env: AppEnv
    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
        Group {
            switch env.phase {
            case .loading:
                ProgressView()
            case .login:
                LoginView()
            case .onboarding:
                OnboardingView()
            case .home:
                HomeView()
            }
        }
        .task { await env.bootstrap() }
        .onChange(of: scenePhase) { _, newPhase in
            guard newPhase == .active, env.phase == .home else { return }
            Task {
                try? await env.recordRepo.autoEndOverdue()
                await env.syncEngine.syncWithRetry()
            }
        }
    }
}
