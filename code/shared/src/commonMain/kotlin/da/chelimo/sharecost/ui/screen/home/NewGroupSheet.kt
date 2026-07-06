package da.chelimo.sharecost.ui.screen.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import da.chelimo.sharecost.ui.components.ScButton
import da.chelimo.sharecost.ui.components.ScField
import da.chelimo.sharecost.ui.components.ScIconButton
import da.chelimo.sharecost.ui.components.ScSelectField
import da.chelimo.sharecost.ui.components.ScTextField
import da.chelimo.sharecost.ui.components.ScTopBar
import da.chelimo.sharecost.ui.components.icon.ScIcon
import da.chelimo.sharecost.ui.components.icon.ScIcons
import da.chelimo.sharecost.ui.theme.ShareCostTheme

/**
 * 5 · New group — a real full-screen destination, not a sheet. It used to render over a dimmed
 * scrim with tap-outside-to-dismiss as the only way out; a tester couldn't tell it was dismissible
 * and got stuck. A back arrow in a normal top bar removes that ambiguity entirely.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NewGroupSheet(
    onDismiss: () -> Unit = {},
    onCreate: (name: String, emoji: String) -> Unit = { _, _ -> },
) {
    val c = ShareCostTheme.colors
    val emojis = listOf("💸", "🏝️", "🏠", "🍝", "✈️", "🎉", "⛷️", "🎂")
    var sel by remember { mutableStateOf(emojis.first()) }
    var name by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize().background(c.page)) {
        ScTopBar(title = "New group", navIcon = { ScIconButton(ScIcons.Back, onClick = onDismiss) })
        Column(
            Modifier.fillMaxSize().weight(1f).verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(72.dp).clip(RoundedCornerShape(22.dp)).background(c.surface).border(1.dp, c.border, RoundedCornerShape(22.dp)), contentAlignment = Alignment.Center) {
                    Text(sel, fontSize = 38.sp)
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    emojis.forEach { e ->
                        val on = e == sel
                        Box(
                            Modifier.size(40.dp).clip(RoundedCornerShape(11.dp))
                                .background(if (on) c.blueTint else c.surface)
                                .border(if (on) 2.dp else 1.dp, if (on) c.blue else c.border, RoundedCornerShape(11.dp))
                                .clickable { sel = e },
                            contentAlignment = Alignment.Center,
                        ) { Text(e, fontSize = 22.sp) }
                    }
                }
            }
            ScField("Group name") { ScTextField(name, { name = it }, placeholder = "e.g. Trip to Puerto Rico") }
            ScField("Base currency") {
                ScSelectField("USD — US Dollar", {}, leading = { ScIcon(ScIcons.Globe, size = 18.dp, tint = c.ink2) })
            }
        }
        Column(Modifier.fillMaxWidth().padding(20.dp)) {
            ScButton("Create group", { onCreate(name, sel) }, leadingIcon = ScIcons.Check)
        }
    }
}

@Preview
@Composable
private fun NewGroupPreview() {
    ShareCostTheme { NewGroupSheet() }
}
