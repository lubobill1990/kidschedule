package app.kidschedule.data.sync

import android.util.Log
import kotlinx.coroutines.delay

/** 网络恢复初期连接池中的死连接常让首个请求失败,须重试;失败必须留日志,否则无法排查 */
suspend fun SyncEngine.syncWithRetry(attempts: Int = 3): Boolean {
    for (attempt in 1..attempts) {
        val result = runCatching { sync() }
        if (result.isSuccess) return true
        Log.w("KidSchedule", "sync attempt $attempt/$attempts failed", result.exceptionOrNull())
        if (attempt < attempts) delay(1500L * attempt)
    }
    return false
}
