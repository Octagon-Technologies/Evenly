package app.splitevenly.di

import app.splitevenly.data.auth.StubAuthSession
import app.splitevenly.data.auth.SupabaseAuthSession
import app.splitevenly.data.db.EvenlyDatabase
import app.splitevenly.data.remote.supabase.FeedbackHttp
import app.splitevenly.data.remote.supabase.JoinItemPortionGateway
import app.splitevenly.data.remote.supabase.PlaceholderClaimGateway
import app.splitevenly.data.remote.supabase.PushController
import app.splitevenly.data.remote.supabase.ReceiptStorage
import app.splitevenly.data.remote.supabase.RemoteGroupGateway
import app.splitevenly.data.remote.supabase.SupabaseConfig
import app.splitevenly.data.remote.supabase.SupabaseJoinItemPortionGateway
import app.splitevenly.data.remote.supabase.SupabasePlaceholderClaimGateway
import app.splitevenly.data.remote.supabase.SupabaseReceiptStorage
import app.splitevenly.data.remote.supabase.SupabaseRemoteGroupGateway
import app.splitevenly.data.remote.supabase.SupabaseWebBillLinkGateway
import app.splitevenly.data.remote.supabase.SyncEngine
import app.splitevenly.data.remote.supabase.SyncManager
import app.splitevenly.data.remote.supabase.WebBillLinkGateway
import app.splitevenly.data.remote.supabase.createEvenlySupabaseClient
import app.splitevenly.data.repository.FeedbackOutbox
import app.splitevenly.data.upload.AccessTokenProvider
import app.splitevenly.data.upload.ReceiptUploadManager
import app.splitevenly.domain.auth.AuthSession
import app.splitevenly.domain.feedback.FeedbackSubmitter
import app.splitevenly.platform.AppForeground
import app.splitevenly.platform.ConnectivityObserver
import app.splitevenly.platform.EvAnalytics
import app.splitevenly.platform.PushService
import app.splitevenly.platform.SecureStorage
import app.splitevenly.ui.screen.auth.HomeGateViewModel
import app.splitevenly.ui.screen.group.GroupFilterStore
import app.splitevenly.ui.screen.home.HomeViewModel
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.ktor.client.HttpClient
import kotlinx.coroutines.flow.map
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

/**
 * Auth + sync wiring (06 §5.5). With real [SupabaseConfig] credentials this binds the live
 * [SupabaseAuthSession] + [SyncEngine] (Supabase client, Room round-trip); with the placeholder
 * config it stays on the local-only [StubAuthSession], so the app runs fully offline until configured.
 */
val authModule =
    module {
        if (SupabaseConfig.isConfigured) {
            single { createEvenlySupabaseClient() }
            single { SyncEngine(get<SupabaseClient>(), get<EvenlyDatabase>()) }
            // Live sync driver (F7): push-on-write + Realtime pull + periodic safety net.
            single { SyncManager(get<SupabaseClient>(), get<SyncEngine>(), get<EvenlyDatabase>()) }
            // Push registration + delivery glue (F7): needs the platform PushService (from platformModule).
            single { PushController(get<PushService>(), get<SyncEngine>(), get<SupabaseClient>()) }
            single<AuthSession> {
                SupabaseAuthSession(
                    get<SupabaseClient>(),
                    get(),
                    get<SyncEngine>(),
                    get<SyncManager>(),
                    get<PushController>(),
                    get<AppForeground>(),
                    analytics = getOrNull<EvAnalytics>(),
                    httpClient = get<HttpClient>(),
                    signOutWipeDao = get<EvenlyDatabase>().signOutWipeDao(),
                    secureStorage = get<SecureStorage>(),
                )
            }
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
            // In-app feedback (ADMIN_FEEDBACK_SPEC.md step 8). Eager for the same reason the receipt
            // uploader is: a ticket queued on a dead network in a previous session should start draining
            // on launch, not wait for someone to open the form again. Bound only with Supabase, so the
            // offline build keeps the Settings row on its `mailto:`.
            single<FeedbackSubmitter>(createdAtStart = true) {
                FeedbackOutbox(
                    dao = get<EvenlyDatabase>().feedbackOutboxDao(),
                    http = FeedbackHttp(get<HttpClient>(), accessTokenProvider = get()),
                    connectivity = get<ConnectivityObserver>().status,
                    signedIn = get<AuthSession>().currentUserId.map { it != null },
                )
            }
            // Server-side invite-token resolution for cross-device join (F7).
            single<RemoteGroupGateway> { SupabaseRemoteGroupGateway(get<SupabaseClient>(), get<EvenlyDatabase>()) }
            // First-claim-wins guard for "Is this you?". Unbound in the offline build, where there is no
            // server to contend with and a claim simply applies.
            single<PlaceholderClaimGateway> { SupabasePlaceholderClaimGateway(get<SupabaseClient>()) }
            // Atomic solo-claim-to-shared-portion conversion (spec §5.3). Unbound offline: joining a line
            // someone else already claimed is unavailable rather than risking a local cross-user write.
            single<JoinItemPortionGateway> { SupabaseJoinItemPortionGateway(get<SupabaseClient>()) }
            // The payer's bill-link lifecycle (WEB_CLAIM_SPEC.md §3.9.3). Reaches `web_bill_links` only via
            // security-definer RPCs, so `token_hash` never becomes readable through a table policy.
            single<WebBillLinkGateway> { SupabaseWebBillLinkGateway(get<SupabaseClient>()) }
        } else {
            single<AuthSession> { StubAuthSession(get()) }
        }
    }

/** Per-screen ViewModels (06 §3.3), retrieved in composables via `koinViewModel()`. */
val viewModelModule =
    module {
        viewModelOf(::HomeViewModel)
        // Scoped to the Home back-stack entry so the pending-deletion check runs once per entry to Home,
        // not once per recomposition (which would blank Home behind a network call on every Back).
        viewModelOf(::HomeGateViewModel)
        // Ephemeral expense-feed filter (F6), shared between the Filter sheet + the Expenses tab destinations.
        single { GroupFilterStore() }
    }
