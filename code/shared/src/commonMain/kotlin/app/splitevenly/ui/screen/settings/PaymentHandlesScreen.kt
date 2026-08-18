package app.splitevenly.ui.screen.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.splitevenly.domain.settlement.PaymentApp
import app.splitevenly.domain.settlement.canonicalPaymentHandle
import app.splitevenly.domain.settlement.paymentAppName
import app.splitevenly.domain.settlement.paymentHandlesAreSaveable
import app.splitevenly.domain.settlement.resolvePreferredPaymentApp
import app.splitevenly.ui.components.EvButton
import app.splitevenly.ui.components.EvIconButton
import app.splitevenly.ui.components.EvParticipantChip
import app.splitevenly.ui.components.EvPaymentHandleFields
import app.splitevenly.ui.components.EvTopBar
import app.splitevenly.ui.components.icon.EvIcon
import app.splitevenly.ui.components.icon.EvIcons
import app.splitevenly.ui.theme.EvenlyTheme

/**
 * Payment-apps editor (design/src/screens-settings.jsx onboarding handle step). One field per app;
 * group members deep-link into whichever you fill in. Blank fields clear that app. You can also mark
 * one filled app as *preferred* — that's the default others see when they settle with you. Wired via
 * PaymentHandlesRoute.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PaymentHandlesScreen(
    initial: Map<PaymentApp, String> = emptyMap(),
    initialPreferred: PaymentApp? = null,
    saving: Boolean = false,
    onBack: () -> Unit = {},
    onSave: (handles: Map<PaymentApp, String>, preferred: PaymentApp?) -> Unit = { _, _ -> },
) {
    val c = EvenlyTheme.colors
    // Handles arrive in whatever shape an older client stored them, so they are normalized on read
    // rather than migrated in place: a sync from that client cannot then resurrect an odd one.
    var values by remember(initial) {
        mutableStateOf(initial.mapValues { (app, raw) -> canonicalPaymentHandle(app, raw) })
    }
    var preferred by remember(initialPreferred) { mutableStateOf(initialPreferred) }
    var checked by remember { mutableStateOf(false) }

    // Only apps with a non-blank handle can be preferred. The first one added takes the slot on its
    // own, and losing its handle hands the slot to the next; see resolvePreferredPaymentApp.
    val filled = PaymentApp.entries.filter { values[it]?.isNotBlank() == true }
    val effectivePreferred = resolvePreferredPaymentApp(values, preferred)

    Column(Modifier.fillMaxSize().background(c.page).systemBarsPadding()) {
        EvTopBar(
            title = "Payment apps",
            navIcon = { EvIconButton(EvIcons.Back, onBack) },
        )
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                "Add the handles people can pay you with. Group members see only the apps you fill in, and pay you in one tap.",
                color = c.ink2,
                fontSize = 13.sp,
            )
            EvPaymentHandleFields(
                values = values,
                onValuesChange = { values = it },
                showErrors = checked,
            )

            if (filled.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Preferred method", color = c.ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Text("The one people see first when they pay you back.", color = c.ink2, fontSize = 12.sp)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        filled.forEach { app ->
                            val on = app == effectivePreferred
                            EvParticipantChip(
                                paymentAppName(app),
                                selected = on,
                                leading = { EvIcon(if (on) EvIcons.Star else EvIcons.Wallet, size = 15.dp) },
                                // No tap-to-clear: one filled app is always the preferred one, so the
                                // only meaningful tap is on a different one.
                                onClick = { preferred = app },
                            )
                        }
                    }
                }
            }

            Box(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                // Live even when a handle is malformed: tapping reveals what is wrong rather than
                // greying out with no explanation (ui/AGENTS.md, "never leave a silent dead end").
                EvButton(
                    text = "Save",
                    onClick = {
                        checked = true
                        if (paymentHandlesAreSaveable(values)) onSave(values, effectivePreferred)
                    },
                    leadingIcon = EvIcons.Check,
                    enabled = !saving,
                )
            }
        }
    }
}

@Preview
@Composable
private fun PaymentHandlesPreview() {
    EvenlyTheme {
        PaymentHandlesScreen(
            initial = mapOf(PaymentApp.VENMO to "@alex-r", PaymentApp.CASH_APP to "\$alexr"),
            initialPreferred = PaymentApp.VENMO,
        )
    }
}
