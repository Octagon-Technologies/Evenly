package da.chelimo.sharecost.di

import da.chelimo.sharecost.data.db.ShareCostDatabase
import da.chelimo.sharecost.data.remote.fx.FrankfurterFxFetcher
import da.chelimo.sharecost.data.remote.fx.FxRateFetcher
import da.chelimo.sharecost.data.remote.supabase.ReceiptOcrHttp
import da.chelimo.sharecost.data.remote.supabase.ReceiptStorage
import da.chelimo.sharecost.data.remote.supabase.RemoteGroupGateway
import da.chelimo.sharecost.data.upload.ReceiptUploadHttp
import da.chelimo.sharecost.data.repository.ActivityRepositoryImpl
import da.chelimo.sharecost.data.repository.BillRepositoryImpl
import da.chelimo.sharecost.data.repository.CategoryRepositoryImpl
import da.chelimo.sharecost.data.repository.ExpenseRepositoryImpl
import da.chelimo.sharecost.data.repository.FxRepositoryImpl
import da.chelimo.sharecost.data.repository.GroupRepositoryImpl
import da.chelimo.sharecost.data.repository.ProfileRepositoryImpl
import da.chelimo.sharecost.data.repository.SettlementRepositoryImpl
import da.chelimo.sharecost.domain.repository.ActivityRepository
import da.chelimo.sharecost.domain.repository.CategoryRepository
import da.chelimo.sharecost.domain.receipt.ReceiptOcr
import da.chelimo.sharecost.domain.repository.BillRepository
import da.chelimo.sharecost.domain.repository.ExpenseRepository
import da.chelimo.sharecost.domain.repository.FxRepository
import da.chelimo.sharecost.domain.repository.GroupRepository
import da.chelimo.sharecost.domain.repository.ProfileRepository
import da.chelimo.sharecost.domain.repository.SettlementRepository
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Data layer wiring (06 §3): DAOs off the singleton [ShareCostDatabase], the FX fetcher over the
 * platform `HttpClient`, and each repository implementation bound to its domain interface. The DB
 * and `HttpClient` singletons are supplied by [platformModule] (they need platform constructors).
 * Repositories use their default `Clock.System`; tests construct them directly with a fixed clock.
 */
val dataModule: Module = module {
    // DAOs
    single { get<ShareCostDatabase>().userDao() }
    single { get<ShareCostDatabase>().groupDao() }
    single { get<ShareCostDatabase>().memberDao() }
    single { get<ShareCostDatabase>().expenseDao() }
    single { get<ShareCostDatabase>().shareDao() }
    single { get<ShareCostDatabase>().settlementDao() }
    single { get<ShareCostDatabase>().fxRateDao() }
    single { get<ShareCostDatabase>().conflictDao() }
    single { get<ShareCostDatabase>().expenseEditConflictDao() }
    single { get<ShareCostDatabase>().commentDao() }
    single { get<ShareCostDatabase>().receiptDao() }
    single { get<ShareCostDatabase>().receiptUploadDao() }
    single { get<ShareCostDatabase>().historyEventDao() }
    single { get<ShareCostDatabase>().categoryDao() }
    single { get<ShareCostDatabase>().expenseItemDao() }
    single { get<ShareCostDatabase>().itemClaimDao() }
    single { get<ShareCostDatabase>().itemShareDao() }
    single { get<ShareCostDatabase>().billParticipantDao() }

    // Remote
    single<FxRateFetcher> { FrankfurterFxFetcher(get()) }
    // Ktor-based Storage uploader with byte progress (the Android WorkManager path; iOS uses native sessions).
    single { ReceiptUploadHttp(get()) }
    // Receipt OCR for "Split the bill" — calls the extract-receipt edge function (Claude vision).
    single<ReceiptOcr> { ReceiptOcrHttp(get(), get(), get()) }

    // Repositories
    // GroupRepository takes the optional remote gateway so join-by-link resolves never-synced groups (F7).
    single<GroupRepository> { GroupRepositoryImpl(get(), get(), get(), get(), get(), get(), remoteGroups = getOrNull<RemoteGroupGateway>(), receiptDao = get()) }
    // ExpenseRepository takes the FX repo + group DAO so balances convert to the group base currency (F2),
    // and the history DAO so create/edit/delete append to the activity log (F5).
    single<ExpenseRepository> { ExpenseRepositoryImpl(get(), get(), fxRepository = get(), groupDao = get(), historyEventDao = get(), editConflictDao = get()) }
    // "Split the bill" (itemized): items + claims + extras → derived shares (deterministic ids).
    single<BillRepository> { BillRepositoryImpl(get(), get(), get(), get(), get(), get(), historyEventDao = get()) }
    single<SettlementRepository> { SettlementRepositoryImpl(get(), get(), historyEventDao = get()) }
    single<FxRepository> { FxRepositoryImpl(get(), get()) }
    single<ProfileRepository> { ProfileRepositoryImpl(get(), get()) }
    // Per-group categories with copy-on-write defaults (the Edit-categories screen + the expense picker).
    single<CategoryRepository> { CategoryRepositoryImpl(get()) }
    // Expense activity (F5): comments + receipts + history. ReceiptStorage is bound only when Supabase
    // is configured (it needs the client), so resolve it optionally and degrade gracefully if absent.
    single<ActivityRepository> { ActivityRepositoryImpl(get(), get(), get(), get(), receiptStorage = getOrNull<ReceiptStorage>()) }
}
