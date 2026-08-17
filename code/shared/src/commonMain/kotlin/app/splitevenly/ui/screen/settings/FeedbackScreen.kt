package app.splitevenly.ui.screen.settings

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.splitevenly.domain.feedback.FEEDBACK_COUNTER_FROM
import app.splitevenly.domain.feedback.FEEDBACK_WORD_CAP
import app.splitevenly.domain.feedback.FeedbackCategory
import app.splitevenly.domain.feedback.FeedbackDraft
import app.splitevenly.domain.feedback.FeedbackGap
import app.splitevenly.domain.feedback.FeedbackOutcome
import app.splitevenly.domain.feedback.FeedbackType
import app.splitevenly.domain.feedback.feedbackEditAllowed
import app.splitevenly.domain.feedback.feedbackWordCount
import app.splitevenly.domain.feedback.gaps
import app.splitevenly.platform.isReduceMotionEnabled
import app.splitevenly.ui.components.ButtonVariant
import app.splitevenly.ui.components.ChipVariant
import app.splitevenly.ui.components.EvButton
import app.splitevenly.ui.components.EvChip
import app.splitevenly.ui.components.EvField
import app.splitevenly.ui.components.EvIconButton
import app.splitevenly.ui.components.EvSegmented
import app.splitevenly.ui.components.EvTextField
import app.splitevenly.ui.components.EvTopBar
import app.splitevenly.ui.components.StatusBarScrim
import app.splitevenly.ui.components.icon.EvIcon
import app.splitevenly.ui.components.icon.EvIcons
import app.splitevenly.ui.theme.EvenlyTheme
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * The in-app feedback form (ADMIN_FEEDBACK_SPEC.md §4.2, step 8).
 *
 * Six category chips wrap rather than collapsing into a picker sheet: the spec budgets "one tap, one
 * tap, one paragraph", and a sheet spends the second tap on opening and closing itself.
 *
 * Three answers and nothing else: no name and no email, because in-app the person is signed in and
 * asking them to type their own address is the exact friction this screen exists to remove. The server
 * ignores both fields from an app source anyway.
 *
 * DI-free by the layer's rule, so `@Preview` works: `FeedbackRoute` does the wiring.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun FeedbackScreen(
    onBack: () -> Unit,
    onSubmit: (FeedbackDraft) -> Unit = {},
    submitting: Boolean = false,
    offline: Boolean = false,
    outcome: FeedbackOutcome? = null,
    onDone: () -> Unit = {},
) {
    val c = EvenlyTheme.colors

    var type by remember { mutableStateOf<FeedbackType?>(null) }
    var category by remember { mutableStateOf<FeedbackCategory?>(null) }
    var message by remember { mutableStateOf("") }

    // Set only by a tap on Send. Until then the screen shows no red: marking a form incomplete before
    // anyone has tried to complete it is nagging, not helping.
    var showGaps by remember { mutableStateOf(false) }

    val draft = FeedbackDraft(type, category, message)
    val gaps = draft.gaps()

    Column(Modifier.fillMaxSize().background(c.page)) {
        StatusBarScrim()
        EvTopBar(title = "Feedback", navIcon = { EvIconButton(EvIcons.Back, onBack) })

        if (outcome == FeedbackOutcome.Sent || outcome == FeedbackOutcome.Queued) {
            FeedbackThankYou(
                type = type ?: FeedbackType.Suggestion,
                queued = outcome == FeedbackOutcome.Queued,
                offline = offline,
                onDone = onDone,
            )
            return@Column
        }

        if (offline) {
            Row(
                Modifier.fillMaxWidth().background(c.bannerOffline).padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                EvIcon(EvIcons.WifiOff, size = 16.dp, tint = c.bannerOfflineInk)
                Text(
                    "No connection. This sends when you're back online.",
                    color = c.bannerOfflineInk,
                    fontSize = 13.sp,
                )
            }
        }

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(horizontal = 16.dp)
                .padding(top = 20.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            EvField("What kind of feedback?") {
                Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    EvSegmented(
                        options = FeedbackType.entries.map { it.label },
                        selected = type?.label,
                        onSelect = { label -> type = FeedbackType.entries.first { it.label == label } },
                    )
                    GapHint(show = showGaps && FeedbackGap.Type in gaps, text = "Pick one.")
                }
            }

            EvField("What's it about?") {
                Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        FeedbackCategory.entries.forEach { option ->
                            val on = category == option
                            EvChip(
                                text = option.label,
                                variant = if (on) ChipVariant.Blue else ChipVariant.Neutral,
                                large = true,
                                modifier = Modifier.selectable(selected = on) { category = option },
                            )
                        }
                    }
                    GapHint(show = showGaps && FeedbackGap.Category in gaps, text = "Pick one.")
                }
            }

            EvField("Your message") {
                Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    EvTextField(
                        value = message,
                        // Rejecting the edit rather than truncating it is spec §4.3: typing stops at the
                        // cap so nobody loses their last sentence at the moment they hit Send.
                        onValueChange = { next -> if (feedbackEditAllowed(message, next)) message = next },
                        placeholder = placeholderFor(type),
                        singleLine = false,
                        minHeight = 132.dp,
                        isError = showGaps && FeedbackGap.Message in gaps,
                    )
                    val words = feedbackWordCount(message)
                    if (words >= FEEDBACK_COUNTER_FROM) {
                        Text(
                            "${FEEDBACK_WORD_CAP - words} words left",
                            color = c.ink2,
                            fontSize = 12.5.sp,
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.End,
                        )
                    }
                    GapHint(
                        show = showGaps && FeedbackGap.Message in gaps,
                        text = "Add a few words so we know what to look at.",
                    )
                }
            }

            outcome?.let { ErrorNote(it) }

            // Deliberately never disabled. Tapping with gaps marks them in place; a greyed-out control
            // with no explanation is the dead end `ui/AGENTS.md` forbids.
            EvButton(
                onClick = {
                    if (gaps.isNotEmpty()) {
                        showGaps = true
                    } else if (!submitting) {
                        onSubmit(draft)
                    }
                },
                variant = ButtonVariant.PrimarySolid,
            ) {
                if (submitting) {
                    CircularProgressIndicator(Modifier.size(18.dp), color = c.onAccent, strokeWidth = 2.dp)
                    Text("Sending", color = c.onAccent, fontSize = 16.sp)
                } else {
                    Text("Send feedback", color = c.onAccent, fontSize = 16.sp)
                }
            }

            Text(
                "Your app version is sent with this.",
                color = c.ink3,
                fontSize = 12.sp,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * The prompt changes with the type because one generic line cannot serve all three: "what did you
 * expect instead" is the right question for a bug and a confusing one for a feature request.
 */
private fun placeholderFor(type: FeedbackType?): String =
    when (type) {
        FeedbackType.Problem -> "What happened, and what did you expect instead?"
        FeedbackType.Suggestion -> "What would you like Evenly to do?"
        FeedbackType.Question -> "What would you like to know?"
        null -> "Tell us as much as you can."
    }

@Composable
private fun GapHint(
    show: Boolean,
    text: String,
) {
    if (!show) return
    Text(text, color = EvenlyTheme.colors.danger, fontSize = 12.5.sp)
}

/** The three outcomes that leave the person on the form with their words intact. */
@Composable
private fun ErrorNote(outcome: FeedbackOutcome) {
    val c = EvenlyTheme.colors
    val text =
        when (outcome) {
            FeedbackOutcome.NeedsSignIn -> "Your session expired. Sign in again and this sends on its own."
            FeedbackOutcome.RateLimited -> "That's a lot of feedback in one hour. Try again a bit later."
            FeedbackOutcome.Failed -> "Something went wrong and this couldn't be sent."
            else -> return
        }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(c.dangerTint)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        EvIcon(EvIcons.Alert, size = 16.dp, tint = c.danger)
        Text(text, color = c.danger, fontSize = 13.sp, lineHeight = 18.sp)
    }
}

/**
 * The feedback thank-you screen (build-order step 5, `THANKYOU_SCREEN_PROMPT.md`). Icon, motion and
 * headline are keyed to [type]; the subtitle is overridden when the ticket hasn't actually gone out
 * yet, since a delivery promise ("we'll email you") should describe what happened, not what type was
 * picked. In-app always has an account behind it, so unlike the anonymous web form there is no
 * no-email variant of the Problem copy here (`THANKYOU_SCREEN_PROMPT.md` Q6).
 */
@Composable
private fun FeedbackThankYou(
    type: FeedbackType,
    queued: Boolean,
    offline: Boolean,
    onDone: () -> Unit,
) {
    val c = EvenlyTheme.colors
    Column(
        Modifier.fillMaxSize().navigationBarsPadding().padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically),
    ) {
        FeedbackThankYouIcon(type)
        Text(headlineFor(type), color = c.ink, fontSize = 19.sp, textAlign = TextAlign.Center)
        Text(
            // "Back online" is only true when the DEVICE is the reason. A ticket also queues when the
            // server cannot be reached from a perfectly good connection, and telling someone holding
            // four bars to get back online reads as the app being broken twice over.
            subtitleFor(type, queued, offline),
            color = c.ink2,
            fontSize = 14.sp,
            lineHeight = 21.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 260.dp),
        )
        EvButton("Done", onDone, modifier = Modifier.widthIn(max = 240.dp).padding(top = 6.dp))
    }
}

private fun headlineFor(type: FeedbackType): String =
    when (type) {
        FeedbackType.Problem -> "We've got this."
        FeedbackType.Suggestion -> "Noted, and thank you."
        FeedbackType.Question -> "We'll get you an answer."
    }

private fun subtitleFor(
    type: FeedbackType,
    queued: Boolean,
    offline: Boolean,
): String {
    if (queued && offline) return "It sends as soon as you're back online."
    if (queued) return "It hasn't gone out yet. Evenly keeps trying in the background."
    return when (type) {
        FeedbackType.Problem -> {
            "Your report is logged and a real person will read it. We'll email you when it's fixed."
        }

        FeedbackType.Suggestion -> {
            "Ideas from people who actually use Evenly are how it gets better. We read every one."
        }

        FeedbackType.Question -> {
            "Your question is with us. We'll reply by email."
        }
    }
}

/**
 * One badge, three motions: Problem *settles* (a checkmark draws itself in), Suggestion *lifts* (a
 * sparkle rises with three small sparks drifting off it), Question *travels* (a paper plane arrives
 * mid-flight, trailing a dashed line). [isReduceMotionEnabled] skips straight to the end state:
 * mock's own accessibility rule (`THANKYOU_SCREEN_PROMPT.md`) is that reduced motion must still look
 * finished, not merely static-and-empty.
 */
@Composable
private fun FeedbackThankYouIcon(type: FeedbackType) {
    val c = EvenlyTheme.colors
    val reduceMotion = remember { isReduceMotionEnabled() }

    val entrance = remember(type) { Animatable(if (reduceMotion) 1f else 0f) }
    val draw = remember(type) { Animatable(if (reduceMotion) 1f else 0f) }
    val sparkA = remember(type) { Animatable(0f) }
    val sparkB = remember(type) { Animatable(0f) }
    val sparkC = remember(type) { Animatable(0f) }

    LaunchedEffect(type) {
        if (reduceMotion) return@LaunchedEffect
        launch {
            entrance.animateTo(
                1f,
                spring(
                    dampingRatio = if (type == FeedbackType.Suggestion) 0.5f else 0.62f,
                    stiffness = 260f,
                ),
            )
        }
        launch { draw.animateTo(1f, tween(320, delayMillis = 160, easing = LinearEasing)) }
        if (type == FeedbackType.Suggestion) {
            launch { sparkA.animateTo(1f, tween(520, delayMillis = 90)) }
            launch { sparkB.animateTo(1f, tween(520, delayMillis = 180)) }
            launch { sparkC.animateTo(1f, tween(480, delayMillis = 280)) }
        }
    }

    val badgeBg = if (type == FeedbackType.Suggestion) c.blueTint else c.surface
    val badgeBorder = if (type == FeedbackType.Suggestion) c.blueTint2 else c.borderStrong

    Box(Modifier.size(84.dp), contentAlignment = Alignment.Center) {
        if (type == FeedbackType.Suggestion) {
            // Rise-and-fade, not a plain fade: each spark's own progress drives both its upward drift
            // and a triangular alpha (in for the first third, out for the rest), matching the mock's
            // "opacity 0 -> 1 -> 0" spark keyframe rather than a linear one.
            listOf(sparkA to (-22f to 8f), sparkB to (18f to -2f), sparkC to (4f to -20f)).forEach { (anim, dir) ->
                val p = anim.value
                if (p <= 0f) return@forEach
                val a = if (p < 0.35f) p / 0.35f else (1f - p) / 0.65f
                Box(
                    Modifier
                        .offset { IntOffset((dir.first * p).roundToInt(), (dir.second * p - 14f * p).roundToInt()) }
                        .alpha(a.coerceIn(0f, 1f))
                        .size(5.dp)
                        .clip(CircleShape)
                        .background(c.blueText),
                )
            }
        }

        val scale = 0.68f + 0.4f * entrance.value
        Box(
            Modifier
                .size(64.dp)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    alpha = entrance.value.coerceIn(0f, 1f)
                }.clip(CircleShape)
                .background(badgeBg)
                .border(1.5.dp, badgeBorder, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            when (type) {
                FeedbackType.Problem -> ProblemCheckmark(draw.value, c.bluePressed)
                FeedbackType.Suggestion -> EvIcon(EvIcons.Sparkle, size = 28.dp, tint = c.blueText)
                FeedbackType.Question -> QuestionPlane(entrance.value, draw.value, c.bluePressed)
            }
        }
    }
}

/** Draws EvIcons.Check's own path (a 24x24 "M5 12l5 5L20 6"), trimmed by [progress] via
 *  [PathMeasure] so the mark appears to draw itself rather than simply fading in. */
@Composable
private fun ProblemCheckmark(
    progress: Float,
    tint: Color,
) {
    Canvas(Modifier.size(30.dp)) {
        val s = size.width / 24f
        val full =
            Path().apply {
                moveTo(5 * s, 12 * s)
                lineTo(10 * s, 17 * s)
                lineTo(20 * s, 6 * s)
            }
        val measure = PathMeasure().apply { setPath(full, false) }
        val trimmed = Path()
        measure.getSegment(0f, measure.length * progress.coerceIn(0f, 1f), trimmed, true)
        drawPath(
            trimmed,
            color = tint,
            style = Stroke(width = 3.2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }
}

/** EvIcons.Send's own plane silhouette (a 24x24 "M4 12l16-7-7 16-2.5-6.5L4 12z"), already mid-flight:
 *  [entrance] (which overshoots via [spring]) carries a small translate + rotate so it settles rather
 *  than launches, and [draw] trims a dashed trail behind it the same way [ProblemCheckmark] draws in. */
@Composable
private fun QuestionPlane(
    entrance: Float,
    draw: Float,
    tint: Color,
) {
    Canvas(Modifier.size(34.dp)) {
        val s = size.width / 24f
        val trail =
            Path().apply {
                moveTo(3 * s, 15 * s)
                quadraticTo(12 * s, 19 * s, 19 * s, 8 * s)
            }
        val trailMeasure = PathMeasure().apply { setPath(trail, false) }
        val trailTrimmed = Path()
        trailMeasure.getSegment(0f, trailMeasure.length * draw.coerceIn(0f, 1f), trailTrimmed, true)
        drawPath(
            trailTrimmed,
            color = tint.copy(alpha = 0.35f),
            style =
                Stroke(
                    width = 1.6.dp.toPx(),
                    cap = StrokeCap.Round,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 5.dp.toPx())),
                ),
        )

        val plane =
            Path().apply {
                moveTo(4 * s, 12 * s)
                lineTo(20 * s, 5 * s)
                lineTo(13 * s, 21 * s)
                lineTo(10.5f * s, 14.5f * s)
                close()
            }
        translate(left = -10f * s * (1f - entrance), top = 6f * s * (1f - entrance)) {
            rotate(degrees = -14f * (1f - entrance), pivot = Offset(12f * s, 12f * s)) {
                drawPath(plane, color = tint, alpha = entrance.coerceIn(0f, 1f))
            }
        }
    }
}
