package app.splitevenly.data.remote.supabase

import app.splitevenly.core.error.AppError
import app.splitevenly.core.error.AppResult
import app.splitevenly.core.id.GroupId
import app.splitevenly.core.id.UserId
import app.splitevenly.domain.expense.Expense
import app.splitevenly.domain.group.Group
import app.splitevenly.ui.screen.group.ExpensesState
import app.splitevenly.ui.screen.group.buildGroupExpenses
import app.splitevenly.ui.screen.home.HomeUiState
import app.splitevenly.ui.screen.home.homeState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class HydrationTrackerTest {
    private val ok = AppResult.Ok(Unit)
    private val offlineErr: AppResult<Unit> = AppResult.Err(AppError.Network(AppError.Network.Kind.Offline))

    @Test
    fun emptyRoomIsNeverEmptyUntilTheFirstPullLands() = runTest {
        val t = HydrationTracker()
        assertEquals(ListReadiness.Waiting, t.observeUserReadiness("u").first())
        t.pullStarted("u")
        assertEquals(ListReadiness.Waiting, t.observeUserReadiness("u").first())
        t.pullFinished("u", ok)
        assertEquals(ListReadiness.Ready, t.observeUserReadiness("u").first())
    }

    @Test
    fun failedFirstPullReportsOfflineAndRecoversOnRetrySuccess() = runTest {
        val t = HydrationTracker()
        t.pullStarted("u")
        t.pullFinished("u", offlineErr)
        assertEquals(ListReadiness.Failed(offline = true), t.observeUserReadiness("u").first())
        t.pullFinished("u", ok)
        assertEquals(ListReadiness.Ready, t.observeUserReadiness("u").first())
    }

    @Test
    fun aLaterBlipDoesNotBlankAnAlreadyHydratedSession() = runTest {
        val t = HydrationTracker()
        t.pullFinished("u", ok)
        t.pullStarted("u")
        t.pullFinished("u", offlineErr)
        assertEquals(ListReadiness.Ready, t.observeUserReadiness("u").first())
    }

    @Test
    fun anotherAccountDoesNotInheritDone() = runTest {
        val t = HydrationTracker()
        t.pullFinished("a", ok)
        assertEquals(ListReadiness.Waiting, t.observeUserReadiness("b").first())
    }

    @Test
    fun joinedGroupWaitsForItsPullThenClears() = runTest {
        val t = HydrationTracker()
        t.pullFinished("u", ok)
        t.markGroupPending("g")
        assertEquals(ListReadiness.Waiting, t.observeGroupReadiness("u", "g").first())
        assertEquals(ListReadiness.Ready, t.observeGroupReadiness("u", "other").first())
        t.pullFinished("u", offlineErr)
        assertEquals(ListReadiness.Failed(offline = true), t.observeGroupReadiness("u", "g").first())
        t.pullStarted("u")
        t.pullFinished("u", ok)
        assertEquals(ListReadiness.Ready, t.observeGroupReadiness("u", "g").first())
    }

    @Test
    fun aPullThatStartedBeforeTheJoinDoesNotClearIt() = runTest {
        val t = HydrationTracker()
        t.pullFinished("u", ok)
        t.pullStarted("u")
        t.markGroupPending("g")
        t.pullFinished("u", ok)
        assertEquals(ListReadiness.Waiting, t.observeGroupReadiness("u", "g").first())
        t.pullStarted("u")
        t.pullFinished("u", ok)
        assertEquals(ListReadiness.Ready, t.observeGroupReadiness("u", "g").first())
    }

    @Test
    fun signOutWipeMakesTheSameAccountWaitAgain() = runTest {
        val t = HydrationTracker()
        t.pullFinished("u", ok)
        t.forget("u")
        assertEquals(ListReadiness.Waiting, t.observeUserReadiness("u").first())
    }

    @Test
    fun retryRunsTheAttachedSyncer() = runTest {
        val t = HydrationTracker()
        var ran: String? = null
        t.attachSyncer { ran = it; ok }
        t.retry("u")
        assertEquals("u", ran)
    }

    @Test
    fun offlineStubIsAlwaysReady() = runTest {
        assertEquals(ListReadiness.Ready, HydrationTracker(assumeHydrated = true).observeUserReadiness("u").first())
    }

    @Test
    fun homeStateMatrixForEmptyRoom() {
        assertEquals(HomeUiState.Loading, homeState(emptyList(), emptyList(), 0, ListReadiness.Waiting))
        assertEquals(HomeUiState.Empty, homeState(emptyList(), emptyList(), 0, ListReadiness.Ready))
        assertEquals(HomeUiState.Failed(true), homeState(emptyList(), emptyList(), 0, ListReadiness.Failed(true)))
        // A deleted group alone does not stop the real empty state.
        assertEquals(HomeUiState.Empty, homeState(emptyList(), emptyList(), 2, ListReadiness.Ready))
    }

    @Test
    fun groupExpensesMatrixForEmptyRoom() {
        val me = UserId("u1")
        val g = Group(GroupId("g"), "Trip", "x", "USD", adminUserId = me, inviteToken = "t", createdAt = 0)
        fun state(r: ListReadiness) = buildGroupExpenses(g, emptyList<Expense>(), emptyList(), me, "2026-06-15", readiness = r).state
        assertEquals(ExpensesState.Loading, state(ListReadiness.Waiting))
        assertEquals(ExpensesState.Empty, state(ListReadiness.Ready))
        assertEquals(ExpensesState.Failed, state(ListReadiness.Failed(false)))
    }
}
