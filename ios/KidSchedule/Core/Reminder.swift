import Foundation

// 提醒阈值与下次触发时刻计算,协议 §13。时间均为 epoch 毫秒。

struct ReminderEvent {
    let startedAt: Int64
    var endedAt: Int64? = nil
    var ongoing: Bool = false
    var deleted: Bool = false
}

enum ActivityKind: String {
    case instant, duration
}

enum ReminderMode: String {
    case auto, fixed, off
}

struct ReminderResult: Equatable {
    let thresholdSec: Int64?
    let nextFireAtMillis: Int64?

    static let none = ReminderResult(thresholdSec: nil, nextFireAtMillis: nil)
}

enum ReminderCalculator {

    static func compute(
        kind: ActivityKind,
        mode: ReminderMode,
        fixedIntervalSec: Int64?,
        events: [ReminderEvent]
    ) -> ReminderResult {
        if mode == .off { return .none }

        let sample = events
            .filter { !$0.deleted }
            .sorted { $0.startedAt < $1.startedAt }
            .suffix(SyncProtocol.reminderSampleN)

        if sample.isEmpty { return .none }
        if sample.contains(where: { $0.ongoing }) { return .none }

        let thresholdSec: Int64
        switch mode {
        case .fixed:
            guard let fixed = fixedIntervalSec else { return .none }
            thresholdSec = fixed
        case .auto:
            if sample.count < SyncProtocol.reminderMinSamples { return .none }
            var intervals: [Int64] = []
            for (a, b) in zip(sample, sample.dropFirst()) {
                intervals.append((b.startedAt - a.startedAt) / 1000)
            }
            intervals.sort()
            // P90 = 升序第 ceil(0.9n) 个(1-based);整数运算避免浮点误差
            let idx = (9 * intervals.count + 9) / 10
            thresholdSec = intervals[idx - 1]
        case .off:
            return .none
        }

        let last = sample.last!
        let anchor: Int64
        switch kind {
        case .instant: anchor = last.startedAt
        case .duration: anchor = last.endedAt ?? last.startedAt
        }
        return ReminderResult(thresholdSec: thresholdSec, nextFireAtMillis: anchor + thresholdSec * 1000)
    }
}
