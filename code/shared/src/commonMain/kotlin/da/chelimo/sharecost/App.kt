package da.chelimo.sharecost

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import da.chelimo.sharecost.ui.navigation.ShareCostNavHost
import da.chelimo.sharecost.ui.navigation.ShareCostNavHost
import da.chelimo.sharecost.ui.theme.ShareCostTheme

@Composable
@Preview
fun App() {
    ShareCostTheme {
        ShareCostNavHost()
    }
}
