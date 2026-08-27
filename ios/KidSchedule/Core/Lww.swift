import Foundation

/// LWW 裁决输入:同一行的一个版本。时间为 epoch 毫秒。协议 §6
struct LwwVersion {
    let clientUpdatedAt: Int64
    let deviceId: String
}

enum LwwVerdict {
    case aWins, bWins, equalKeepA
}

enum Lww {
    /// a = 现存版本,b = 新来版本
    static func decide(a: LwwVersion, b: LwwVersion) -> LwwVerdict {
        if b.clientUpdatedAt > a.clientUpdatedAt { return .bWins }
        if b.clientUpdatedAt < a.clientUpdatedAt { return .aWins }
        if b.deviceId > a.deviceId { return .bWins }
        if b.deviceId < a.deviceId { return .aWins }
        return .equalKeepA
    }
}
