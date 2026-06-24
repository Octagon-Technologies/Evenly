package da.chelimo.sharecost.ui.screen.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import da.chelimo.sharecost.ui.components.ScButton
import da.chelimo.sharecost.ui.components.ScField
import da.chelimo.sharecost.ui.components.ScSheetScaffold
import da.chelimo.sharecost.ui.components.ScTextField
import da.chelimo.sharecost.ui.components.icon.ScIcons
import da.chelimo.sharecost.ui.theme.ShareCostTheme

/** Paste-an-invite front door (the manual counterpart to the `sharecost://j/{token}` deep link). */
@Composable
fun JoinByLinkSheet(onDismiss: () -> Unit = {}, onSubmit: (token: String) -> Unit = {}) {
    val c = ShareCostTheme.colors
    var link by remember { mutableStateOf("") }
    val token = extractInviteToken(link)
    Box(Modifier.fillMaxSize().background(c.surface)) {
        ScSheetScaffold(onDismiss, title = "Join with a link", sub = "Paste the invite link or code someone shared with you.") {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                ScField("Invite link") { ScTextField(link, { link = it }, placeholder = "sharecost://j/…") }
                ScButton("Continue", { token?.let(onSubmit) }, enabled = token != null, leadingIcon = ScIcons.Link)
            }
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
    ShareCostTheme { JoinByLinkSheet() }
}
