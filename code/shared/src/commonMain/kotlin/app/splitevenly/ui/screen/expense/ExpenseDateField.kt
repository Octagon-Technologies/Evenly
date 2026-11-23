package app.splitevenly.ui.screen.expense

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.splitevenly.domain.expense.expenseDateChipLabel
import app.splitevenly.domain.expense.expenseDateLongLabel
import app.splitevenly.domain.expense.isFutureDate
import app.splitevenly.domain.expense.shiftIsoDate
import app.splitevenly.ui.components.EvButton
import app.splitevenly.ui.components.EvField
import app.splitevenly.ui.components.EvSheetScaffold
import app.splitevenly.ui.components.clickableClearingFocus
import app.splitevenly.ui.components.icon.EvIcon
import app.splitevenly.ui.components.icon.EvIcons
import app.splitevenly.ui.theme.EvenlyTheme
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.plus

/** Height of an `EvTextField`, so the date column centers on the field rather than on its label. */
private val FIELD_HEIGHT = 52.dp

/**
 * The bill's title field with its date beside it: a small "Date" label over a rounded chip ("Today",
 * "Yesterday", "Nov 20"). Kept on the title row so the date adds no section to an editor that already has
 * several. Tapping the chip opens [ExpenseDateSheet].
 */
@Composable
fun TitleWithDateRow(
    date: String,
    today: String,
    onDateClick: () -> Unit,
    titleLabel: String = "Title",
    field: @Composable () -> Unit,
) {
    // Bottom-aligned so the date column sits level with the text field, not with the label above it; its
    // minimum height is the field's, which centers the label and chip on the field.
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Bottom) {
        EvField(titleLabel, Modifier.weight(1f)) { field() }
        Column(Modifier.heightIn(min = FIELD_HEIGHT), verticalArrangement = Arrangement.Center) {
            ExpenseDateChip(date, today, onDateClick)
        }
    }
}

/** The "Date" label and chip on their own, centered on each other. */
@Composable
fun ExpenseDateChip(
    date: String,
    today: String,
    onClick: () -> Unit,
) {
    val c = EvenlyTheme.colors
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text("Date", color = c.ink3, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        Box(
            Modifier
                .clip(RoundedCornerShape(99.dp))
                .background(c.blueTint)
                .clickableClearingFocus(onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 5.dp),
        ) {
            Text(expenseDateChipLabel(date, today), color = c.blueText, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

private val MONTHS =
    listOf(
        "January", "February", "March", "April", "May", "June",
        "July", "August", "September", "October", "November", "December",
    )

/**
 * "When was this?": Today, Yesterday and the day before as one-tap chips, then a month calendar. Future
 * days are shown but can't be picked. Nothing changes until "Use …" is tapped.
 */
@Composable
fun ExpenseDateSheet(
    date: String,
    today: String,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val c = EvenlyTheme.colors
    var picked by remember { mutableStateOf(date) }
    var month by remember { mutableStateOf(runCatching { LocalDate.parse(date) }.getOrNull()?.let { LocalDate(it.year, it.month, 1) }) }
    var closing by remember { mutableStateOf(false) }
    EvSheetScaffold(onDismiss = onDismiss, title = "When was this?", dismissRequested = closing) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(today, shiftIsoDate(today, -1), shiftIsoDate(today, -2)).forEachIndexed { i, d ->
                    val label =
                        when (i) {
                            0 -> "Today"
                            1 -> "Yesterday"
                            else -> expenseDateLongLabel(d)
                        }
                    QuickDayChip(label, selected = picked == d) {
                        picked = d
                        month = LocalDate.parse(d).let { LocalDate(it.year, it.month, 1) }
                    }
                }
            }
            month?.let { m ->
                MonthGrid(
                    month = m,
                    picked = picked,
                    today = today,
                    onPrev = { month = LocalDate(m.year, m.month, 1).plus(DatePeriod(months = -1)) },
                    onNext = { month = LocalDate(m.year, m.month, 1).plus(DatePeriod(months = 1)) },
                    onPick = { picked = it },
                )
            }
            EvButton("Use ${if (picked == today) "today" else expenseDateLongLabel(picked)}", {
                onPick(picked)
                closing = true
            })
        }
    }
}

@Composable
private fun QuickDayChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val c = EvenlyTheme.colors
    Box(
        Modifier
            .clip(RoundedCornerShape(99.dp))
            .background(if (selected) c.blue else c.surface)
            .clickableClearingFocus(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(label, color = if (selected) c.onAccent else c.ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun MonthGrid(
    month: LocalDate,
    picked: String,
    today: String,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onPick: (String) -> Unit,
) {
    val c = EvenlyTheme.colors
    val todayMonth = runCatching { LocalDate.parse(today) }.getOrNull()?.let { LocalDate(it.year, it.month, 1) }
    val canGoNext = todayMonth == null || month < todayMonth
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${MONTHS[month.monthNumber - 1]} ${month.year}",
                color = c.ink,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Box(Modifier.clip(CircleShape).clickableClearingFocus(onClick = onPrev).padding(8.dp)) {
                EvIcon(EvIcons.ChevL, size = 18.dp, tint = c.ink2)
            }
            Box(
                Modifier
                    .clip(CircleShape)
                    .then(if (canGoNext) Modifier.clickableClearingFocus(onClick = onNext) else Modifier)
                    .padding(8.dp),
            ) {
                EvIcon(EvIcons.ChevR, size = 18.dp, tint = if (canGoNext) c.ink2 else c.border)
            }
        }
        Row(Modifier.fillMaxWidth()) {
            listOf("M", "T", "W", "T", "F", "S", "S").forEach {
                Text(it, color = c.ink3, fontSize = 11.sp, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
            }
        }
        // Weeks start on Monday: isoDayNumber is 1 for Monday, so the first row has (n - 1) blanks.
        val lead = month.dayOfWeek.isoDayNumber - 1
        val days = month.plus(DatePeriod(months = 1)).plus(DatePeriod(days = -1)).dayOfMonth
        val cells = List(lead) { null } + (1..days).map { LocalDate(month.year, month.month, it).toString() }
        cells.chunked(7).forEach { week ->
            Row(Modifier.fillMaxWidth()) {
                (0 until 7).forEach { col ->
                    val d = week.getOrNull(col)
                    Box(Modifier.weight(1f).aspectRatio(1.15f), contentAlignment = Alignment.Center) {
                        if (d != null) DayCell(d, picked = d == picked, isToday = d == today, future = isFutureDate(d, today), onPick)
                    }
                }
            }
        }
    }
}

@Composable
private fun DayCell(
    date: String,
    picked: Boolean,
    isToday: Boolean,
    future: Boolean,
    onPick: (String) -> Unit,
) {
    val c = EvenlyTheme.colors
    Box(
        Modifier
            .fillMaxWidth(0.82f)
            .aspectRatio(1f)
            .clip(CircleShape)
            .background(if (picked) c.blue else androidx.compose.ui.graphics.Color.Transparent)
            .then(if (future) Modifier else Modifier.clickableClearingFocus { onPick(date) }),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            date.takeLast(2).trimStart('0'),
            color =
                when {
                    picked -> c.onAccent
                    future -> c.border
                    isToday -> c.blueText
                    else -> c.ink
                },
            fontSize = 14.sp,
            fontWeight = if (picked || isToday) FontWeight.Bold else FontWeight.Normal,
        )
    }
}
