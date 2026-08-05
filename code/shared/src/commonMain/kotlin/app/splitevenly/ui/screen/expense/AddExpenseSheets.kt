package app.splitevenly.ui.screen.expense

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.splitevenly.domain.expense.GroupCategory
import app.splitevenly.platform.PickSource
import app.splitevenly.ui.components.AvatarSize
import app.splitevenly.ui.components.EvAvatar
import app.splitevenly.ui.components.EvButton
import app.splitevenly.ui.components.EvField
import app.splitevenly.ui.components.EvModalScaffold
import app.splitevenly.ui.components.EvTextField
import app.splitevenly.ui.components.currencySymbol
import app.splitevenly.ui.components.icon.EvIcon
import app.splitevenly.ui.components.icon.EvIcons
import app.splitevenly.ui.components.topHairline
import app.splitevenly.ui.screen.bill.ScanSourceRow
import app.splitevenly.ui.screen.group.CategoryCatalog
import app.splitevenly.ui.theme.EvenlyTheme

/** The currencies offered in the expense-currency sheet (F2). The group base is seeded separately. */
private val CurrencyChoices = listOf("USD", "EUR", "GBP", "MXN", "CAD", "AUD", "JPY", "INR", "KES")

/** Creates a placeholder member from a typed name (also reachable from inside the payer sheet). */
@Composable
internal fun AddParticipantSheet(onAdd: (String) -> Unit, onDismiss: () -> Unit) {
    val c = EvenlyTheme.colors
    var newName by remember { mutableStateOf("") }
    EvModalScaffold(onDismiss = onDismiss) {
        Text("Add a participant", color = c.ink, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 12.dp))
        EvField("Name") { EvTextField(newName, { newName = it }, placeholder = "e.g. Bob") }
        Box(Modifier.fillMaxWidth().padding(top = 16.dp)) {
            EvButton("Add", { if (newName.isNotBlank()) { onAdd(newName.trim()); onDismiss() } }, enabled = newName.isNotBlank())
        }
    }
}

/** Source picker for a locally-attached receipt in the divide flow. */
@Composable
internal fun ReceiptSourceSheet(onPick: (PickSource) -> Unit, onDismiss: () -> Unit) {
    val c = EvenlyTheme.colors
    EvModalScaffold(onDismiss = onDismiss) {
        Text("Add a receipt", color = c.ink, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 4.dp))
        Text("Attach a photo or PDF. It uploads after you save.", color = c.ink2, fontSize = 13.sp, modifier = Modifier.padding(bottom = 8.dp))
        ReceiptSourceRow(EvIcons.Image, "Photos") { onDismiss(); onPick(PickSource.Photos) }
        ReceiptSourceRow(EvIcons.Archive, "Files (image or PDF)") { onDismiss(); onPick(PickSource.Files) }
        ReceiptSourceRow(EvIcons.Camera, "Take a photo") { onDismiss(); onPick(PickSource.Camera) }
    }
}

/** Source picker for the itemized body's receipt *scan* (the extracted items pre-fill the list). */
@Composable
internal fun ScanSourceSheet(onScan: (PickSource) -> Unit, onDismiss: () -> Unit) {
    val c = EvenlyTheme.colors
    EvModalScaffold(onDismiss = onDismiss) {
        Text("Scan the bill", color = c.ink, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 4.dp))
        Text(
            "A restaurant check or store receipt with line items. We'll pull them out for you, several pages read as one bill.",
            color = c.ink2, fontSize = 13.sp, modifier = Modifier.padding(bottom = 8.dp),
        )
        ScanSourceRow(EvIcons.Image, "Photos") { onDismiss(); onScan(PickSource.Photos) }
        ScanSourceRow(EvIcons.Archive, "Files (image or PDF)") { onDismiss(); onScan(PickSource.Files) }
        ScanSourceRow(EvIcons.Camera, "Take a photo") { onDismiss(); onScan(PickSource.Camera) }
    }
}

@Composable
internal fun CurrencySheet(currency: String, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val c = EvenlyTheme.colors
    EvModalScaffold(onDismiss = onDismiss) {
        Text("Expense currency", color = c.ink, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 12.dp))
        CurrencyChoices.forEach { code ->
            Row(
                Modifier.fillMaxWidth().clickable { onPick(code); onDismiss() }.padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(code, color = c.ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, fontFamily = EvenlyTheme.monoFamily, modifier = Modifier.width(48.dp))
                Text(currencySymbol(code), color = c.ink2, fontSize = 14.sp, modifier = Modifier.weight(1f))
                if (code == currency) EvIcon(EvIcons.Check, size = 18.dp, tint = c.blueText)
            }
        }
    }
}

/** Category picker. Tapping the active category clears it (the field is optional). */
@Composable
internal fun CategorySheet(
    categories: List<GroupCategory>,
    categoryId: String?,
    onPick: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val c = EvenlyTheme.colors
    EvModalScaffold(onDismiss = onDismiss) {
        Text("Category", color = c.ink, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 12.dp))
        // A 2-per-row grid of equal-width chips (not a fixed 152dp width) so two always fit
        // side by side regardless of the sheet's width, instead of collapsing to one long column.
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            categories.chunked(2).forEach { pair ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    pair.forEach { cat ->
                        val on = categoryId == cat.key
                        val tint = Color(cat.colorHex)
                        val shape = RoundedCornerShape(12.dp)
                        Row(
                            Modifier.weight(1f).clip(shape)
                                .background(if (on) tint.copy(alpha = 0.12f) else c.page)
                                .border(if (on) 2.dp else 1.dp, if (on) tint else c.borderStrong, shape)
                                .clickable {
                                    onPick(if (on) null else cat.key)
                                    onDismiss()
                                }
                                .padding(horizontal = 12.dp, vertical = 11.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            EvIcon(CategoryCatalog.icon(cat.iconToken), size = 18.dp, tint = tint)
                            Text(cat.label, color = c.ink, fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (on) EvIcon(EvIcons.Check, size = 15.dp, tint = tint)
                        }
                    }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

/**
 * "Who paid?" — a member, a brand-new placeholder member, or someone outside the group. Picking a member
 * also adds them to the split (they're still removable); an outside payer is never part of it.
 */
@Composable
internal fun PayerSheet(
    participants: List<AddParticipantUi>,
    effectivePayerId: String,
    isOutsidePayer: Boolean,
    outsidePayerName: String?,
    onPickMember: (String) -> Unit,
    onPickOutside: (String) -> Unit,
    onAddSomeoneNew: () -> Unit,
    onDismiss: () -> Unit,
) {
    val c = EvenlyTheme.colors
    var someoneElse by remember { mutableStateOf(isOutsidePayer) }
    var outsideDraft by remember { mutableStateOf(outsidePayerName ?: "") }
    EvModalScaffold(onDismiss = onDismiss) {
        Text("Who paid?", color = c.ink, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 12.dp))
        participants.forEach { p ->
            Row(
                Modifier.fillMaxWidth().clickable {
                    onPickMember(p.userId)
                    onDismiss()
                }.padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                EvAvatar(p.name, me = p.isMe, size = AvatarSize.Sm)
                Text(p.name, color = c.ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                if (!isOutsidePayer && p.userId == effectivePayerId) EvIcon(EvIcons.Check, size = 18.dp, tint = c.blueText)
            }
        }
        // Add a brand-new person right here (creates a placeholder member) — a member who paid must
        // exist first, and this covers "the payer isn't in the group yet".
        Box(Modifier.topHairline(c.border)) {
            Row(
                Modifier.fillMaxWidth().clickable { onDismiss(); onAddSomeoneNew() }.padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(Modifier.size(28.dp).clip(RoundedCornerShape(99.dp)).background(c.blueTint), contentAlignment = Alignment.Center) {
                    EvIcon(EvIcons.Plus, size = 15.dp, tint = c.blueText)
                }
                Text("Add someone new", color = c.blueText, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            }
        }
        // An outside payer (not a group member, not in the split) — clearer than the old "Someone else",
        // which testers read as "another member".
        Box(Modifier.topHairline(c.border)) {
            Row(
                Modifier.fillMaxWidth().clickable { someoneElse = true }.padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(Modifier.size(28.dp).clip(RoundedCornerShape(99.dp)).background(c.blueTint), contentAlignment = Alignment.Center) {
                    EvIcon(EvIcons.User, size = 15.dp, tint = c.blueText)
                }
                Text("Someone outside the group", color = c.ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                if (isOutsidePayer && !someoneElse) EvIcon(EvIcons.Check, size = 18.dp, tint = c.blueText)
            }
        }
        if (someoneElse) {
            Box(Modifier.padding(top = 4.dp)) {
                EvField("Their name") { EvTextField(outsideDraft, { outsideDraft = it }, placeholder = "e.g. the Airbnb host") }
            }
            Text(
                "An outside payer isn't part of the split, everyone owes them their share.",
                color = c.ink3, fontSize = 12.sp, lineHeight = 16.sp, modifier = Modifier.padding(top = 8.dp),
            )
            Box(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                EvButton("Done", {
                    if (outsideDraft.isNotBlank()) {
                        onPickOutside(outsideDraft.trim())
                        onDismiss()
                    }
                }, enabled = outsideDraft.isNotBlank())
            }
        }
    }
}

/** A tappable source row in the "Add a receipt" sheet (Photos / Files / Camera). */
@Composable
private fun ReceiptSourceRow(icon: ImageVector, label: String, onClick: () -> Unit) {
    val c = EvenlyTheme.colors
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        EvIcon(icon, size = 20.dp, tint = c.blueText)
        Text(label, color = c.ink, fontSize = 15.sp, fontWeight = FontWeight.Medium)
    }
}
