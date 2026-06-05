package da.chelimo.sharecost.ui.screen.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import da.chelimo.sharecost.ui.theme.ShareCostTheme

/** Placeholder Home screen for the architecture skeleton — replace with the real home (05 §3). */
@Composable
fun HomeScreen() {
    Scaffold { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = "ShareCost",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = "Architecture skeleton is wired up.",
                style = MaterialTheme.typography.bodyMedium,
                color = ShareCostTheme.colors.textMuted,
                textAlign = TextAlign.Center,
            )
        }
    }
}
