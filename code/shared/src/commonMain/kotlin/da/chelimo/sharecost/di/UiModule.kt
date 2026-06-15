package da.chelimo.sharecost.di

import da.chelimo.sharecost.data.auth.StubAuthSession
import da.chelimo.sharecost.domain.auth.AuthSession
import da.chelimo.sharecost.ui.screen.home.HomeViewModel
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

/** The (stub) auth session — the seam Supabase auth replaces later (06 §5.5). */
val authModule = module {
    single<AuthSession> { StubAuthSession(get()) }
}

/** Per-screen ViewModels (06 §3.3), retrieved in composables via `koinViewModel()`. */
val viewModelModule = module {
    viewModelOf(::HomeViewModel)
}
