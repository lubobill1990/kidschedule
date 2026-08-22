package app.kidschedule.data

import android.content.Context
import java.util.UUID

// 安装时生成一次,持久保存,参与 LWW 平局裁决(协议 §1)
object DeviceId {
    private const val PREFS = "kidschedule"
    private const val KEY = "device_id"

    fun get(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(KEY, null)?.let { return it }
        val id = UUID.randomUUID().toString()
        prefs.edit().putString(KEY, id).apply()
        return id
    }
}
