package app.splitevenly.ui.screen.group

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import io.github.alexzhirkevich.qrose.rememberQrCodePainter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.splitevenly.ui.components.AvatarSize
import app.splitevenly.ui.components.ButtonVariant
import app.splitevenly.ui.components.ChipVariant
import app.splitevenly.ui.components.EvAvatar
import app.splitevenly.ui.components.EvBanner
import app.splitevenly.ui.components.EvButton
import app.splitevenly.ui.components.EvChip
import app.splitevenly.ui.components.EvDot
import app.splitevenly.ui.components.EvEmptyState
import app.splitevenly.ui.components.EvFab
import app.splitevenly.ui.components.EvIconButton
import app.splitevenly.ui.components.EvSegmented
import app.splitevenly.ui.components.EvSheetScaffold
import app.splitevenly.ui.components.EvSkeletonRow
import app.splitevenly.ui.components.EvTopBar
import app.splitevenly.ui.components.EvUndoToast
import app.splitevenly.ui.components.icon.EvIcon
import app.splitevenly.ui.components.icon.EvIcons
import app.splitevenly.domain.expense.ExpenseCategory
import app.splitevenly.ui.theme.EvenlyTheme
import app.splitevenly.ui.theme.categoryColor

data class ExpenseItemUi(
    val id: String,
    val title: String,
    val sub: String,
    val icon: ImageVector,
    val remaining: Double,
    val original: Double,
    val settled: Boolean = false,
    val unread: Boolean = false,
    val currencySymbol: String = "$",
    // Richer-card fields (F-polish): payer attribution, category accent, and the viewer's own stake.
    val payerName: String = "",
    val payerIsMe: Boolean = false,
    val amountLabel: String = "",
    val categoryColor: Color? = null,
    val stakeLabel: String? = null,
    val stakeAmount: String? = null,
    val stakeOwedToYou: Boolean = false,
)

data class ExpenseDayUi(val label: String, val items: List<ExpenseItemUi>)

enum class ExpensesState { Loading, Empty, Populated }

/** Shared corner radius for the expense feed's per-row cards (and the settled tray). */
private val ExpenseCardShape = RoundedCornerShape(14.dp)

/**
 * Whether [inviteLink] is a real join link rather than the "Generating link…" placeholder.
 *
 * Keyed on the path segment, deliberately **not** the host: the host is branding and has already
 * changed once (sharecost.app to split-evenly.app), and may change again if the domain moves. The
 * previous version of this test matched the brand prefix, and the rebrand silently falsified it,
 * so the invite QR stopped rendering with nothing to catch it.
 */
internal fun hasInviteToken(inviteLink: String): Boolean = "/j/" in inviteLink

/** 6 · Group · Expenses tab (design/src/screens-group.jsx). Hosted inside GroupHomeScreen. */
@Composable
fun GroupExpensesTab(
    groupEmoji: String = "🏝️",
    groupName: String = "Tulum Trip",
    state: ExpensesState = ExpensesState.Populated,
    days: List<ExpenseDayUi> = GroupSamples.days,
    drafts: Int = 1,
    offline: Boolean = false,
    filterActive: Boolean = false,
    inviteLink: String = "split-evenly.app/j/8Kk2-Tulum",
    onBack: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onAdd: () -> Unit = {},
    onOpenExpense: (String) -> Unit = {},
    onSearch: () -> Unit = {},
    onFilter: () -> Unit = {},
    onClearFilter: () -> Unit = {},
    onOpenDrafts: () -> Unit = {},
    onCopyInvite: () -> Unit = {},
    onShareInvite: () -> Unit = {},
    onRotateInvite: () -> Unit = {},
    unresolvedBills: List<UnresolvedBillUi> = emptyList(),
    onOpenBill: (String) -> Unit = {},
    // "Is this you?" (identity claim). Rendered here because this is where people actually look; the
    // settings row and the full-screen list are the deliberate second and third doors, not the first.
    identityNames: List<UnclaimedNameUi> = emptyList(),
    identityFinished: Boolean = false,
    onIdentityThatsMe: (String) -> Unit = {},
    onIdentityNotMe: (String) -> Unit = {},
    onIdentityNoneOfThese: () -> Unit = {},
    onIdentityLater: () -> Unit = {},
    onIdentitySeeAll: () -> Unit = {},
    /** Non-null while a claim is still cancellable. The merge has not been written yet. */
    undoToastText: String? = null,
    onUndoClaim: () -> Unit = {},
    /** How a finished claim ended, when that needs saying (someone else won it, or it couldn't be confirmed). */
    claimNoticeText: String? = null,
    onDismissClaimNotice: () -> Unit = {},
) {
    val c = EvenlyTheme.colors
    var sub by remember { mutableStateOf("Active") }
    var showInvite by remember { mutableStateOf(false) }
    // Default open; the collapse choice sticks for the whole visit (state lives above the section so it
    // survives the bills momentarily emptying and re-appearing).
    var claimsExpanded by remember { mutableStateOf(true) }
    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize().background(c.page)) {
        EvTopBar(
            title = groupName,
            navIcon = {
                // Emoji is now decorative — tapping a group icon for settings was a hidden affordance.
                // Settings has its own obvious gear in the actions.
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    EvIconButton(EvIcons.Back, onBack)
                    Text(groupEmoji, fontSize = 22.sp)
                }
            },
            actions = {
                EvIconButton(EvIcons.Search, onSearch)
                // Invite/share: the front-door entry to the group's join link (also in Group settings).
                EvIconButton(EvIcons.Share, { showInvite = true })
                EvIconButton(EvIcons.Gear, onOpenSettings)
            },
        )
        if (offline) EvBanner("Offline. Your changes will sync.")
        // Tab island: a segmented pill below the title (not an underline bar in the app bar), with the
        // advanced-filter funnel beside it so the bar stays uncluttered.
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            EvSegmented(
                options = listOf("Active", "All", "Settled"),
                selected = sub,
                onSelect = { sub = it },
                modifier = Modifier.weight(1f),
            )
            FilterFunnel(active = filterActive, onClick = onFilter)
        }
        if (filterActive) FilterChipRow(onClearFilter)
        if (unresolvedBills.isNotEmpty()) {
            UnresolvedBillsSection(unresolvedBills, claimsExpanded, { claimsExpanded = !claimsExpanded }, onOpenBill)
        }
        // Outside the feed's LazyColumn on purpose: the question has to be there on every sub-tab, with
        // a filter on, and in the empty states too, which are exactly the moments a scrolling item
        // wouldn't render.
        if (identityNames.isNotEmpty()) {
            IdentityClaimCard(
                names = identityNames,
                onThatsMe = onIdentityThatsMe,
                onNotMe = onIdentityNotMe,
                onNoneOfThese = onIdentityNoneOfThese,
                onLater = onIdentityLater,
                onSeeAll = onIdentitySeeAll,
            )
        } else if (identityFinished) {
            IdentityClaimDoneNote()
        }

        // The sub-tab narrows by settlement status; drop days that have nothing under the active view.
        val visibleDays = days.mapNotNull { day ->
            val active = if (sub == "Settled") emptyList() else day.items.filter { !it.settled }
            val settled = if (sub == "Active") emptyList() else day.items.filter { it.settled }
            if (active.isEmpty() && settled.isEmpty()) null else VisibleDay(day.label, active, settled)
        }

        when {
            state == ExpensesState.Loading -> Column(Modifier.padding(top = 8.dp)) { repeat(5) { EvSkeletonRow() } }
            filterActive && visibleDays.isEmpty() -> EvEmptyState(
                icon = EvIcons.Filter,
                title = "No matching expenses",
                text = "No expenses match the current filter. Clear it to see everything.",
                ctaText = "Clear filters",
                onCta = onClearFilter,
            )

            state == ExpensesState.Empty -> EvEmptyState(
                icon = EvIcons.Receipt,
                title = "No expenses yet",
                text = "Add the first expense and Evenly tracks who owes whom.",
                ctaText = "Add expense",
                onCta = onAdd,
            )

            visibleDays.isEmpty() -> EvEmptyState(
                icon = EvIcons.Receipt,
                title = "Nothing here",
                text = "No ${sub.lowercase()} expenses in this group yet.",
                ctaText = "Add expense",
                onCta = onAdd,
            )

            else -> Box(Modifier.weight(1f)) {
                LazyColumn(Modifier.fillMaxSize()) {
                    // One prompt at a time, identity first: this strip already stacks the offline
                    // banner, the filter chips, and the unresolved-bills section, and a fifth
                    // interstitial buries the feed.
                    if (drafts > 0 && !filterActive && sub != "Settled" && identityNames.isEmpty()) {
                        item {
                            Row(
                                Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 4.dp)
                                    .clip(RoundedCornerShape(12.dp)).background(c.blueTint).clickable { onOpenDrafts() }
                                    .padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    EvIcon(EvIcons.Edit, size = 16.dp, tint = c.blueText)
                                    Text("Drafts ($drafts)", color = c.blueText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                                }
                                EvIcon(EvIcons.ChevR, size = 16.dp, tint = c.blueText)
                            }
                        }
                    }
                    visibleDays.forEach { day ->
                        // A plain item, not a stickyHeader: pinned it overlapped the rows it scrolled
                        // over (its surface band is a near-transparent wash in dark mode). Scrolling with
                        // the content is the familiar pattern and sidesteps the bleed-through entirely.
                        item(key = day.label) { EvDayHeader(day.label) }
                        // Each expense is its own card (side margins + small gap, no hairlines).
                        itemsIndexed(day.active, key = { _, it -> it.id }) { _, it ->
                            ExpenseRowFrom(it, onOpenExpense)
                        }
                        if (day.settled.isNotEmpty()) {
                            if (sub == "Settled") {
                                itemsIndexed(day.settled, key = { _, it -> "s-${it.id}" }) { _, it ->
                                    ExpenseRowFrom(it, onOpenExpense)
                                }
                            } else {
                                item(key = "settled-${day.label}") { SettledTray(day.settled, onOpenExpense) }
                            }
                        }
                    }
                    item { Spacer(Modifier.height(150.dp)) }
                }
                EvFab(onAdd, Modifier.align(Alignment.BottomEnd).padding(16.dp))
            }
        }
    }
    // Sits above the FAB, over whatever branch rendered. Swiping it away is deliberately not a cancel:
    // only the Undo button is, so a stray gesture can't quietly decide who someone is.
    val toastModifier = Modifier.align(Alignment.BottomCenter).padding(horizontal = 16.dp).padding(bottom = 92.dp)
    when {
        undoToastText != null -> EvUndoToast(undoToastText, onUndoClaim, toastModifier)
        // A claim that lost the race or couldn't be confirmed has to say so. Nothing was written either
        // way, so the card is still there to try again.
        claimNoticeText != null -> EvUndoToast(claimNoticeText, onDismissClaimNotice, toastModifier, actionLabel = "Got it")
    }
    if (showInvite) {
        InviteSheet(
            groupName = groupName,
            inviteLink = inviteLink,
            onCopy = onCopyInvite,
            onShare = onShareInvite,
            onRotate = onRotateInvite,
            onDismiss = { showInvite = false },
        )
    }
    }
}

/**
 * Invite bottom sheet: the group's join link with a one-tap copy and a (link-invalidating) rotate.
 * Stateless — the link string and the copy/rotate actions are wired by GroupExpensesRoute; only the
 * transient "copied" affirmation lives here, and it resets whenever the link rotates.
 */
@Composable
private fun InviteSheet(
    groupName: String,
    inviteLink: String,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onRotate: () -> Unit,
    onDismiss: () -> Unit,
) {
    val c = EvenlyTheme.colors
    var copied by remember(inviteLink) { mutableStateOf(false) }
    EvSheetScaffold(
        onDismiss = onDismiss,
        title = "Invite to $groupName",
        sub = "Anyone with this link can join.",
    ) {
        // Scannable QR of the join link — hand the phone across the table instead of typing a URL.
        // White plate + dark modules so it scans in both light and dark themes. Hidden until the token
        // syncs: the test is for a real "/j/<token>" link rather than the "Generating link…" sentinel.
        // Do NOT reinstate a brand-prefix check here — the rebrand silently falsified exactly that and
        // the QR stopped rendering, because the guard and the link are built in two different files.
        if (hasInviteToken(inviteLink)) {
            Box(Modifier.fillMaxWidth().padding(bottom = 12.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.clip(RoundedCornerShape(16.dp)).background(Color.White).padding(14.dp)) {
                    Image(
                        painter = rememberQrCodePainter(inviteLink),
                        contentDescription = "QR code to join $groupName",
                        modifier = Modifier.size(176.dp),
                    )
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.surface)
                .border(1.dp, c.border, RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            EvIcon(EvIcons.Link, size = 16.dp, tint = c.ink3)
            Text(
                inviteLink,
                modifier = Modifier.weight(1f),
                color = c.ink,
                fontSize = 13.sp,
                fontFamily = EvenlyTheme.monoFamily,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(12.dp))
        EvButton(
            text = "Share link",
            onClick = onShare,
            leadingIcon = EvIcons.Share,
        )
        Spacer(Modifier.height(8.dp))
        EvButton(
            text = if (copied) "Link copied" else "Copy link",
            onClick = { onCopy(); copied = true },
            variant = ButtonVariant.Secondary,
            leadingIcon = if (copied) EvIcons.Check else EvIcons.Copy,
        )
        Spacer(Modifier.height(4.dp))
        EvButton(
            text = "New link",
            onClick = onRotate,
            variant = ButtonVariant.Text,
            leadingIcon = EvIcons.Reload,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
    }
}

/** A day section narrowed to the current sub-tab (Active/All/Settled). */
private data class VisibleDay(val label: String, val active: List<ExpenseItemUi>, val settled: List<ExpenseItemUi>)

/** Thin "Filtered · Clear" affordance shown above the feed when a filter is applied. */
@Composable
private fun FilterChipRow(onClear: () -> Unit) {
    val c = EvenlyTheme.colors
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            EvIcon(EvIcons.Filter, size = 14.dp, tint = c.blueText)
            Text("Filtered", color = c.ink2, fontSize = 13.sp)
        }
        Text("Clear", color = c.blueText, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.clickable { onClear() })
    }
}

/**
 * The feed's signature row: a category-tinted icon tile, the payer attribution (avatar + "X paid · $96"),
 * and a trailing **personal stake** — "you owe" in ink, "you're owed" in blue, or a Settled chip —
 * so the number that matters to the viewer leads.
 */
@Composable
private fun ExpenseRowFrom(it: ExpenseItemUi, onOpen: (String) -> Unit) {
    val c = EvenlyTheme.colors
    val accent = it.categoryColor ?: c.ink2
    Row(
        Modifier.fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clip(ExpenseCardShape)
            .background(c.page)
            .border(1.dp, c.border, ExpenseCardShape)
            .clickable { onOpen(it.id) }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier.size(40.dp).clip(RoundedCornerShape(11.dp))
                .background(if (it.settled) c.settledTint else it.categoryColor?.copy(alpha = 0.14f) ?: c.surface),
            contentAlignment = Alignment.Center,
        ) {
            EvIcon(if (it.settled) EvIcons.Check else it.icon, size = 20.dp, tint = if (it.settled) c.settled else accent)
        }
        Column(Modifier.weight(1f)) {
            Text(it.title, style = MaterialTheme.typography.titleSmall, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (it.payerName.isNotBlank()) EvAvatar(it.payerName, me = it.payerIsMe, size = AvatarSize.Xs)
                Text(
                    if (it.amountLabel.isNotBlank()) "${it.sub} · ${it.amountLabel}" else it.sub,
                    style = MaterialTheme.typography.bodyMedium, color = c.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            when {
                it.settled -> EvChip("Settled", variant = ChipVariant.Green, leadingIcon = EvIcons.Check)
                it.stakeLabel != null && it.stakeAmount != null -> Column(horizontalAlignment = Alignment.End) {
                    // Owed-to-you → amber (in your favour); you-owe → plain ink (white on dark). Blue as
                    // the "you owe" amount sank into the dark page; a neutral ink amount reads clearly and
                    // still lets the amber "you're owed" stand out as the one colour that matters.
                    val stakeColor = if (it.stakeOwedToYou) c.credit else c.ink
                    Text(it.stakeLabel, color = stakeColor, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    Text(it.stakeAmount, color = stakeColor, fontFamily = EvenlyTheme.monoFamily, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                }
                it.amountLabel.isNotBlank() -> Text(it.amountLabel, color = c.ink2, fontFamily = EvenlyTheme.monoFamily, fontSize = 14.sp)
            }
            if (it.unread) EvDot()
            EvIcon(EvIcons.ChevR, size = 16.dp, tint = c.ink3)
        }
    }
}

@Composable
private fun EvDayHeader(label: String) {
    val c = EvenlyTheme.colors
    Text(
        label,
        Modifier.fillMaxWidth().background(c.surface).padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 6.dp),
        color = c.ink2,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.2.sp,
    )
}

@Composable
private fun SettledTray(items: List<ExpenseItemUi>, onOpen: (String) -> Unit) {
    val c = EvenlyTheme.colors
    var open by remember { mutableStateOf(false) }
    Column {
        // A muted (surface-fill) card header so the collapsed settled group reads as secondary to the
        // white active expense cards above it.
        Row(
            Modifier.fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp)
                .clip(ExpenseCardShape)
                .background(c.surface)
                .border(1.dp, c.border, ExpenseCardShape)
                .clickable { open = !open }
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(11.dp)).background(c.blueTint), contentAlignment = Alignment.Center) {
                EvIcon(EvIcons.Check, size = 20.dp, tint = c.blueText)
            }
            Text("Settled (${items.size})", color = c.ink2, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            EvIcon(if (open) EvIcons.ChevU else EvIcons.ChevD, size = 16.dp, tint = c.ink3)
        }
        if (open) {
            items.forEach { ExpenseRowFrom(it, onOpen) }
        }
    }
}

/** The advanced-filter funnel that sits beside the tab island; tints when a filter is active. */
@Composable
private fun FilterFunnel(active: Boolean, onClick: () -> Unit) {
    val c = EvenlyTheme.colors
    val shape = RoundedCornerShape(12.dp)
    Box(
        Modifier.size(44.dp)
            .clip(shape)
            .background(if (active) c.blueTint else c.surface)
            .border(1.dp, if (active) c.blue else c.border, shape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        EvIcon(EvIcons.Filter, size = 18.dp, tint = if (active) c.blue else c.ink2)
    }
}

internal object GroupSamples {
    val days = listOf(
        ExpenseDayUi("Today", listOf(
            ExpenseItemUi("1", "Dinner at La Negra", "Andrew paid", EvIcons.Food, 24.0, 96.0, unread = true,
                payerName = "Andrew", amountLabel = "\$96.00", categoryColor = categoryColor(ExpenseCategory.FOOD),
                stakeLabel = "you owe", stakeAmount = "\$24.00"),
            ExpenseItemUi("2", "Airport taxi", "You paid", EvIcons.Car, 14.5, 58.0,
                payerName = "You", payerIsMe = true, amountLabel = "\$58.00", categoryColor = categoryColor(ExpenseCategory.TRANSPORT),
                stakeLabel = "you're owed", stakeAmount = "\$14.50", stakeOwedToYou = true),
        )),
        ExpenseDayUi("Wed, May 22", listOf(
            ExpenseItemUi("3", "Beach villa — night 2", "Maya paid", EvIcons.Bed, 0.0, 60.0, settled = true,
                payerName = "Maya", amountLabel = "\$60.00", categoryColor = categoryColor(ExpenseCategory.LODGING)),
            ExpenseItemUi("4", "Cenote day trip", "Bob paid", EvIcons.Ticket, 18.0, 72.0,
                payerName = "Bob", amountLabel = "\$72.00", categoryColor = categoryColor(ExpenseCategory.ENTERTAINMENT),
                stakeLabel = "you owe", stakeAmount = "\$18.00"),
            ExpenseItemUi("5", "Supermarket run", "You paid", EvIcons.Cart, 33.2, 41.5,
                payerName = "You", payerIsMe = true, amountLabel = "\$41.50", categoryColor = categoryColor(ExpenseCategory.GROCERIES),
                stakeLabel = "you're owed", stakeAmount = "\$33.20", stakeOwedToYou = true),
        )),
    )
}

@Preview
@Composable
private fun GroupExpensesPreview() {
    EvenlyTheme { GroupExpensesTab() }
}
