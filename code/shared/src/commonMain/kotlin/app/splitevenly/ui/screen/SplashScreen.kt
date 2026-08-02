package app.splitevenly.ui.screen

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.splitevenly.ui.components.EvenlyMark

/**
 * The brand blue the splash paints, on both platforms and in both themes.
 *
 * Deliberately NOT a theme token. The splash is the one surface that must not adapt: the Android
 * windowSplashScreenBackground and the iOS UILaunchScreen colour are baked into resources that
 * cannot read the Compose theme, so this composable has to match them exactly or the hand-off
 * flickers. White-on-blue also means one asset serves light and dark mode.
 */
val EvenlySplashBlue: Color = Color(0xFF3762E3)

/**
 * The on-screen size of the feather, and the contract with both OS launch screens.
 *
 * `LaunchMark.imageset` is authored at exactly 96pt and `ic_splash_mark.xml` puts 96dp of mark on
 * its 288dp canvas, both centred. Drawing the same 96.dp dead-centre here means the OS splash hands
 * off to Compose with the mark not moving or resizing by a pixel: the only visible change is the
 * wordmark fading in beneath it. Change this and you must re-generate both assets.
 */
private val MarkSize = 96.dp

/**
 * Full-bleed brand splash: white feather + "Evenly" on brand blue.
 *
 * Shown while the app resolves where to start (see `App.kt`), which used to be a blank page. The
 * feather is already on screen from the OS launch screen, so only the wordmark animates; a fast
 * resolve then reads as one continuous screen rather than two splashes fighting each other.
 */
@Composable
fun SplashScreen(modifier: Modifier = Modifier) {
    val reveal = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        reveal.animateTo(1f, tween(durationMillis = 420, easing = FastOutSlowInEasing))
    }
    Box(
        modifier = modifier.fillMaxSize().background(EvenlySplashBlue),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = EvenlyMark,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(MarkSize),
        )
        Text(
            text = "Evenly",
            color = Color.White,
            fontSize = 34.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = (-1).sp,
            modifier = Modifier
                .offset(y = MarkSize / 2 + 34.dp)
                .alpha(reveal.value),
        )
    }
}

@Preview
@Composable
private fun SplashPreview() {
    SplashScreen()
}
