package da.chelimo.sharecost.di

import org.koin.core.context.startKoin
import org.koin.dsl.KoinAppDeclaration
import org.koin.dsl.module

/** App-wide singletons (06 §3). Use-case / view-model wiring lands here as later layers arrive. */
val appModule = module {
    // intentionally empty for now — the data layer is wired in [dataModule] + [platformModule].
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
        modules(appModule, dataModule, platformModule())
    }
}
