package da.chelimo.sharecost.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import da.chelimo.sharecost.core.id.GroupId
import da.chelimo.sharecost.domain.repository.CategoryRepository
import da.chelimo.sharecost.ui.screen.settings.EditCategoriesScreen
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/** Edit categories, wired: streams the group's categories; adds, renames, restyles, and deletes. */
@Composable
fun EditCategoriesRoute(
    groupId: String,
    onBack: () -> Unit,
) {
    val repo = koinInject<CategoryRepository>()
    val gid = remember(groupId) { GroupId(groupId) }
    val categories by remember(gid) { repo.observeCategories(gid) }.collectAsStateWithLifecycle(emptyList())
    val scope = rememberCoroutineScope()

    EditCategoriesScreen(
        categories = categories,
        onBack = onBack,
        onAdd = { label, icon, color -> scope.launch { repo.addCategory(gid, label, icon, color) } },
        onRename = { key, label -> scope.launch { repo.renameCategory(gid, key, label) } },
        onRestyle = { key, icon, color -> scope.launch { repo.updateCategoryStyle(gid, key, icon, color) } },
        onDelete = { key -> scope.launch { repo.deleteCategory(gid, key) } },
    )
}
