package da.chelimo.sharecost.ui.screen.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import da.chelimo.sharecost.domain.settlement.PaymentApp
import da.chelimo.sharecost.ui.components.ScButton
import da.chelimo.sharecost.ui.components.ScField
import da.chelimo.sharecost.ui.components.ScIconButton
import da.chelimo.sharecost.ui.components.ScTextField
import da.chelimo.sharecost.ui.components.ScTopBar
import da.chelimo.sharecost.ui.components.icon.ScIcons
import da.chelimo.sharecost.ui.theme.ShareCostTheme

private data class HandleField(val app: PaymentApp, val label: String, val placeholder: String)

private val HandleFields = listOf(
    HandleField(PaymentApp.VENMO, "Venmo", "@your-handle"),
    HandleField(PaymentApp.CASH_APP, "Cash App", "\$yourhandle"),
    HandleField(PaymentApp.PAYPAL, "PayPal", "paypal.me/you"),
    HandleField(PaymentApp.ZELLE, "Zelle", "email or phone"),
)

/**
 * Payment-apps editor (design/src/screens-settings.jsx onboarding handle step). One field per app;
 * group members deep-link into whichever you fill in. Blank fields clear that app. Wired via
 * PaymentHandlesRoute.
 */
@Composable
fun PaymentHandlesScreen(
    initial: Map<PaymentApp, String> = emptyMap(),
    saving: Boolean = false,
    onBack: () -> Unit = {},
    onSave: (Map<PaymentApp, String>) -> Unit = {},
) {
    val c = ShareCostTheme.colors
    var values by remember(initial) { mutableStateOf(initial) }

    Column(Modifier.fillMaxSize().background(c.surface).systemBarsPadding()) {
        ScTopBar(
            title = "Payment apps",
            navIcon = { ScIconButton(ScIcons.Back, onBack) },
        )
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                "Add the handles people can pay you with. Group members see only the apps you fill in, and pay you in one tap.",
                color = c.ink2, fontSize = 13.sp,
            )
            HandleFields.forEach { f ->
                ScField(f.label) {
                    ScTextField(
                        value = values[f.app].orEmpty(),
                        onValueChange = { values = values + (f.app to it) },
                        placeholder = f.placeholder,
                        mono = true,
                    )
                }
            }
            Box(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                ScButton("Save", { onSave(values) }, leadingIcon = ScIcons.Check, enabled = !saving)
            }
        }
    }
}

@Preview
@Composable
private fun PaymentHandlesPreview() {
    ShareCostTheme { PaymentHandlesScreen(initial = mapOf(PaymentApp.VENMO to "@alex-r")) }
}
