package app.kidschedule.data.repo

import android.content.Context
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Serializable
data class FamilyDto(val id: String, val name: String)

// 家庭/邀请均走 security definer RPC(迁移 0001)
class FamilyRepo(private val client: SupabaseClient, context: Context) {

    private val prefs = context.getSharedPreferences("kidschedule", Context.MODE_PRIVATE)

    var currentFamilyId: String?
        get() = prefs.getString("current_family_id", null)
        set(value) {
            prefs.edit().putString("current_family_id", value).apply()
        }

    suspend fun createFamily(name: String, displayName: String?): String =
        client.postgrest.rpc(
            "create_family",
            buildJsonObject {
                put("p_name", name)
                displayName?.let { put("p_display_name", it) }
            },
        ).decodeAs()

    suspend fun createInvite(familyId: String): String =
        client.postgrest.rpc(
            "create_invite",
            buildJsonObject { put("p_family_id", familyId) },
        ).decodeAs()

    suspend fun acceptInvite(code: String, displayName: String?): String =
        client.postgrest.rpc(
            "accept_invite",
            buildJsonObject {
                put("p_code", code)
                displayName?.let { put("p_display_name", it) }
            },
        ).decodeAs()

    /** RLS 限定只返回本人所在家庭 */
    suspend fun myFamilies(): List<FamilyDto> =
        client.postgrest.from("families").select().decodeList()
}
