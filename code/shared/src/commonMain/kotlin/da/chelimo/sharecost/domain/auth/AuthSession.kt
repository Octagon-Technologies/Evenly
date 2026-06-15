package da.chelimo.sharecost.domain.auth

import da.chelimo.sharecost.core.id.UserId
import kotlinx.coroutines.flow.StateFlow

/**
 * The signed-in user. This is the seam real auth (Supabase, §5.5) swaps into later — the rest of the
 * app depends only on this interface. For the MVP wiring it is backed by [StubAuthSession], a local
 * account with no network.
 */
interface AuthSession {
    /** The current user's id, or null when signed out. Data screens observe this. */
    val currentUserId: StateFlow<UserId?>

    /** Sign in (stub: create or load the local account), returning the user id. */
    suspend fun signIn(displayName: String): UserId

    fun signOut()
}
