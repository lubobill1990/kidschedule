import Foundation

// App 与 widget 扩展共享的容器:同一 SQLite + 同一 UserDefaults。

enum AppGroup {
    static let id = "group.com.weavejam.kidschedule"

    static let defaults: UserDefaults = {
        guard let d = UserDefaults(suiteName: id) else { return .standard }
        // 老版本把这些键存在 standard,迁到共享容器(只补缺,不覆盖)
        for key in ["device_id", "current_family_id", "selected_baby_id"] {
            if d.string(forKey: key) == nil, let v = UserDefaults.standard.string(forKey: key) {
                d.set(v, forKey: key)
            }
        }
        return d
    }()

    /// App Group 容器内的数据库路径;拿不到容器(如单元测试)回退 Application Support
    static func databasePath() throws -> String {
        guard let container = FileManager.default
            .containerURL(forSecurityApplicationGroupIdentifier: id)
        else {
            return try legacyDir().appendingPathComponent("kidschedule.sqlite").path
        }
        let dest = container.appendingPathComponent("kidschedule.sqlite")
        migrateLegacyDatabase(to: dest)
        return dest.path
    }

    private static func legacyDir() throws -> URL {
        try FileManager.default.url(
            for: .applicationSupportDirectory, in: .userDomainMask,
            appropriateFor: nil, create: true
        )
    }

    /// 老版本库在 Application Support,一次性搬进 App Group(连 -wal/-shm)
    private static func migrateLegacyDatabase(to dest: URL) {
        let fm = FileManager.default
        guard !fm.fileExists(atPath: dest.path),
              let legacy = try? legacyDir().appendingPathComponent("kidschedule.sqlite"),
              fm.fileExists(atPath: legacy.path)
        else { return }
        for suffix in ["", "-wal", "-shm"] {
            try? fm.moveItem(
                at: URL(fileURLWithPath: legacy.path + suffix),
                to: URL(fileURLWithPath: dest.path + suffix)
            )
        }
    }
}
