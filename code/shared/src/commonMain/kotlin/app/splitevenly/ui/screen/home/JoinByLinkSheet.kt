package app.splitevenly.ui.screen.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.splitevenly.ui.components.EvButton
import app.splitevenly.ui.components.EvField
import app.splitevenly.ui.components.EvIconButton
import app.splitevenly.ui.components.EvTextField
import app.splitevenly.ui.components.EvTopBar
import app.splitevenly.ui.components.icon.EvIcons
import app.splitevenly.ui.theme.EvenlyTheme

/**
 * Paste-an-invite front door (the manual counterpart to the `splitevenly://j/{token}` deep link).
 * A real full-screen destination, not a sheet-over-a-blank-page: same shape as [NewGroupSheet]
 * (back arrow in a top bar + scrollable body + pinned footer action) so the two entry points to a
 * group feel identical.
 */
@Composable
fun JoinByLinkSheet(onDismiss: () -> Unit = {}, onSubmit: (token: String) -> Unit = {}) {
    val c = EvenlyTheme.colors
    var link by remember { mutableStateOf("") }
    val token = extractInviteToken(link)
    Column(Modifier.fillMaxSize().background(c.page).systemBarsPadding()) {
        EvTopBar(title = "Join with a link", navIcon = { EvIconButton(EvIcons.Back, onClick = onDismiss) })
        Column(
            Modifier.fillMaxSize().weight(1f).verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                "Paste the invite link or code someone shared with you.",
                color = c.ink2, fontSize = 14.sp, lineHeight = 21.sp,
            )
            EvField("Invite link") { EvTextField(link, { link = it }, placeholder = "splitevenly://j/…") }
        }
        Column(Modifier.fillMaxWidth().padding(20.dp)) {
            EvButton("Continue", { token?.let(onSubmit) }, enabled = token != null, leadingIcon = EvIcons.Link)
        }
    }
}

/** The invite token from a pasted link or raw code: the path segment after the last '/', sans query. */
fun extractInviteToken(raw: String): String? {
    val token = raw.trim().trimEnd('/').substringAfterLast('/').substringBefore('?').trim()
    return token.ifBlank { null }
}

@Preview
@Composable
private fun JoinByLinkPreview() {
    EvenlyTheme { JoinByLinkSheet() }
}
