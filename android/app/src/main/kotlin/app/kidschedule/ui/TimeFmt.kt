package app.kidschedule.ui

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

object TimeFmt {
    private val hm = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())
    private val mdhm = DateTimeFormatter.ofPattern("M月d日 HH:mm").withZone(ZoneId.systemDefault())
    private val mdWeek = DateTimeFormatter.ofPattern("M月d日 EEE", Locale.CHINA)
    private val edit = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    fun clock(millis: Long): String = hm.format(Instant.ofEpochMilli(millis))

    fun dateClock(millis: Long): String = mdhm.format(Instant.ofEpochMilli(millis))

    /** 当天零点毫秒,用于按天分组 */
    fun startOfDay(millis: Long): Long =
        Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate()
            .atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    /** 时间线分组标题:今天 / 昨天 / M月d日 周几 */
    fun dayLabel(dayStartMillis: Long): String {
        val date = Instant.ofEpochMilli(dayStartMillis).atZone(ZoneId.systemDefault()).toLocalDate()
        val today = LocalDate.now()
        return when (date) {
            today -> "今天"
            today.minusDays(1) -> "昨天"
            else -> mdWeek.format(date)
        }
    }

    /** “3分钟前 / 2小时前 / 昨天 14:30” */
    fun relative(millis: Long, now: Long): String {
        val diff = now - millis
        return when {
            diff < 60_000 -> "刚刚"
            diff < 3600_000 -> "${diff / 60_000}分钟前"
            diff < 86400_000 -> "${diff / 3600_000}小时${diff % 3600_000 / 60_000}分前"
            else -> dateClock(millis)
        }
    }

    /** 时长汇总 “1小时40分 / 45分” */
    fun durationText(totalSec: Long): String {
        val h = totalSec / 3600
        val m = totalSec % 3600 / 60
        return when {
            h > 0 && m > 0 -> "${h}小时${m}分"
            h > 0 -> "${h}小时"
            else -> "${m}分"
        }
    }

    /** 可编辑时间文本 “2026-08-22 00:15” */
    fun editText(millis: Long): String =
        edit.format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))

    fun parseEdit(text: String): Long? = runCatching {
        LocalDateTime.parse(text.trim(), edit)
            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }.getOrNull()

    /** 进行中已持续 “12:34” */
    fun elapsed(startMillis: Long, now: Long): String {
        val sec = ((now - startMillis) / 1000).coerceAtLeast(0)
        val h = sec / 3600
        val m = sec % 3600 / 60
        val s = sec % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }
}
