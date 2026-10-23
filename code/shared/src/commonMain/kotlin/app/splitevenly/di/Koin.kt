package app.splitevenly.di

import app.splitevenly.platform.AppForeground
import org.koin.core.context.startKoin
import org.koin.dsl.KoinAppDeclaration
import org.koin.dsl.module

/** App-wide singletons (06 §3). Use-case / view-model wiring lands here as later layers arrive. */
val appModule = module {
    // "Is the app on screen", written by the Compose root, read by SyncManager to gate sync. Lives
    // here (always loaded) rather than in authModule's `isConfigured` branch — App.kt injects it
    // unconditionally, so the offline/stub build needs it too.
    single { AppForeground() }
}

private var koinStarted = false

/**
 * Idempotent Koin start — safe to call from each platform entry point. Android starts via
 * [initKoinAndroid] (which binds the `Context`); iOS calls this directly.
 */
fun initKoin(appDeclaration: KoinAppDeclaration = {}) {
    if (koinStarted) return
    koinStarted = true
    startKoin {
        appDeclaration()
        modules(appModule, dataModule, authModule, viewModelModule, platformModule())
    }
}
