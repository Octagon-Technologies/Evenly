import SwiftUI
import Shared

@main
struct iOSApp: App {
    // Firebase Messaging, notification delivery, and background-upload completion all land on
    // UIApplicationDelegate callbacks that SwiftUI has no equivalent for. See AppDelegate.swift.
    @UIApplicationDelegateAdaptor(AppDelegate.self) var appDelegate

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