import SwiftUI

@main
struct KidScheduleApp: App {
    var body: some Scene {
        WindowGroup {
            ContentView()
        }
    }
}

struct ContentView: View {
    var body: some View {
        VStack(spacing: 12) {
            Image(systemName: "figure.and.child.holdinghands")
                .font(.system(size: 56))
                .foregroundStyle(.tint)
            Text("KidSchedule")
                .font(.largeTitle.bold())
            Text("多用户家庭共享的婴儿看护记录")
                .foregroundStyle(.secondary)
        }
    }
}
