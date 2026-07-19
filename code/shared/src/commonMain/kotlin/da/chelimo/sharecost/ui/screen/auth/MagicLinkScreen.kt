package da.chelimo.sharecost.ui.screen.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import da.chelimo.sharecost.ui.components.ScButton
import da.chelimo.sharecost.ui.components.ScField
import da.chelimo.sharecost.ui.components.ScIconButton
import da.chelimo.sharecost.ui.components.ScSpinner
import da.chelimo.sharecost.ui.components.ScTextField
import da.chelimo.sharecost.ui.components.ButtonVariant
import da.chelimo.sharecost.ui.components.icon.ScIcon
import da.chelimo.sharecost.ui.components.icon.ScIcons
import da.chelimo.sharecost.ui.theme.ShareCostTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation

enum class MagicLinkState { Input, Loading, Sent }

/** 2 · Email / magic link (design/src/screens-auth.jsx). */
@Composable
fun MagicLinkScreen(
    state: MagicLinkState = MagicLinkState.Input,
    error: String? = null,
    onBack: () -> Unit = {},
    onSend: (String) -> Unit = {},
    onVerify: (String) -> Unit = {},
    onResend: () -> Unit = {},
    onPasswordSignIn: (String, String) -> Unit = { _, _ -> },
    allowPassword: Boolean = false,
) {
    val c = ShareCostTheme.colors
    var email by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().background(c.page).systemBarsPadding().padding(horizontal = 24.dp)) {
        Box(Modifier.padding(vertical = 8.dp)) { ScIconButton(ScIcons.Back, onBack) }

        if (state == MagicLinkState.Sent) {
            Column(
                Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(Modifier.size(72.dp).clip(RoundedCornerShape(20.dp)).background(c.blueTint), contentAlignment = Alignment.Center) {
                    ScIcon(ScIcons.Mail, size = 34.dp, tint = c.blueText)
                }
                Text("Check your email", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = c.ink)
                Text(
                    buildAnnotatedString {
                        append("We sent a magic link + 6-digit code to ")
                        withStyle(SpanStyle(color = c.ink, fontWeight = FontWeight.SemiBold)) { append(email) }
                        append(". Tap the link, or enter the code below.")
                    },
                    color = c.ink2, fontSize = 15.sp, lineHeight = 22.sp, textAlign = TextAlign.Center,
                    modifier = Modifier.widthIn(max = 280.dp),
                )
                ScField("6-digit code") {
                    ScTextField(code, { code = it.filter { ch -> ch.isDigit() }.take(6) }, placeholder = "123456", keyboardType = KeyboardType.Number)
                }
                error?.let { Text(it, color = c.danger, fontSize = 13.sp, textAlign = TextAlign.Center) }
                ScButton("Verify & sign in", { onVerify(code) }, enabled = code.length == 6)
                ScButton("Resend code", onResend, variant = ButtonVariant.Text)
            }
        } else {
            Column(
                Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterVertically),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Sign in with email", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = c.ink, letterSpacing = (-0.5).sp)
                    Text("We'll email you a link and a 6-digit code.", fontSize = 15.sp, color = c.ink2)
                }
                ScField("Email address") {
                    ScTextField(email, { email = it }, placeholder = "you@email.com", keyboardType = KeyboardType.Email)
                }
                // Debug-only: seeded test accounts sign in with a password (no email round-trip). Hidden
                // in release builds (see isDebugBuild()); leave blank for the normal magic-link / OTP flow.
                if (allowPassword) {
                    ScField("Password (test accounts, optional)") {
                        ScTextField(password, { password = it }, placeholder = "•••••••", keyboardType = KeyboardType.Password, visualTransformation = PasswordVisualTransformation())
                    }
                }
                error?.let { Text(it, color = c.danger, fontSize = 13.sp) }
                val usePassword = allowPassword && password.isNotEmpty()
                ScButton(
                    onClick = { if (usePassword) onPasswordSignIn(email, password) else onSend(email) },
                    enabled = state != MagicLinkState.Loading && email.contains("@"),
                ) {
                    if (state == MagicLinkState.Loading) {
                        ScSpinner(size = 18.dp)
                        Text("Signing in…", color = LocalContentColor.current, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    } else {
                        ScIcon(ScIcons.Send, size = 20.dp, tint = LocalContentColor.current)
                        Text(if (usePassword) "Sign in" else "Email me a code", color = LocalContentColor.current, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

@Preview
@Composable
private fun MagicLinkInputPreview() {
    ShareCostTheme { MagicLinkScreen(state = MagicLinkState.Input) }
}

@Preview
@Composable
private fun MagicLinkSentPreview() {
    ShareCostTheme { MagicLinkScreen(state = MagicLinkState.Sent) }
}
