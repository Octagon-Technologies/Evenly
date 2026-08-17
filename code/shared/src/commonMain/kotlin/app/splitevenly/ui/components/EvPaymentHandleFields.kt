@file:Suppress("FunctionNaming") // Composables are PascalCase, as everywhere else in ui/.

package app.splitevenly.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.splitevenly.domain.settlement.HandleStage
import app.splitevenly.domain.settlement.HandleVerdict
import app.splitevenly.domain.settlement.PaymentApp
import app.splitevenly.domain.settlement.canonicalPaymentHandle
import app.splitevenly.domain.settlement.checkPaymentHandle
import app.splitevenly.domain.settlement.paymentAppName
import app.splitevenly.domain.settlement.paymentHandleBody
import app.splitevenly.domain.settlement.paymentHandleExample
import app.splitevenly.domain.settlement.paymentHandlePrefix
import app.splitevenly.ui.components.icon.EvIcon
import app.splitevenly.ui.components.icon.EvIcons
import app.splitevenly.ui.theme.EvenlyTheme

/** Display order. Venmo and Cash App first because they are the two most people reach for. */
private val HandleOrder = listOf(PaymentApp.VENMO, PaymentApp.CASH_APP, PaymentApp.ZELLE, PaymentApp.PAYPAL)

/**
 * The four payment-handle inputs, shared by the onboarding step and Settings > Payment apps so the
 * two can never disagree about what a valid handle is.
 *
 * Closed rows are a tappable "+" list; opening one turns it into a field with the service's sigil
 * printed as a fixed prefix, a worked example under it, and the live verdict from
 * `PaymentHandleValidator`. Three behaviours are load-bearing:
 *
 * - **A value that belongs elsewhere is offered a one-tap move, not silently refiled.** Refiling it
 *   automatically was tried and reverted: `BasicTextField` reports no paste, only a new value, and the
 *   only way to guess at one is a jump in length. Fast typing and keyboard autocorrect both deliver
 *   several characters per change, so a Cashtag typed into the PayPal field re-homed itself mid-word
 *   and the rest of the keystrokes landed in the other field. Moving a value the user is still typing
 *   destroys input; a tap they chose cannot.
 * - **[showErrors] is the blur signal.** There is no per-field focus callback in `EvTextField`, so the
 *   caller flips this on the first Continue/Save tap and an incomplete value stays quiet until then.
 * - **A misroute never blocks.** Only [HandleVerdict.Invalid] does.
 *
 * [values] and [onValuesChange] carry the canonical form (sigil included); the field itself holds the
 * body. Callers never see the two apart.
 */
@Composable
fun EvPaymentHandleFields(
    values: Map<PaymentApp, String>,
    onValuesChange: (Map<PaymentApp, String>) -> Unit,
    modifier: Modifier = Modifier,
    showErrors: Boolean = false,
) {
    // Rows the user has opened but not yet filled. A row with a value is open by virtue of having one.
    var opened by remember { mutableStateOf(emptySet<PaymentApp>()) }

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        HandleOrder.forEach { app ->
            val canonical = values[app].orEmpty()
            val isOpen = app in opened || canonical.isNotBlank()
            if (isOpen) {
                HandleInput(
                    app = app,
                    canonical = canonical,
                    showErrors = showErrors,
                    onChange = { typed -> onValuesChange(values + (app to canonicalPaymentHandle(app, typed))) },
                    onMoveTo = { target, moved ->
                        opened = opened + target
                        onValuesChange(values + (app to "") + (target to moved))
                    },
                )
            } else {
                ClosedHandleRow(app) { opened = opened + app }
            }
        }
    }
}

@Composable
private fun ClosedHandleRow(
    app: PaymentApp,
    onOpen: () -> Unit,
) {
    val c = EvenlyTheme.colors
    EvSelectField(
        value = paymentAppName(app),
        onClick = onOpen,
        leading = { EvIcon(EvIcons.Wallet, size = 18.dp, tint = c.ink2) },
        trailingIcon = EvIcons.Plus,
    )
}

@Composable
private fun HandleInput(
    app: PaymentApp,
    canonical: String,
    showErrors: Boolean,
    onChange: (typed: String) -> Unit,
    onMoveTo: (target: PaymentApp, canonical: String) -> Unit,
) {
    val c = EvenlyTheme.colors
    val body = paymentHandleBody(app, canonical)
    val stage = if (showErrors) HandleStage.SETTLED else HandleStage.TYPING
    val verdict = checkPaymentHandle(app, canonical, stage)
    val prefix = paymentHandlePrefix(app)

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            paymentAppName(app),
            color = c.ink2,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
        )
        EvTextField(
            value = body,
            onValueChange = onChange,
            placeholder = if (app == PaymentApp.ZELLE) "email or mobile number" else null,
            mono = true,
            identifier = true,
            isError = verdict is HandleVerdict.Invalid,
            leading =
                if (prefix.isEmpty()) {
                    null
                } else {
                    {
                        Text(
                            prefix,
                            color = c.ink3,
                            fontSize = 16.sp,
                            fontFamily = EvenlyTheme.monoFamily,
                        )
                    }
                },
            trailing = { HandleTrailing(verdict) },
        )
        HandleHelper(app, verdict)
        if (verdict is HandleVerdict.WrongApp) {
            MisrouteNudge(verdict) { onMoveTo(verdict.belongsTo, verdict.canonical) }
        }
    }
}

@Composable
private fun HandleTrailing(verdict: HandleVerdict) {
    val c = EvenlyTheme.colors
    when (verdict) {
        is HandleVerdict.Ok -> EvIcon(EvIcons.Check, size = 18.dp, tint = c.blueText)
        is HandleVerdict.Invalid -> EvIcon(EvIcons.Alert, size = 18.dp, tint = c.danger)
        else -> Unit
    }
}

/** One line under the field: the error when there is one, the worked example otherwise. */
@Composable
private fun HandleHelper(
    app: PaymentApp,
    verdict: HandleVerdict,
) {
    val c = EvenlyTheme.colors
    val (text, color) =
        when (verdict) {
            is HandleVerdict.Invalid -> verdict.message to c.danger
            is HandleVerdict.WrongApp -> return
            else -> paymentHandleExample(app) to c.ink3
        }
    Text(text, color = color, fontSize = 12.sp, lineHeight = 17.sp, modifier = Modifier.padding(horizontal = 2.dp))
}

@Composable
private fun MisrouteNudge(
    verdict: HandleVerdict.WrongApp,
    onMove: () -> Unit,
) {
    val c = EvenlyTheme.colors
    val shape = RoundedCornerShape(12.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(c.blueTint)
            .border(1.dp, c.blueTint2, shape)
            .clickable(onClick = onMove)
            .padding(horizontal = 13.dp, vertical = 11.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        EvIcon(EvIcons.Info, size = 16.dp, tint = c.blueText)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(verdict.message, color = c.ink2, fontSize = 12.5.sp, lineHeight = 17.sp)
            Text(
                "Move it to ${paymentAppName(verdict.belongsTo)}",
                color = c.blueText,
                fontSize = 12.5.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}
