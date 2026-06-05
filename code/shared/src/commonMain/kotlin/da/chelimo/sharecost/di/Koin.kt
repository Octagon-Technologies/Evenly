package da.chelimo.sharecost.di

import org.koin.core.context.startKoin
import org.koin.dsl.KoinAppDeclaration
import org.koin.dsl.module

/** Koin modules (06 §3). Populated as repositories / use cases / view models land. */
val appModule = module {
    // intentionally empty for the skeleton
}

private var koinStarted = false

/** Idempotent Koin start — safe to call from each platform entry point (Android + iOS). */
fun initKoin(appDeclaration: KoinAppDeclaration = {}) {
    if (koinStarted) return
    koinStarted = true
    startKoin {
        appDeclaration()
        modules(appModule)
    }
}
