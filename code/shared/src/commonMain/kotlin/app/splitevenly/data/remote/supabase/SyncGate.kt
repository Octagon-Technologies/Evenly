package app.splitevenly.data.remote.supabase

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Serialises every sync operation, and fences sign-out's cache wipe against the ones already queued.
 *
 * **The mutex alone was not enough, and that is the whole reason this class exists.** Three sync
 * launches run on `SupabaseAuthSession`'s process-lifetime scope and never gate on `currentUserId`:
 * the restore-pull in `init`, `mirrorCurrentUser`'s `syncNow`, and `PushController`'s pull on a
 * delivered push (deliberately ungated — background FCM → pull is intended). Sign-out used to (1) push,
 * (2) null `currentUserId`, (3) wipe Room, (4) sign out of Supabase. A pull queued behind step 1's push
 * acquired the mutex the moment that push released it, ran with account A's session still fully valid
 * (step 4 is last), and re-landed A's groups, expenses, and members into the cache step
 * 3 had just emptied. The mutex did not prevent that — it *scheduled* it, by holding the pull until
 * exactly the window the wipe runs in. Nulling `currentUserId` cancels `SyncManager`'s loops, which
 * are children of the gate collector, and none of the three above.
 *
 * Two rules close it:
 *
 * 1. **The latch is checked after the lock is taken, not before.** An operation that queued while sync
 *    was open still sees the closed latch when its turn finally comes.
 * 2. **The wipe runs inside the same lock.** So it cannot interleave with an operation already
 *    in-flight; it waits for it, and everything after it is refused.
 *
 * The latch is keyed by user id rather than being a plain boolean so that [reopen] cannot be used by
 * one account's sign-in to unblock work queued for the account that just left.
 */
internal class SyncGate {
    private val mutex = Mutex()

    /** The account sync is closed for, or null. Set *before* the lock is taken — see rule 1. */
    private val closedFor = MutableStateFlow<String?>(null)

    /**
     * Run [block] under the sync lock, unless sync is closed for [userId] — then do nothing and return
     * null. Callers treat null as "this round trip did not happen", which is neither a success nor a
     * failure and must not be recorded as either.
     */
    suspend fun <T> withSync(
        userId: String,
        block: suspend () -> T,
    ): T? = mutex.withLock { if (closedFor.value == userId) null else block() }

    /**
     * Close sync for [userId], then run [block] with the lock held.
     *
     * The close happens before the lock is requested on purpose: anything already queued reaches the
     * front, sees the latch, and returns without touching Room, so [block] is the next thing to run.
     */
    suspend fun <T> closeForSignOut(
        userId: String,
        block: suspend () -> T,
    ): T {
        closedFor.value = userId
        return mutex.withLock { block() }
    }

    /**
     * Reopen sync, but only if it is still closed for [userId].
     *
     * Two callers: sign-out aborting (unsynced writes were found, the user stays signed in), and the
     * same account signing back in. A *different* account signing in must not clear the latch — its own
     * pushes and pulls were never blocked by it, and clearing it would hand a still-queued operation
     * for the departed account permission to run.
     */
    fun reopen(userId: String) {
        closedFor.compareAndSet(expect = userId, update = null)
    }

    /** Whether sync is currently closed for [userId]. For tests and diagnostics only. */
    fun isClosedFor(userId: String): Boolean = closedFor.value == userId
}
