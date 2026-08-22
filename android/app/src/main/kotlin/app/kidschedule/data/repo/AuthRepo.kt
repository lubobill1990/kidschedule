package app.kidschedule.data.repo

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.OtpType
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.builtin.OTP
import io.github.jan.supabase.auth.status.SessionStatus
import kotlinx.coroutines.flow.StateFlow

class AuthRepo(private val client: SupabaseClient) {

    val sessionStatus: StateFlow<SessionStatus> get() = client.auth.sessionStatus

    fun currentUserId(): String? = client.auth.currentUserOrNull()?.id

    /** phone 形如 +8613800138000 */
    suspend fun sendOtp(phone: String) {
        client.auth.signInWith(OTP) { this.phone = phone }
    }

    suspend fun verifyOtp(phone: String, code: String) {
        client.auth.verifyPhoneOtp(OtpType.Phone.SMS, phone = phone, token = code)
    }

    suspend fun signOut() = client.auth.signOut()
}
