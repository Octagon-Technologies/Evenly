package da.chelimo.sharecost.ui.screen.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import da.chelimo.sharecost.ui.components.ButtonVariant
import da.chelimo.sharecost.ui.components.ScButton
import da.chelimo.sharecost.ui.components.ScCard
import da.chelimo.sharecost.ui.components.ScField
import da.chelimo.sharecost.ui.components.ScListCard
import da.chelimo.sharecost.ui.components.ScSelectField
import da.chelimo.sharecost.ui.components.ScTextField
import da.chelimo.sharecost.ui.components.ScToggle
import da.chelimo.sharecost.ui.components.icon.ScIcon
import da.chelimo.sharecost.ui.components.icon.ScIcons
import da.chelimo.sharecost.ui.theme.ShareCostTheme

/** 3 · First-launch onboarding carousel (design/src/screens-auth.jsx). */
@Composable
fun OnboardingScreen(
    initialName: String = "",
    initialCurrency: String = "USD",
    onFinish: (name: String, baseCurrency: String) -> Unit = { _, _ -> },
) {
    val c = ShareCostTheme.colors
    val steps = listOf("name", "currency", "handle", "analytics", "notify")
    var step by remember { mutableStateOf(0) }
    var name by remember { mutableStateOf(initialName) }
    var currency by remember { mutableStateOf(initialCurrency) }
    var analytics by remember { mutableStateOf(true) }
    val cur = steps[step]
    fun next() { if (step < steps.lastIndex) step++ else onFinish(name, currency) }

    Column(Modifier.fillMaxSize().background(c.page).systemBarsPadding().padding(horizontal = 24.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                steps.indices.forEach { i ->
                    Box(Modifier.width(if (i == step) 22.dp else 7.dp).height(7.dp).clip(CircleShape).background(if (i == step) c.blue else c.borderStrong))
                }
            }
            Spacer(Modifier.weight(1f))
            if (step < steps.lastIndex && cur != "name") {
                ScButton("Skip", { next() }, variant = ButtonVariant.Text)
            }
        }

        OnbBody(
            modifier = Modifier.weight(1f),
            icon = when (cur) { "name" -> ScIcons.User; "currency" -> ScIcons.Globe; "handle" -> ScIcons.Wallet; "analytics" -> ScIcons.Chart; else -> ScIcons.Bell },
            title = when (cur) {
                "name" -> "What should we call you?"
                "currency" -> "Pick your base currency"
                "handle" -> "Add a payment handle"
                "analytics" -> "Help improve ShareCost"
                else -> "Stay in the loop"
            },
            text = when (cur) {
                "name" -> "This is how friends see you in groups."
                "currency" -> "Used as the default for new groups. You can change it per group."
                "handle" -> "So friends can pay you back in one tap. Optional — you can skip."
                "analytics" -> "Share anonymous usage data. No expense details, ever."
                else -> "Get notified when someone adds an expense or pays you back."
            },
        ) {
            when (cur) {
                "name" -> ScField("Display name") { ScTextField(name, { name = it }) }
                "currency" -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    ScSelectField("Search currency…", {}, leading = { ScIcon(ScIcons.Search, size = 18.dp, tint = c.ink3) }, trailingIcon = ScIcons.Search, valueColor = c.ink3)
                    ScListCard(items = listOf("USD" to "US Dollar", "EUR" to "Euro", "GBP" to "British Pound", "MXN" to "Mexican Peso")) { (code, label) ->
                        Row(Modifier.fillMaxWidth().clickable { currency = code }.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(code, color = c.ink, fontWeight = FontWeight.SemiBold, fontFamily = ShareCostTheme.monoFamily, modifier = Modifier.width(44.dp))
                            Text(label, color = c.ink, fontSize = 15.sp, modifier = Modifier.weight(1f))
                            if (code == currency) ScIcon(ScIcons.Check, size = 20.dp, tint = c.blue)
                        }
                    }
                }
                "handle" -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("Venmo", "Cash App", "Zelle", "PayPal").forEach { app ->
                        ScSelectField(app, {}, leading = { ScIcon(ScIcons.Wallet, size = 18.dp, tint = c.ink2) }, trailingIcon = ScIcons.Plus)
                    }
                }
                "analytics" -> ScCard(padded = true) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Anonymous analytics", color = c.ink, fontWeight = FontWeight.SemiBold)
                            Text("On by default", color = c.ink2, fontSize = 12.sp)
                        }
                        ScToggle(analytics, { analytics = it })
                    }
                }
                else -> ScCard(padded = true) {
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Box(Modifier.size(56.dp).clip(RoundedCornerShape(20.dp)).background(c.blueTint), contentAlignment = Alignment.Center) {
                            ScIcon(ScIcons.Bell, size = 28.dp, tint = c.blue)
                        }
                        Text("We'll ask your device for permission next.", color = c.ink2, fontSize = 12.sp, textAlign = TextAlign.Center)
                    }
                }
            }
        }

        Box(Modifier.padding(bottom = 8.dp)) {
            ScButton(
                text = if (cur == "notify") "Enable & finish" else "Continue",
                onClick = { next() },
                leadingIcon = if (cur == "notify") ScIcons.Check else ScIcons.ChevR,
            )
        }
    }
}

@Composable
private fun OnbBody(
    icon: ImageVector,
    title: String,
    text: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val c = ShareCostTheme.colors
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically)) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Box(Modifier.size(56.dp).clip(RoundedCornerShape(18.dp)).background(c.blueTint), contentAlignment = Alignment.Center) {
                ScIcon(icon, size = 28.dp, tint = c.blue)
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(title, fontSize = 26.sp, fontWeight = FontWeight.Bold, color = c.ink, letterSpacing = (-0.5).sp)
                Text(text, fontSize = 15.sp, color = c.ink2, lineHeight = 22.sp)
            }
        }
        content()
    }
}

@Preview
@Composable
private fun OnboardingPreview() {
    ShareCostTheme { OnboardingScreen() }
}
