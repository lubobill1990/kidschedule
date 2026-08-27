import Foundation

// 时间戳网络传输用 ISO8601,本地存 epoch 毫秒(协议 §1)。
// 服务端 timestamptz 可能带 6 位小数与 +00:00 偏移,解析时归一到毫秒。
enum IsoTime {
    private static let outFmt: DateFormatter = {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.timeZone = TimeZone(identifier: "UTC")
        f.dateFormat = "yyyy-MM-dd'T'HH:mm:ss.SSSXXXXX"
        return f
    }()

    private static let isoFmt = ISO8601DateFormatter()

    static func toIso(_ millis: Int64) -> String {
        outFmt.string(from: Date(timeIntervalSince1970: Double(millis) / 1000.0))
    }

    static func toMillis(_ iso: String) -> Int64 {
        var base = iso
        var fracMillis: Int64 = 0
        if let dot = iso.firstIndex(of: ".") {
            let after = iso.index(after: dot)
            var end = after
            while end < iso.endIndex, iso[end].isNumber { end = iso.index(after: end) }
            let digits = String(iso[after..<end])
            let padded = digits.count >= 3 ? String(digits.prefix(3)) : digits.padding(toLength: 3, withPad: "0", startingAt: 0)
            fracMillis = Int64(padded) ?? 0
            base = String(iso[..<dot]) + String(iso[end...])
        }
        guard let d = isoFmt.date(from: base) else { return 0 }
        return Int64(d.timeIntervalSince1970.rounded()) * 1000 + fracMillis
    }
}
