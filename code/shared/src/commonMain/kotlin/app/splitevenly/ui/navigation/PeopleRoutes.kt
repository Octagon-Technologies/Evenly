package app.splitevenly.ui.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.splitevenly.core.error.AppResult
import app.splitevenly.data.remote.supabase.HydrationTracker
import app.splitevenly.data.remote.supabase.ListReadiness
import app.splitevenly.domain.auth.AuthSession
import app.splitevenly.domain.people.PeopleSummary
import app.splitevenly.domain.people.PersonExpense
import app.splitevenly.domain.people.linkPool
import app.splitevenly.domain.repository.PeopleRepository
import app.splitevenly.domain.repository.ProfileRepository
import app.splitevenly.ui.screen.people.KnownPersonUi
import app.splitevenly.ui.screen.people.LinkNamesScreen
import app.splitevenly.ui.screen.people.knownPeopleFor
import app.splitevenly.ui.screen.people.PeopleListScreen
import app.splitevenly.ui.screen.people.PersonScreen
import app.splitevenly.ui.screen.people.RecordPaymentSheet
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import app.splitevenly.ui.theme.EvenlyTheme
import org.koin.compose.koinInject

/** The signed-in viewer's People summary, or null while there is no user or the first pull hasn't landed. */
@OptIn(ExperimentalCoroutinesApi::class)
@Composable
private fun rememberPeople(): PeopleSummary? {
    val people = koinInject<PeopleRepository>()
    val auth = koinInject<AuthSession>()
    val hydration = koinInject<HydrationTracker>()
    val flow: Flow<PeopleSummary?> =
        remember {
            auth.currentUserId.flatMapLatest { uid ->
                if (uid == null) {
                    flowOf(null)
                } else {
                    combine(people.observePeople(uid), hydration.observeUserReadiness(uid.value)) { summary, readiness ->
                        // Room answers empty before the first pull; only a landed pull may claim "nobody owes you".
                        val empty = summary.owing.isEmpty() && summary.settled.isEmpty()
                        if (empty && readiness != ListReadiness.Ready) null else summary
                    }
                }
            }
        }
    val summary by flow.collectAsStateWithLifecycle(null)
    return summary
}

/** "People you know" for an add-by-name sheet in [groupId]: everyone from the viewer's other groups. */
@Composable
fun rememberKnownPeople(groupId: String): List<KnownPersonUi> {
    val summary = rememberPeople()
    return remember(summary, groupId) { summary?.let { knownPeopleFor(it.everyone, groupId) }.orEmpty() }
}

/** The People root tab. */
@Composable
fun PeopleTabRoute(
    onOpenPerson: (String) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val profiles = koinInject<ProfileRepository>()
    val profile by profiles.observeProfile().collectAsStateWithLifecycle(null)
    PeopleListScreen(
        summary = rememberPeople(),
        userName = profile?.displayName?.takeIf { it.isNotBlank() && it != "You" } ?: "there",
        onOpenPerson = onOpenPerson,
        onOpenSettings = onOpenSettings,
    )
}

/** One person's page. [personKey] is [app.splitevenly.domain.people.Person.key]. */
@Composable
fun PersonRoute(
    personKey: String,
    onBack: () -> Unit,
    onLinkOtherNames: () -> Unit,
    onOpenExpense: (PersonExpense) -> Unit,
) {
    val people = koinInject<PeopleRepository>()
    val auth = koinInject<AuthSession>()
    val scope = rememberCoroutineScope()
    val summary = rememberPeople()
    // A link can change which name is the key, so fall back to whichever person now holds the old one.
    val person = summary?.let { s -> (s.owing + s.settled).firstOrNull { it.key == personKey || personKey in it.memberIds } }
    var paying by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize()) {
        PersonScreen(
            person = person,
            onBack = onBack,
            onRecordPayment = { paying = true },
            onLinkMatch = { m ->
                val me = auth.currentUserId.value ?: return@PersonScreen
                person?.let { p -> scope.launch { people.link(me, listOf(m.userId), p.key) } }
            },
            onRuleOut = { m ->
                val me = auth.currentUserId.value ?: return@PersonScreen
                person?.let { p -> scope.launch { people.ruleOut(me, listOf(m.userId), p.key) } }
            },
            onUnlink = { id ->
                val me = auth.currentUserId.value ?: return@PersonScreen
                scope.launch { people.unlink(me, id) }
            },
            onLinkOtherNames = onLinkOtherNames,
            onOpenExpense = onOpenExpense,
        )
        if (paying && person != null) {
            RecordPaymentSheet(
                person = person,
                onDismiss = { paying = false },
                onSave = { currency, split ->
                    val me = auth.currentUserId.value ?: return@RecordPaymentSheet
                    scope.launch {
                        if (people.recordPayment(me, person, currency, split) is AppResult.Ok) paying = false
                    }
                },
            )
        }
    }
}

/** "Link other names" for one person. */
@Composable
fun LinkNamesRoute(
    personKey: String,
    onBack: () -> Unit,
) {
    val people = koinInject<PeopleRepository>()
    val auth = koinInject<AuthSession>()
    val scope = rememberCoroutineScope()
    val summary = rememberPeople()
    val person = summary?.let { s -> (s.owing + s.settled).firstOrNull { it.key == personKey || personKey in it.memberIds } }
    if (summary == null || person == null) {
        // Page-colored, never transparent: an empty Box shows the window through as black on iOS.
        Box(Modifier.fillMaxSize().background(EvenlyTheme.colors.page))
        return
    }
    val leftOut =
        (person.groups.map { it.groupName } + person.otherNames.map { it.groupName })
            .distinct()
    LinkNamesScreen(
        personName = person.name,
        leftOut = leftOut,
        candidates = linkPool(person, summary),
        onBack = onBack,
        onSave = { ids ->
            val me = auth.currentUserId.value ?: return@LinkNamesScreen
            scope.launch {
                people.link(me, ids, person.key)
                onBack()
            }
        },
    )
}
