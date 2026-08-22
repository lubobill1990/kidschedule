package app.kidschedule.core.sync

/** LWW 裁决输入:同一行的一个版本。时间为 epoch 毫秒。协议 §6 */
data class LwwVersion(val clientUpdatedAt: Long, val deviceId: String)

enum class LwwVerdict { A_WINS, B_WINS, EQUAL_KEEP_A }

object Lww {
    /** a = 现存版本,b = 新来版本 */
    fun decide(a: LwwVersion, b: LwwVersion): LwwVerdict = when {
        b.clientUpdatedAt > a.clientUpdatedAt -> LwwVerdict.B_WINS
        b.clientUpdatedAt < a.clientUpdatedAt -> LwwVerdict.A_WINS
        b.deviceId > a.deviceId -> LwwVerdict.B_WINS
        b.deviceId < a.deviceId -> LwwVerdict.A_WINS
        else -> LwwVerdict.EQUAL_KEEP_A
    }
}
