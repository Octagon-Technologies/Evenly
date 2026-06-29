package da.chelimo.sharecost.ui.screen.settings

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import da.chelimo.sharecost.domain.expense.GroupCategory
import da.chelimo.sharecost.ui.components.ButtonVariant
import da.chelimo.sharecost.ui.components.ScButton
import da.chelimo.sharecost.ui.components.ScCard
import da.chelimo.sharecost.ui.components.ScField
import da.chelimo.sharecost.ui.components.ScIconButton
import da.chelimo.sharecost.ui.components.ScModalScaffold
import da.chelimo.sharecost.ui.components.ScSectionLabel
import da.chelimo.sharecost.ui.components.ScTextField
import da.chelimo.sharecost.ui.components.ScTopBar
import da.chelimo.sharecost.ui.components.icon.ScIcon
import da.chelimo.sharecost.ui.components.icon.ScIcons
import da.chelimo.sharecost.ui.components.topHairline
import da.chelimo.sharecost.ui.screen.group.CategoryCatalog
import da.chelimo.sharecost.ui.theme.ShareCostTheme

/**
 * Per-group category management. DI-free (callbacks only, so `@Preview` works); the route wrapper
 * binds these to [da.chelimo.sharecost.domain.repository.CategoryRepository]. Defaults are
 * copy-on-write — the repository materializes a custom row on first edit, so the UI just edits.
 */
@Composable
fun EditCategoriesScreen(
    categories: List<GroupCategory>,
    onBack: () -> Unit = {},
    onAdd: (label: String, iconToken: String, colorHex: Long) -> Unit = { _, _, _ -> },
    onRename: (key: String, label: String) -> Unit = { _, _ -> },
    onRestyle: (key: String, iconToken: String, colorHex: Long) -> Unit = { _, _, _ -> },
    onDelete: (key: String) -> Unit = {},
) {
    val c = ShareCostTheme.colors
    var showAdd by remember { mutableStateOf(false) }
    var editTarget by remember { mutableStateOf<GroupCategory?>(null) }

    Column(Modifier.fillMaxSize().background(c.surface).systemBarsPadding()) {
        ScTopBar(
            title = "Categories",
            navIcon = { ScIconButton(ScIcons.Back, onBack) },
        )
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column {
                ScSectionLabel("Categories · ${categories.size}")
                ScCard {
                    categories.forEachIndexed { i, cat ->
                        val tint = Color(cat.colorHex)
                        Row(
                            modifier = Modifier.fillMaxWidth()
                                .then(if (i > 0) Modifier.topHairline(c.border) else Modifier)
                                .clickable { editTarget = cat }
                                .heightIn(min = 56.dp)
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Box(
                                Modifier.size(36.dp).clip(RoundedCornerShape(10.dp))
                                    .background(tint.copy(alpha = 0.12f)),
                                contentAlignment = Alignment.Center,
                            ) {
                                ScIcon(CategoryCatalog.icon(cat.iconToken), size = 20.dp, tint = tint)
                            }
                            Text(
                                cat.label,
                                color = c.ink,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f),
                            )
                            ScIcon(ScIcons.Edit, size = 18.dp, tint = c.ink3)
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .then(if (categories.isNotEmpty()) Modifier.topHairline(c.border) else Modifier)
                            .clickable { showAdd = true }
                            .heightIn(min = 56.dp)
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        ScIcon(ScIcons.Plus, size = 18.dp, tint = c.blue)
                        Text("Add category", color = c.blue, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (showAdd) {
        CategoryEditorModal(
            title = "New category",
            initialLabel = "",
            initialIcon = CategoryCatalog.icons.firstOrNull()?.token ?: "tag",
            initialColor = CategoryCatalog.colors.firstOrNull() ?: 0xFF2563EB,
            onDismiss = { showAdd = false },
            onSave = { label, icon, color ->
                onAdd(label, icon, color)
                showAdd = false
            },
            onDelete = null,
        )
    }

    editTarget?.let { target ->
        CategoryEditorModal(
            title = "Edit category",
            initialLabel = target.label,
            initialIcon = target.iconToken,
            initialColor = target.colorHex,
            onDismiss = { editTarget = null },
            onSave = { label, icon, color ->
                if (label != target.label) onRename(target.key, label)
                if (icon != target.iconToken || color != target.colorHex) onRestyle(target.key, icon, color)
                editTarget = null
            },
            onDelete = {
                onDelete(target.key)
                editTarget = null
            },
        )
    }
}

/**
 * Shared add/edit modal: a name field, an icon picker grid, and a color swatch row. [onDelete] is
 * null for the add flow; when present a Delete button reveals an inline "Tap again to delete" confirm
 * (no blocking system dialog — matches the app's lightweight modal style).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CategoryEditorModal(
    title: String,
    initialLabel: String,
    initialIcon: String,
    initialColor: Long,
    onDismiss: () -> Unit,
    onSave: (label: String, iconToken: String, colorHex: Long) -> Unit,
    onDelete: (() -> Unit)?,
) {
    val c = ShareCostTheme.colors
    var label by remember { mutableStateOf(initialLabel) }
    var iconToken by remember { mutableStateOf(initialIcon) }
    var colorHex by remember { mutableStateOf(initialColor) }
    var confirmDelete by remember { mutableStateOf(false) }
    val tint = Color(colorHex)

    ScModalScaffold(onDismiss = onDismiss) {
        Text(title, color = c.ink, fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 12.dp))

        ScField("Name") { ScTextField(label, { label = it }, placeholder = "e.g. Groceries") }

        ScField("Icon", modifier = Modifier.padding(top = 16.dp)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                CategoryCatalog.icons.forEach { option ->
                    val on = option.token == iconToken
                    val shape = RoundedCornerShape(12.dp)
                    Box(
                        Modifier.size(44.dp).clip(shape)
                            .background(if (on) tint.copy(alpha = 0.12f) else c.page)
                            .border(if (on) 2.dp else 1.dp, if (on) tint else c.borderStrong, shape)
                            .clickable { iconToken = option.token },
                        contentAlignment = Alignment.Center,
                    ) {
                        ScIcon(option.icon, size = 20.dp, tint = if (on) tint else c.ink2)
                    }
                }
            }
        }

        ScField("Color", modifier = Modifier.padding(top = 16.dp)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                CategoryCatalog.colors.forEach { swatch ->
                    val on = swatch == colorHex
                    Box(
                        Modifier.size(32.dp).clip(CircleShape).background(Color(swatch))
                            .then(if (on) Modifier.border(3.dp, c.page, CircleShape).border(2.dp, c.ink, CircleShape) else Modifier)
                            .clickable { colorHex = swatch },
                    )
                }
            }
        }

        Box(Modifier.fillMaxWidth().padding(top = 20.dp)) {
            ScButton("Save", { if (label.isNotBlank()) onSave(label.trim(), iconToken, colorHex) }, enabled = label.isNotBlank())
        }

        if (onDelete != null) {
            Box(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                if (confirmDelete) {
                    ScButton("Tap again to delete", onDelete, leadingIcon = ScIcons.Trash, variant = ButtonVariant.Danger)
                } else {
                    ScButton("Delete category", { confirmDelete = true }, leadingIcon = ScIcons.Trash, variant = ButtonVariant.Danger)
                }
            }
        }
    }
}

@Preview
@Composable
private fun EditCategoriesPreview() {
    ShareCostTheme {
        EditCategoriesScreen(
            categories = listOf(
                GroupCategory("food", "Food & drink", "food", 0xFFF59E0B, isDefault = true, sortOrder = 0),
                GroupCategory("9f2c-uuid", "Scuba gear", "ticket", 0xFF2563EB, isDefault = false, sortOrder = 1),
            ),
        )
    }
}
