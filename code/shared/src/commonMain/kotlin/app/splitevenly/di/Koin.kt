package app.splitevenly.di

import app.splitevenly.data.remote.revenuecat.NoProBilling
import app.splitevenly.data.remote.revenuecat.ProConfig
import app.splitevenly.data.remote.revenuecat.RevenueCatBilling
import app.splitevenly.domain.pro.ProBilling
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
    // Evenly Pro's store surface (PRO_PASS_SPEC.md §9). Always bound, so no caller has to ask whether
    // monetization is switched on: with no RevenueCat keys the inert stand-in answers "unavailable" and
    // every Pro surface simply does not render. Lives here rather than in authModule's isConfigured
    // branch because the two configurations are independent — RevenueCat can be live before Supabase is
    // and the reverse, and App.kt injects this unconditionally.
    single<ProBilling> { if (ProConfig.isConfigured) RevenueCatBilling() else NoProBilling }
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
