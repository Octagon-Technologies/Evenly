package da.chelimo.sharecost.di

import da.chelimo.sharecost.data.auth.StubAuthSession
import da.chelimo.sharecost.data.auth.SupabaseAuthSession
import da.chelimo.sharecost.data.db.ShareCostDatabase
import da.chelimo.sharecost.data.remote.supabase.PushController
import da.chelimo.sharecost.data.remote.supabase.ReceiptStorage
import da.chelimo.sharecost.data.remote.supabase.RemoteGroupGateway
import da.chelimo.sharecost.data.remote.supabase.SupabaseConfig
import da.chelimo.sharecost.data.remote.supabase.SupabaseReceiptStorage
import da.chelimo.sharecost.data.remote.supabase.SupabaseRemoteGroupGateway
import da.chelimo.sharecost.data.remote.supabase.SyncEngine
import da.chelimo.sharecost.data.remote.supabase.SyncManager
import da.chelimo.sharecost.data.remote.supabase.createShareCostSupabaseClient
import da.chelimo.sharecost.data.upload.AccessTokenProvider
import da.chelimo.sharecost.data.upload.ReceiptUploadManager
import da.chelimo.sharecost.domain.auth.AuthSession
import da.chelimo.sharecost.platform.AppForeground
import da.chelimo.sharecost.platform.PushService
import da.chelimo.sharecost.platform.ScAnalytics
import da.chelimo.sharecost.ui.screen.group.GroupFilterStore
import da.chelimo.sharecost.ui.screen.home.HomeViewModel
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

/**
 * Auth + sync wiring (06 §5.5). With real [SupabaseConfig] credentials this binds the live
 * [SupabaseAuthSession] + [SyncEngine] (Supabase client, Room round-trip); with the placeholder
 * config it stays on the local-only [StubAuthSession], so the app runs fully offline until configured.
 */
val authModule = module {
    if (SupabaseConfig.isConfigured) {
        single { createShareCostSupabaseClient() }
        single { SyncEngine(get<SupabaseClient>(), get<ShareCostDatabase>()) }
        // Live sync driver (F7): push-on-write + Realtime pull + periodic safety net.
        single { SyncManager(get<SupabaseClient>(), get<SyncEngine>(), get<ShareCostDatabase>()) }
        // Push registration + delivery glue (F7): needs the platform PushService (from platformModule).
        single { PushController(get<PushService>(), get<SyncEngine>(), get<SupabaseClient>()) }
        single<AuthSession> { SupabaseAuthSession(get<SupabaseClient>(), get(), get<SyncEngine>(), get<SyncManager>(), get<PushController>(), get<AppForeground>(), analytics = getOrNull<ScAnalytics>()) }
        // Receipt bytes (F5) go to Supabase Storage; bound only here, so the offline stub build has none.
        single<ReceiptStorage> { SupabaseReceiptStorage(get<SupabaseClient>()) }
        // Resilient receipt upload (D-22): token for the Storage REST PUT + the durable outbox manager.
        // Eager so leftover uploads from a prior session start draining on launch (the manager self-binds).
        single<AccessTokenProvider> { AccessTokenProvider { get<SupabaseClient>().auth.currentSessionOrNull()?.accessToken } }
        single(createdAtStart = true) {
            ReceiptUploadManager(
                uploadDao = get(),
                receiptDao = get(),
                historyDao = get(),
                fileStore = get(),
                imageProcessor = get(),
                scheduler = get(),
                http = get(),
                auth = get(),
                connectivity = get(),
                tokens = get(),
            )
        }
        // Server-side invite-token resolution for cross-device join (F7).
        single<RemoteGroupGateway> { SupabaseRemoteGroupGateway(get<SupabaseClient>(), get<ShareCostDatabase>()) }
    } else {
        single<AuthSession> { StubAuthSession(get()) }
    }
}

/** Per-screen ViewModels (06 §3.3), retrieved in composables via `koinViewModel()`. */
val viewModelModule = module {
    viewModelOf(::HomeViewModel)
    // Ephemeral expense-feed filter (F6), shared between the Filter sheet + the Expenses tab destinations.
    single { GroupFilterStore() }
}
