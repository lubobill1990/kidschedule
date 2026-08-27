import Foundation

enum TimeFmt {
    private static let timeOnly: DateFormatter = {
        let f = DateFormatter()
        f.dateFormat = "HH:mm"
        return f
    }()

    private static let monthDayTime: DateFormatter = {
        let f = DateFormatter()
        f.dateFormat = "M月d日 HH:mm"
        return f
    }()

    static func date(_ millis: Int64) -> Date {
        Date(timeIntervalSince1970: Double(millis) / 1000.0)
    }

    static func millis(_ date: Date) -> Int64 {
        Int64(date.timeIntervalSince1970 * 1000)
    }

    /// 今天只显示时间,更早带月日
    static func eventTime(_ millis: Int64) -> String {
        let d = date(millis)
        if Calendar.current.isDateInToday(d) { return timeOnly.string(from: d) }
        if Calendar.current.isDateInYesterday(d) { return "昨天 " + timeOnly.string(from: d) }
        return monthDayTime.string(from: d)
    }

    private static let monthDay: DateFormatter = {
        let f = DateFormatter()
        f.dateFormat = "M月d日 EEE"
        f.locale = Locale(identifier: "zh_CN")
        return f
    }()

    /// 时间线分组标题:今天 / 昨天 / M月d日 周几
    static func dayLabel(_ millis: Int64) -> String {
        let d = date(millis)
        if Calendar.current.isDateInToday(d) { return "今天" }
        if Calendar.current.isDateInYesterday(d) { return "昨天" }
        return monthDay.string(from: d)
    }

    /// 只显示 HH:mm
    static func clock(_ millis: Int64) -> String {
        timeOnly.string(from: date(millis))
    }

    /// 当天零点毫秒,用于按天分组
    static func startOfDay(_ millis: Int64) -> Int64 {
        Self.millis(Calendar.current.startOfDay(for: date(millis)))
    }

    /// 相对时间:刚刚 / N分钟前 / N小时前 / N天前
    static func relative(_ millis: Int64, now: Int64 = nowEpochMillis()) -> String {
        let sec = max(0, (now - millis) / 1000)
        if sec < 60 { return "刚刚" }
        if sec < 3600 { return "\(sec / 60)分钟前" }
        if sec < 86400 { return "\(sec / 3600)小时前" }
        return "\(sec / 86400)天前"
    }

    /// 时长:MM:SS 或 H小时M分
    static func duration(_ seconds: Int64) -> String {
        if seconds < 3600 {
            return String(format: "%d:%02d", seconds / 60, seconds % 60)
        }
        return "\(seconds / 3600)小时\((seconds % 3600) / 60)分"
    }
}
