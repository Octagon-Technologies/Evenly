package da.chelimo.sharecost

import androidx.compose.ui.window.ComposeUIViewController
import da.chelimo.sharecost.di.initKoin
import platform.UIKit.UIViewController

fun MainViewController(): UIViewController {
    initKoin()
    return ComposeUIViewController { App() }
}
