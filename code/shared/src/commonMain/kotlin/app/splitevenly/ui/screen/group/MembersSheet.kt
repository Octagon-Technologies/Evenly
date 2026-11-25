package app.splitevenly.ui.screen.group

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.splitevenly.platform.isIOS
import app.splitevenly.ui.components.ButtonVariant
import app.splitevenly.ui.components.EvButton
import app.splitevenly.ui.components.EvCard
import app.splitevenly.ui.components.EvSheetScaffold
import app.splitevenly.ui.components.EvSpinner
import app.splitevenly.ui.components.EvTextField
import app.splitevenly.ui.components.icon.EvIcon
import app.splitevenly.ui.components.icon.EvIcons
import app.splitevenly.ui.components.topHairline
import app.splitevenly.ui.screen.people.KnownPeopleList
import app.splitevenly.ui.screen.people.KnownPersonUi
import app.splitevenly.ui.screen.people.matchKnown
import app.splitevenly.ui.screen.settings.MemberRowUi
import app.splitevenly.ui.theme.EvMotion
import app.splitevenly.ui.theme.EvenlyTheme
import io.github.alexzhirkevich.qrose.rememberQrCodePainter
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** How long the add-member bar and the new row's tint stay up. */
private const val ADD_FEEDBACK_MS = 3_000L

/** Room kept under the roster for the bar, so it never covers the row that was just added. */
private val BarSlot = 56.dp

/** The pages inside the Members sheet. Kept as screen state, not routes, so Back stays inside the sheet. */
private enum class MembersPage { Members, AddByName, Qr }

/**
 * Who is in the group, plus the ways to add someone (design/brainstorm/add-member-pages.html). "Add by name"
 * and "Show QR code" slide in as pages inside this same sheet with a back arrow, instead of closing it and
 * opening another sheet out of view. Back (arrow, Android Back, iOS edge swipe) returns to Members; a
 * swipe down still closes the whole sheet. "Share invite link" hands off to the system share sheet.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun MembersSheet(
    members: List<MemberRowUi>,
    groupName: String,
    inviteLink: String,
    sharingInvite: Boolean,
    onInviteWithLink: () -> Unit,
    onCopyInvite: () -> Unit,
    onAddMember: suspend (name: String) -> String?,
    onDismiss: () -> Unit,
    knownPeople: List<KnownPersonUi> = emptyList(),
    onAddKnown: suspend (KnownPersonUi) -> String? = { null },
) {
    var page by remember { mutableStateOf(MembersPage.Members) }
    // Hoisted so coming back from a page finds the card as it was left, not collapsed again.
    var addOpen by remember { mutableStateOf(false) }
    var membersHeightPx by remember { mutableStateOf(0) }
    val density = LocalDensity.current
    val back = { page = MembersPage.Members }
    EvSheetScaffold(onDismiss = onDismiss) {
        // Registered after the scaffold's own handler, so it wins while a page is showing.
        BackHandler(enabled = page != MembersPage.Members) { back() }
        AnimatedContent(
            targetState = page,
            transitionSpec = {
                val dir = if (targetState == MembersPage.Members) -1 else 1
                (slideInHorizontally(EvMotion.standard()) { it * dir } + fadeIn(EvMotion.standard()))
                    .togetherWith(slideOutHorizontally(EvMotion.standard()) { -it * dir } + fadeOut(EvMotion.quick()))
                    .using(SizeTransform(clip = true))
            },
            label = "membersPage",
        ) { current ->
            when (current) {
                MembersPage.Members -> {
                    MembersPageBody(
                        members = members,
                        addOpen = addOpen,
                        onToggleAdd = { addOpen = !addOpen },
                        sharingInvite = sharingInvite,
                        onInviteWithLink = onInviteWithLink,
                        onShowQr = { page = MembersPage.Qr },
                        onAddByName = { page = MembersPage.AddByName },
                        modifier = Modifier.onSizeChanged { membersHeightPx = it.height },
                    )
                }

                MembersPage.AddByName -> {
                    AddByNamePage(members, knownPeople, onAddMember, onAddKnown, onBack = back)
                }

                MembersPage.Qr -> {
                    InviteQrPage(
                        groupName = groupName,
                        inviteLink = inviteLink,
                        sharing = sharingInvite,
                        onShare = onInviteWithLink,
                        onCopy = onCopyInvite,
                        onBack = back,
                        // Same height as Members, so opening the QR doesn't make the sheet jump.
                        modifier = Modifier.heightIn(min = with(density) { membersHeightPx.toDp() }),
                    )
                }
            }
        }
    }
}

/** A page's title row: back arrow on the left, title centered on iOS and leading on Android. */
@Composable
private fun SheetPageHeader(
    title: String,
    onBack: (() -> Unit)? = null,
) {
    val c = EvenlyTheme.colors
    val ios = remember { isIOS() }
    val base = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
    val style = if (ios) base.copy(fontSize = MaterialTheme.typography.titleMedium.fontSize) else base
    Box(Modifier.fillMaxWidth().height(52.dp).padding(bottom = 4.dp), contentAlignment = Alignment.CenterStart) {
        if (onBack != null) {
            Box(
                Modifier.size(40.dp).clip(CircleShape).clickable(onClick = onBack),
                contentAlignment = Alignment.Center,
            ) { EvIcon(EvIcons.Back, size = 20.dp, tint = c.blueText) }
        }
        Text(
            title,
            modifier =
                if (ios) {
                    Modifier.fillMaxWidth().padding(horizontal = 44.dp)
                } else {
                    Modifier.padding(start = if (onBack != null) 48.dp else 4.dp)
                },
            color = c.ink,
            style = style,
            textAlign = if (ios) TextAlign.Center else TextAlign.Start,
            maxLines = 1,
        )
    }
}

@Composable
private fun MembersPageBody(
    members: List<MemberRowUi>,
    addOpen: Boolean,
    onToggleAdd: () -> Unit,
    sharingInvite: Boolean,
    onInviteWithLink: () -> Unit,
    onShowQr: () -> Unit,
    onAddByName: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        SheetPageHeader("Members")
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
            AddPeopleCard(addOpen, onToggleAdd, sharingInvite, onInviteWithLink, onShowQr, onAddByName)
            EvCard(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                members.forEachIndexed { i, m -> MemberRow(m, showDivider = i > 0) }
            }
        }
    }
}

/** Collapsed it reads as a tonal button; tapped, it grows into a tinted card listing the three ways in. */
@Composable
private fun AddPeopleCard(
    open: Boolean,
    onToggle: () -> Unit,
    sharingInvite: Boolean,
    onInviteWithLink: () -> Unit,
    onShowQr: () -> Unit,
    onAddByName: () -> Unit,
) {
    val c = EvenlyTheme.colors
    val t = EvenlyTheme.text
    val chevronTurn by animateFloatAsState(if (open) 180f else 0f, EvMotion.standard())
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(if (open) 20.dp else 26.dp))
            .background(c.blueTint)
            .animateContentSize(EvMotion.standard()),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(horizontal = 18.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            EvIcon(EvIcons.Plus, size = 18.dp, tint = c.blueText)
            Text("Add people", Modifier.weight(1f), style = t.itemTitle.copy(color = c.blueText, fontWeight = FontWeight.SemiBold))
            EvIcon(EvIcons.ChevD, size = 16.dp, tint = c.blueText, modifier = Modifier.rotate(chevronTurn))
        }
        AnimatedVisibility(
            visible = open,
            enter = expandVertically(EvMotion.standard()) + fadeIn(EvMotion.standard()),
            exit = shrinkVertically(EvMotion.standard()) + fadeOut(EvMotion.quick()),
        ) {
            Column(Modifier.padding(start = 8.dp, end = 8.dp, bottom = 8.dp)) {
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.page)) {
                    AddPeopleOption(
                        EvIcons.Share,
                        "Share invite link",
                        if (sharingInvite) "Opening share options…" else "Send it by text or chat",
                        showDivider = false,
                        busy = sharingInvite,
                        onClick = onInviteWithLink,
                    )
                    AddPeopleOption(EvIcons.Qr, "Show QR code", "For someone next to you", showDivider = true, onClick = onShowQr)
                    AddPeopleOption(EvIcons.Edit, "Add by name", "They can claim it later", showDivider = true, onClick = onAddByName)
                }
            }
        }
    }
}

@Composable
private fun AddPeopleOption(
    icon: ImageVector,
    title: String,
    hint: String,
    showDivider: Boolean,
    busy: Boolean = false,
    onClick: () -> Unit,
) {
    val c = EvenlyTheme.colors
    val t = EvenlyTheme.text
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (showDivider) Modifier.topHairline(c.border) else Modifier)
            // Disabled while busy so a second tap during the wait can't queue a second share sheet.
            .clickable(enabled = !busy, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(32.dp).clip(CircleShape).background(c.blueTint), contentAlignment = Alignment.Center) {
            EvIcon(icon, size = 16.dp, tint = c.blueText)
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = t.itemTitle)
            Text(hint, style = t.caption.copy(color = c.ink2, fontSize = 13.sp))
        }
        if (busy) EvSpinner(color = c.blueText, size = 16.dp) else EvIcon(EvIcons.ChevR, size = 15.dp, tint = c.ink3)
    }
}

/** One message in the add page's bar. [seq] restarts the 3-second timer when the same text repeats. */
private data class AddBar(
    val text: String,
    val ok: Boolean,
    val seq: Int,
)

/** Case- and spacing-blind form of a name, for the "already in this group" check. */
private fun normalizedName(name: String): String = name.trim().replace(Regex("\\s+"), " ").lowercase()

/**
 * Add new member: a name field, an Add button, and the group right below. The field is focused as the page
 * arrives, so the keyboard comes up with it. After an add the field clears and the keyboard stays up for
 * the next name; a green bar confirms it so nobody adds the same person twice, and the new row sits on top,
 * tinted, for the same 3 seconds. A name already in the group is refused with the red bar.
 *
 * The save is local-first, so being offline is not a failure: the person is added now and syncs later. The
 * red "Couldn't add" bar is only for a save that really failed on the device.
 */
@Composable
private fun AddByNamePage(
    members: List<MemberRowUi>,
    known: List<KnownPersonUi>,
    onAddMember: suspend (name: String) -> String?,
    onAddKnown: suspend (KnownPersonUi) -> String?,
    onBack: () -> Unit,
) {
    val c = EvenlyTheme.colors
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }
    var name by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var bar by remember { mutableStateOf<AddBar?>(null) }
    var seq by remember { mutableStateOf(0) }
    // Newest first: the people added on this visit lead the list so each one shows up right under the field.
    var addedIds by remember { mutableStateOf(emptyList<String>()) }
    var highlightId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { focus.requestFocus() }
    LaunchedEffect(bar) {
        if (bar != null) {
            delay(ADD_FEEDBACK_MS)
            bar = null
        }
    }
    LaunchedEffect(highlightId) {
        if (highlightId != null) {
            delay(ADD_FEEDBACK_MS)
            highlightId = null
        }
    }

    // Every outcome hands focus back to the field: the Add button, like every EvButton, drops focus on tap,
    // and the keyboard is meant to stay up for the next name or the fix.
    fun show(
        text: String,
        ok: Boolean,
    ) {
        seq += 1
        bar = AddBar(text, ok, seq)
        focus.requestFocus()
    }

    // One path for a typed name and a picked "person you know": same duplicate rule, same bars.
    fun add(
        typed: String,
        save: suspend () -> String?,
    ) {
        if (saving) return
        val trimmed = typed.trim().replace(Regex("\\s+"), " ")
        when {
            trimmed.isEmpty() -> {
                show("Type their name first", ok = false)
            }

            members.any { normalizedName(it.name) == normalizedName(trimmed) } -> {
                show("$trimmed is already in this group", ok = false)
            }

            else -> {
                saving = true
                scope.launch {
                    val id = save()
                    saving = false
                    if (id != null) {
                        name = ""
                        addedIds = listOf(id) + addedIds
                        highlightId = id
                        show("$trimmed has been added to the group", ok = true)
                    } else {
                        show("Couldn't add $trimmed. Try again.", ok = false)
                    }
                }
            }
        }
    }

    fun submit() = add(name) { onAddMember(name.trim().replace(Regex("\\s+"), " ")) }

    val ordered = addedIds.mapNotNull { id -> members.find { it.userId == id } } + members.filter { it.userId !in addedIds }
    Box {
        Column {
            SheetPageHeader("Add new member", onBack)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                EvTextField(
                    name,
                    { name = it },
                    modifier = Modifier.weight(1f).focusRequester(focus),
                    placeholder = "Their name",
                    minHeight = 48.dp,
                    onDone = ::submit,
                )
                EvButton("Add", ::submit, small = true, fillMaxWidth = false)
            }
            KnownPeopleList(matchKnown(known, name)) { k -> add(k.name) { onAddKnown(k) } }
            Text(
                "In this group",
                Modifier.padding(start = 4.dp, top = 16.dp, bottom = 8.dp),
                color = c.ink2,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                EvCard(Modifier.fillMaxWidth()) {
                    ordered.forEachIndexed { i, m ->
                        val tint by animateColorAsState(
                            if (m.userId == highlightId) c.settledTint else Color.Transparent,
                            EvMotion.standard(),
                        )
                        Box(Modifier.background(tint)) { MemberRow(m, showDivider = i > 0) }
                    }
                }
            }
            Spacer(Modifier.height(BarSlot))
        }
        AnimatedVisibility(
            visible = bar != null,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically(EvMotion.standard()) { it / 2 } + fadeIn(EvMotion.standard()),
            exit = slideOutVertically(EvMotion.quick()) { it / 2 } + fadeOut(EvMotion.quick()),
        ) {
            // Holds the last message while it fades, instead of blanking mid-exit.
            var shown by remember { mutableStateOf(bar) }
            bar?.let { shown = it }
            shown?.let { AddMemberBar(it) }
        }
    }
}

/** The green "added" or red "didn't add" bar, sitting at the bottom of the sheet just above the keyboard. */
@Composable
private fun AddMemberBar(bar: AddBar) {
    val c = EvenlyTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (bar.ok) c.settled else c.danger)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (bar.ok) EvIcon(EvIcons.Check, size = 16.dp, tint = Color.White)
        Text(bar.text, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 2)
    }
}

/**
 * The group's join link as a QR, with Share and Copy, as a page of the Members sheet. White plate and dark
 * modules so it scans in both themes. Hidden until the token syncs: the test is for a real "/j/<token>" link
 * rather than the "Generating link…" sentinel. Do NOT reinstate a brand-prefix check here; the rebrand
 * silently falsified exactly that and the QR stopped rendering, because the guard and the link are built in
 * two different files.
 */
@Composable
private fun InviteQrPage(
    groupName: String,
    inviteLink: String,
    sharing: Boolean,
    onShare: () -> Unit,
    onCopy: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = EvenlyTheme.colors
    var copied by remember(inviteLink) { mutableStateOf(false) }
    Column(modifier) {
        SheetPageHeader("Invite to group", onBack)
        Text(
            "Anyone with this link joins $groupName. To split just one bill, share that bill's QR instead.",
            Modifier.fillMaxWidth().padding(bottom = 12.dp, start = 4.dp, end = 4.dp),
            color = c.ink2,
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
        )
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
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(c.surface)
                .border(1.dp, c.border, RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            EvIcon(EvIcons.Link, size = 16.dp, tint = c.ink3)
            Text(
                inviteLink.removePrefix("https://"),
                modifier = Modifier.weight(1f),
                color = c.ink,
                fontSize = 13.sp,
                fontFamily = EvenlyTheme.monoFamily,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // The first iOS share sheet of a launch takes about a second to load; the label says it's coming.
            EvButton(
                text = if (sharing) "Opening…" else "Share link",
                onClick = onShare,
                modifier = Modifier.weight(1f),
                enabled = !sharing,
                leadingIcon = EvIcons.Share,
            )
            EvButton(
                text = if (copied) "Link copied" else "Copy link",
                onClick = {
                    onCopy()
                    copied = true
                },
                modifier = Modifier.weight(1f),
                variant = ButtonVariant.Secondary,
                leadingIcon = if (copied) EvIcons.Check else EvIcons.Copy,
            )
        }
    }
}
