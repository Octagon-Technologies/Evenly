package da.chelimo.sharecost.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import da.chelimo.sharecost.core.error.AppResult
import da.chelimo.sharecost.domain.auth.AuthSession
import da.chelimo.sharecost.domain.group.NewGroup
import da.chelimo.sharecost.domain.repository.GroupRepository
import da.chelimo.sharecost.ui.screen.auth.MagicLinkScreen
import da.chelimo.sharecost.ui.screen.auth.OnboardingScreen
import da.chelimo.sharecost.ui.screen.home.HomeScreen
import da.chelimo.sharecost.ui.screen.home.HomeViewModel
import da.chelimo.sharecost.ui.screen.home.NewGroupSheet
import da.chelimo.sharecost.ui.screen.settings.ProfileScreen
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel

/**
 * Stateful wrappers that bind the (stateless, previewable) screens to ViewModels / the auth session.
 * The NavHost renders these for wired destinations; the screens themselves stay free of DI so their
 * `@Preview`s keep working.
 */

@Composable
fun OnboardingRoute(onFinished: () -> Unit) {
    val auth = koinInject<AuthSession>()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    OnboardingScreen(onFinish = { scope.launch { auth.signIn("Alex Rivera"); onFinished() } })
}

@Composable
fun MagicLinkRoute(onBack: () -> Unit, onSent: () -> Unit) {
    val auth = koinInject<AuthSession>()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    MagicLinkScreen(onBack = onBack, onSend = { scope.launch { auth.signIn("You"); onSent() } })
}

@Composable
fun HomeRoute(onOpenGroup: (String) -> Unit, onNewGroup: () -> Unit, onOpenProfile: () -> Unit) {
    val vm = koinViewModel<HomeViewModel>()
    val state by vm.state.collectAsStateWithLifecycle()
    HomeScreen(state = state, onOpenGroup = onOpenGroup, onNewGroup = onNewGroup, onOpenProfile = onOpenProfile)
}

@Composable
fun NewGroupRoute(onDismiss: () -> Unit, onCreated: (String) -> Unit) {
    val groups = koinInject<GroupRepository>()
    val auth = koinInject<AuthSession>()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    NewGroupSheet(
        onDismiss = onDismiss,
        onCreate = { name, emoji ->
            val uid = auth.currentUserId.value ?: return@NewGroupSheet
            scope.launch {
                val input = NewGroup(name = name.ifBlank { "New group" }, baseCurrency = "USD", creatorUserId = uid, emoji = emoji)
                when (val result = groups.createGroup(input)) {
                    is AppResult.Ok -> onCreated(result.value.id.value)
                    is AppResult.Err -> Unit
                }
            }
        },
    )
}

@Composable
fun ProfileRoute(onBack: () -> Unit, onSignedOut: () -> Unit) {
    val auth = koinInject<AuthSession>()
    ProfileScreen(onBack = onBack, onSignOut = { auth.signOut(); onSignedOut() })
}
