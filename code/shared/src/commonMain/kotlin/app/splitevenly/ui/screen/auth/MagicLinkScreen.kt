package app.splitevenly.ui.screen.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.splitevenly.domain.auth.PLAY_REVIEW_DEMO_EMAIL
import app.splitevenly.ui.components.ButtonVariant
import app.splitevenly.ui.components.EvButton
import app.splitevenly.ui.components.EvField
import app.splitevenly.ui.components.EvIconButton
import app.splitevenly.ui.components.EvKeyboardDoneBar
import app.splitevenly.ui.components.EvSpinner
import app.splitevenly.ui.components.EvTextField
import app.splitevenly.ui.components.icon.EvIcon
import app.splitevenly.ui.components.icon.EvIcons
import app.splitevenly.ui.components.imeAndDoneBarPadding
import app.splitevenly.ui.theme.EvenlyTheme

enum class MagicLinkState { Input, Loading, Sent }

/** 2 · Email / magic link (design/src/screens-auth.jsx). */
@Composable
fun MagicLinkScreen(
    state: MagicLinkState = MagicLinkState.Input,
    error: String? = null,
    onBack: () -> Unit = {},
    onSend: (String) -> Unit = {},
    onVerify: (String) -> Unit = {},
    verifying: Boolean = false,
    onResend: () -> Unit = {},
    onPasswordSignIn: (String, String) -> Unit = { _, _ -> },
    allowPassword: Boolean = false,
) {
    val c = EvenlyTheme.colors
    var email by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var shortCodeHint by remember { mutableStateOf(false) }
    var password by remember { mutableStateOf("") }
    val focusManager = LocalFocusManager.current
    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .background(c.page)
                .systemBarsPadding()
                .padding(horizontal = 24.dp),
        ) {
            Box(Modifier.padding(vertical = 8.dp)) { EvIconButton(EvIcons.Back, onBack) }

            if (state == MagicLinkState.Sent) {
                // Open with the keyboard up: the OS shows its code suggestion only above a focused field.
                val codeFocus = remember { FocusRequester() }
                LaunchedEffect(Unit) { codeFocus.requestFocus() }
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .imeAndDoneBarPadding()
                        .padding(bottom = 8.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(Modifier.size(72.dp).clip(RoundedCornerShape(20.dp)).background(c.blueTint), contentAlignment = Alignment.Center) {
                        EvIcon(EvIcons.Mail, size = 34.dp, tint = c.blueText)
                    }
                    Text("Check your email", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = c.ink)
                    Text(
                        buildAnnotatedString {
                            append("We sent a magic link and a sign-in code to ")
                            withStyle(SpanStyle(color = c.ink, fontWeight = FontWeight.SemiBold)) { append(email) }
                            append(". Tap the link, or enter the code below.")
                        },
                        color = c.ink2,
                        fontSize = 15.sp,
                        lineHeight = 22.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.widthIn(max = 280.dp),
                    )
                    EvField("Sign-in code") {
                        EvTextField(
                            code,
                            {
                                // Digits only, so a pasted "Code: 123 456" still lands as the code.
                                val next = it.filter { ch -> ch.isDigit() }.take(OTP_LENGTH)
                                val completed = next.length == OTP_LENGTH && code.length != OTP_LENGTH
                                code = next
                                shortCodeHint = false
                                if (completed && !verifying) onVerify(next)
                            },
                            // The OS reads this hint to offer the code from the notification or Mail.
                            modifier = Modifier.focusRequester(codeFocus).semantics { contentType = ContentType.SmsOtpCode },
                            placeholder = "123456",
                            keyboardType = KeyboardType.Number,
                            identifier = true,
                        )
                    }
                    (if (shortCodeHint) "Enter all $OTP_LENGTH digits of the code." else error)?.let {
                        Text(it, color = c.danger, fontSize = 13.sp, textAlign = TextAlign.Center)
                    }
                    if (verifying) {
                        EvSpinner()
                    } else {
                        EvButton("Verify & sign in", {
                            if (code.length == OTP_LENGTH) onVerify(code) else shortCodeHint = true
                        })
                    }
                    EvButton("Resend code", onResend, variant = ButtonVariant.Text)
                }
            } else {
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .keyboardAwareBody()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterVertically),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Sign in with email", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = c.ink, letterSpacing = (-0.5).sp)
                        Text("We'll email you a link and a sign-in code.", fontSize = 15.sp, color = c.ink2)
                    }
                    EvField("Email address") {
                        EvTextField(email, { email = it }, placeholder = "you@email.com", keyboardType = KeyboardType.Email)
                    }
                    // Debug builds show this for any seeded test account (no email round-trip). In release
                    // it only reveals for PLAY_REVIEW_DEMO_EMAIL, since Play Store reviewers can't complete
                    // an OTP round-trip against a demo inbox that doesn't exist — see SupabaseAuthSession
                    // .signInWithPassword for the matching server-side allowlist.
                    val showPasswordField = allowPassword || email.trim().equals(PLAY_REVIEW_DEMO_EMAIL, ignoreCase = true)
                    if (showPasswordField) {
                        EvField("Password (test accounts, optional)") {
                            EvTextField(password, {
                                password = it
                            }, placeholder = "•••••••", keyboardType = KeyboardType.Password, visualTransformation = PasswordVisualTransformation())
                        }
                    }
                    error?.let { Text(it, color = c.danger, fontSize = 13.sp) }
                    val usePassword = showPasswordField && password.isNotEmpty()
                    EvButton(
                        onClick = { if (usePassword) onPasswordSignIn(email, password) else onSend(email) },
                        enabled = state != MagicLinkState.Loading && email.contains("@"),
                    ) {
                        if (state == MagicLinkState.Loading) {
                            EvSpinner(size = 18.dp)
                            Text("Signing in…", color = LocalContentColor.current, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        } else {
                            EvIcon(EvIcons.Send, size = 20.dp, tint = LocalContentColor.current)
                            Text(
                                if (usePassword) "Sign in" else "Email me a code",
                                color = LocalContentColor.current,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }
            }
        }
        // The sign-in code uses a number pad, which has no return key on iOS. The email form's keyboard has one.
        EvKeyboardDoneBar(
            onDone = { focusManager.clearFocus() },
            modifier = Modifier.align(Alignment.BottomCenter),
            visible = state == MagicLinkState.Sent,
        )
    }
}

/**
 * Ends the body above the software keyboard and lets it scroll if what is left is too short.
 *
 * Both states put their submit button *below* a focused field ("Email me a code" under the address,
 * "Verify & sign in" under the code), so without the ime inset the keyboard covers the only way
 * forward. `imePadding` before `verticalScroll` shrinks the viewport rather than the content, which
 * keeps the centered arrangement re-centering in the visible strip and keeps bring-into-view aiming
 * at the space above the keyboard.
 */
@Composable
private fun Modifier.keyboardAwareBody(): Modifier = this.imePadding().padding(bottom = 8.dp)

@Preview
@Composable
private fun MagicLinkInputPreview() {
    EvenlyTheme { MagicLinkScreen(state = MagicLinkState.Input) }
}

@Preview
@Composable
private fun MagicLinkSentPreview() {
    EvenlyTheme { MagicLinkScreen(state = MagicLinkState.Sent) }
}

/**
 * Must match Email OTP Length in the hosted Supabase dashboard (Authentication > Providers > Email) and
 * `otp_length` in `supabase/config.toml`. One fixed length is what lets the field submit itself on the
 * last digit; a 6-to-8 range would send a valid 8-digit code at 6.
 */
private const val OTP_LENGTH = 6
