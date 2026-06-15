package da.chelimo.sharecost.ui.screen.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import da.chelimo.sharecost.ui.components.ScOAuthButton
import da.chelimo.sharecost.ui.components.ScWordmark
import da.chelimo.sharecost.ui.components.icon.ScIcons
import da.chelimo.sharecost.ui.theme.ShareCostTheme

/** 1 · Sign-in (design/src/screens-auth.jsx). */
@Composable
fun SignInScreen(
    onProvider: (String) -> Unit = {},
    onLegal: (String) -> Unit = {},
) {
    val c = ShareCostTheme.colors
    Column(
        Modifier.fillMaxSize().background(c.page).systemBarsPadding().padding(horizontal = 24.dp),
    ) {
        Column(
            Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.Start,
        ) {
            ScWordmark(size = 38.dp)
            Text(
                "Track shared expenses.\nPay through your own app.",
                color = c.ink2,
                fontSize = 19.sp,
                fontWeight = FontWeight.Medium,
                lineHeight = 28.sp,
                modifier = Modifier.widthIn(max = 280.dp),
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(bottom = 8.dp)) {
            ScOAuthButton("Continue with Google", ScIcons.Google, { onProvider("google") }, iconTint = c.ink)
            ScOAuthButton("Continue with Apple", ScIcons.Apple, { onProvider("apple") }, iconTint = c.ink)
            ScOAuthButton("Continue with Facebook", ScIcons.Facebook, { onProvider("facebook") }, iconTint = c.blue)
            ScOAuthButton("Continue with Email", ScIcons.Mail, { onProvider("mail") }, iconTint = c.ink)
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        ) {
            Text("Privacy", color = c.ink3, fontSize = 12.sp, modifier = Modifier.clickable { onLegal("privacy") })
            Text("·", color = c.ink3, fontSize = 12.sp)
            Text("Terms", color = c.ink3, fontSize = 12.sp, modifier = Modifier.clickable { onLegal("terms") })
        }
    }
}

@Preview
@Composable
private fun SignInPreview() {
    ShareCostTheme { SignInScreen() }
}
