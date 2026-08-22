package app.kidschedule.data.sync

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.query.filter.FilterOperator
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.postgresChangeFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge

// 只作为「有变化」信号,不解析 payload(协议 §10);收到即触发一次 pull。
class RealtimeSignal(private val client: SupabaseClient) {

    /** 订阅 family 各同步表的变更,返回合并信号流。调用方负责收集并在断线重连后补拉。 */
    suspend fun subscribe(familyId: String): Flow<Unit> {
        val channel = client.channel("family-$familyId")
        val flows = SyncEntity.entries.map { entity ->
            channel.postgresChangeFlow<PostgresAction>(schema = "public") {
                table = entity.table
                filter("family_id", FilterOperator.EQ, familyId)
            }
        }
        channel.subscribe()
        return flows.merge().map { }
    }

    suspend fun unsubscribe(familyId: String) {
        client.channel("family-$familyId").unsubscribe()
    }
}
