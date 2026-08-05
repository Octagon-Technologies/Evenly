import SwiftUI
import Shared

@main
struct iOSApp: App {
    var body: some Scene {
        WindowGroup {
            ContentView()
                // Complete an OAuth / magic-link sign-in opened via splitevenly://login-callback.
                .onOpenURL { url in
                    MainViewControllerKt.handleAuthDeeplink(url: url)
                }
        }
    }
}