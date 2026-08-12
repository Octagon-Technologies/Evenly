package app.splitevenly.di

import app.splitevenly.data.db.EvenlyDatabase
import app.splitevenly.data.remote.fx.FrankfurterFxFetcher
import app.splitevenly.data.remote.fx.FxRateFetcher
import app.splitevenly.data.remote.supabase.GroupExportHttp
import app.splitevenly.data.remote.supabase.ReceiptOcrHttp
import app.splitevenly.data.remote.supabase.ReceiptStorage
import app.splitevenly.data.remote.supabase.RemoteGroupGateway
import app.splitevenly.data.upload.ReceiptUploadHttp
import app.splitevenly.data.repository.ActivityRepositoryImpl
import app.splitevenly.data.repository.BillRepositoryImpl
import app.splitevenly.data.remote.revenuecat.PassActivationGateway
import app.splitevenly.data.remote.revenuecat.SubscriberSyncGateway
import app.splitevenly.data.repository.ProPurchaseCoordinator
import app.splitevenly.data.repository.ProRepositoryImpl
import app.splitevenly.data.repository.WebBillLinkRepositoryImpl
import app.splitevenly.data.repository.CategoryRepositoryImpl
import app.splitevenly.data.repository.ExpenseRepositoryImpl
import app.splitevenly.data.repository.FxRepositoryImpl
import app.splitevenly.data.repository.GroupRepositoryImpl
import app.splitevenly.data.repository.ProfileRepositoryImpl
import app.splitevenly.data.repository.SettlementRepositoryImpl
import app.splitevenly.domain.repository.ActivityRepository
import app.splitevenly.domain.repository.CategoryRepository
import app.splitevenly.domain.receipt.ReceiptOcr
import app.splitevenly.domain.repository.BillRepository
import app.splitevenly.domain.export.GroupExporter
import app.splitevenly.domain.pro.ProBilling
import app.splitevenly.domain.repository.ProRepository
import app.splitevenly.domain.repository.WebBillLinkRepository
import app.splitevenly.data.claim.IdentityPromptSnooze
import app.splitevenly.data.claim.PlaceholderClaimCoordinator
import app.splitevenly.data.remote.supabase.PlaceholderClaimGateway
import app.splitevenly.data.remote.supabase.JoinItemPortionGateway
import app.splitevenly.data.remote.supabase.ScanUsageGateway
import app.splitevenly.data.remote.supabase.WebBillLinkGateway
import app.splitevenly.platform.AppForeground
import app.splitevenly.domain.repository.ExpenseRepository
import app.splitevenly.domain.repository.FxRepository
import app.splitevenly.domain.repository.GroupRepository
import app.splitevenly.domain.repository.ProfileRepository
import app.splitevenly.domain.repository.SettlementRepository
import app.splitevenly.platform.EvAnalytics
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Data layer wiring (06 §3): DAOs off the singleton [EvenlyDatabase], the FX fetcher over the
 * platform `HttpClient`, and each repository implementation bound to its domain interface. The DB
 * and `HttpClient` singletons are supplied by [platformModule] (they need platform constructors).
 * Repositories use their default `Clock.System`; tests construct them directly with a fixed clock.
 */
val dataModule: Module = module {
    // DAOs
    single { get<EvenlyDatabase>().userDao() }
    single { get<EvenlyDatabase>().groupDao() }
    single { get<EvenlyDatabase>().memberDao() }
    single { get<EvenlyDatabase>().expenseDao() }
    single { get<EvenlyDatabase>().shareDao() }
    single { get<EvenlyDatabase>().settlementDao() }
    single { get<EvenlyDatabase>().fxRateDao() }
    single { get<EvenlyDatabase>().fxCurrencyDao() }
    single { get<EvenlyDatabase>().conflictDao() }
    single { get<EvenlyDatabase>().expenseEditConflictDao() }
    single { get<EvenlyDatabase>().commentDao() }
    single { get<EvenlyDatabase>().receiptDao() }
    single { get<EvenlyDatabase>().receiptUploadDao() }
    single { get<EvenlyDatabase>().historyEventDao() }
    single { get<EvenlyDatabase>().categoryDao() }
    single { get<EvenlyDatabase>().expenseItemDao() }
    single { get<EvenlyDatabase>().itemClaimDao() }
    single { get<EvenlyDatabase>().itemShareDao() }
    single { get<EvenlyDatabase>().billParticipantDao() }
    single { get<EvenlyDatabase>().pendingItemEditDao() }
    single { get<EvenlyDatabase>().groupPassDao() }
    single { get<EvenlyDatabase>().userSubscriptionDao() }
    single { get<EvenlyDatabase>().groupScanUsageDao() }
    single { get<EvenlyDatabase>().supersededNoticeDao() }
    single { get<EvenlyDatabase>().placeholderMergeDao() }
    single { get<EvenlyDatabase>().placeholderClaimAnswerDao() }

    // Remote
    single<FxRateFetcher> { FrankfurterFxFetcher(get()) }
    // Ktor-based Storage uploader with byte progress (the Android WorkManager path; iOS uses native sessions).
    single { ReceiptUploadHttp(get()) }
    // Receipt OCR for "Split the bill" — calls the extract-receipt edge function (Claude vision). The
    // access-token provider (bound only when Supabase is configured) lets it authenticate as the user so
    // the server-side per-user rate limit engages (P1 #11); getOrNull keeps the offline/stub build working.
    single<ReceiptOcr> { ReceiptOcrHttp(get(), get(), get(), accessTokenProvider = getOrNull()) }
    // Group CSV export (PRO_PASS_SPEC.md §3). Server-rendered because it reads every member's rows, and
    // Pro-gated on that same server: a client-side gate on a reachable endpoint is decoration.
    single<GroupExporter> { GroupExportHttp(get(), get(), accessTokenProvider = getOrNull()) }

    // Repositories
    // GroupRepository takes the optional remote gateway so join-by-link resolves never-synced groups (F7).
    single<GroupRepository> { GroupRepositoryImpl(get(), get(), get(), get(), get(), get(), get(), get(), remoteGroups = getOrNull<RemoteGroupGateway>(), receiptDao = get(), analytics = getOrNull<EvAnalytics>()) }
    // ExpenseRepository takes the FX repo + group DAO so balances convert to the group base currency (F2),
    // and the history DAO so create/edit/delete append to the activity log (F5).
    single<ExpenseRepository> { ExpenseRepositoryImpl(get(), get(), fxRepository = get(), groupDao = get(), historyEventDao = get(), editConflictDao = get(), supersededNoticeDao = get(), analytics = getOrNull<EvAnalytics>(), settlementDao = get()) }
    // "Split the bill" (itemized): items + claims + extras → derived shares (deterministic ids).
    single<BillRepository> {
        BillRepositoryImpl(
            get(), get(), get(), get(), get(), get(),
            historyEventDao = get(),
            analytics = getOrNull<EvAnalytics>(),
            joinItemGateway = getOrNull<JoinItemPortionGateway>(),
            pendingItemEditDao = get(),
        )
    }
    // The payer's web claim link (WEB_CLAIM_SPEC.md §3.9.3). Not local-first on purpose: a link is a
    // server-side authorisation, so the gateway is required and its absence is reported, never faked.
    single<WebBillLinkRepository> { WebBillLinkRepositoryImpl(getOrNull<WebBillLinkGateway>(), get()) }
    // Evenly Pro, read-only: the Pro badge and the free-scan meter (PRO_PASS_SPEC.md). No write path
    // exists here by design, and the gateway is optional so the offline build simply shows no meter.
    single<ProRepository> { ProRepositoryImpl(get(), get(), get(), scanUsageGateway = getOrNull<ScanUsageGateway>()) }
    // Buy-then-activate for a group pass (PRO_PASS_SPEC.md §6.2). A single, because the in-flight
    // purchase it parks has to outlive the sheet that started it: the failure it exists for is the store
    // charging and our activate call dying, and that must survive the screen going away.
    single {
        ProPurchaseCoordinator(
            billing = get<ProBilling>(),
            storage = get(),
            activation = getOrNull<PassActivationGateway>(),
            subscriberSync = getOrNull<SubscriberSyncGateway>(),
        )
    }
    single<SettlementRepository> { SettlementRepositoryImpl(get(), get(), historyEventDao = get(), analytics = getOrNull<EvAnalytics>()) }
    single<FxRepository> { FxRepositoryImpl(get(), get(), get()) }
    // "Is this you?" claims: schedules the merge behind a 5-second undo window and runs the
    // first-claim-wins guard before writing anything. A single, because it has to outlive the screen
    // that started it (backgrounding flushes a pending claim).
    // "Later" and the closing note are deliberately not persisted; see IdentityPromptSnooze.
    single { IdentityPromptSnooze() }
    single {
        PlaceholderClaimCoordinator(
            groups = get(),
            gateway = getOrNull<PlaceholderClaimGateway>(),
            appForeground = get<AppForeground>(),
        )
    }
    single<ProfileRepository> { ProfileRepositoryImpl(get(), get()) }
    // Per-group categories with copy-on-write defaults (the Edit-categories screen + the expense picker).
    single<CategoryRepository> { CategoryRepositoryImpl(get()) }
    // Expense activity (F5): comments + receipts + history. ReceiptStorage is bound only when Supabase
    // is configured (it needs the client), so resolve it optionally and degrade gracefully if absent.
    single<ActivityRepository> { ActivityRepositoryImpl(get(), get(), get(), get(), receiptStorage = getOrNull<ReceiptStorage>()) }
}
