package app.kidschedule.core.reminder

import app.kidschedule.core.Protocol

// 提醒阈值与下次触发时刻计算,协议 §13。时间均为 epoch 毫秒。

data class ReminderEvent(
    val startedAt: Long,
    val endedAt: Long? = null,
    val ongoing: Boolean = false,
    val deleted: Boolean = false,
)

enum class ActivityKind { INSTANT, DURATION }
enum class ReminderMode { AUTO, FIXED, OFF }

data class ReminderResult(val thresholdSec: Long?, val nextFireAtMillis: Long?) {
    companion object {
        val NONE = ReminderResult(null, null)
    }
}

object ReminderCalculator {

    fun compute(
        kind: ActivityKind,
        mode: ReminderMode,
        fixedIntervalSec: Long?,
        events: List<ReminderEvent>,
    ): ReminderResult {
        if (mode == ReminderMode.OFF) return ReminderResult.NONE

        val sample = events
            .filter { !it.deleted }
            .sortedBy { it.startedAt }
            .takeLast(Protocol.REMINDER_SAMPLE_N)

        if (sample.isEmpty()) return ReminderResult.NONE
        if (sample.any { it.ongoing }) return ReminderResult.NONE

        val thresholdSec: Long = when (mode) {
            ReminderMode.FIXED -> fixedIntervalSec ?: return ReminderResult.NONE
            ReminderMode.AUTO -> {
                if (sample.size < Protocol.REMINDER_MIN_SAMPLES) return ReminderResult.NONE
                val intervals = sample.zipWithNext { a, b -> (b.startedAt - a.startedAt) / 1000 }.sorted()
                // P90 = 升序第 ceil(0.9n) 个(1-based);整数运算避免浮点误差
                val idx = (9 * intervals.size + 9) / 10
                intervals[idx - 1]
            }
            ReminderMode.OFF -> return ReminderResult.NONE
        }

        val last = sample.last()
        val anchor = when (kind) {
            ActivityKind.INSTANT -> last.startedAt
            ActivityKind.DURATION -> last.endedAt ?: last.startedAt
        }
        return ReminderResult(thresholdSec, anchor + thresholdSec * 1000)
    }
}
