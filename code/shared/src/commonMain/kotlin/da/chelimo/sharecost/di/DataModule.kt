package da.chelimo.sharecost.di

import da.chelimo.sharecost.data.db.ShareCostDatabase
import da.chelimo.sharecost.data.remote.fx.FrankfurterFxFetcher
import da.chelimo.sharecost.data.remote.fx.FxRateFetcher
import da.chelimo.sharecost.data.repository.ExpenseRepositoryImpl
import da.chelimo.sharecost.data.repository.FxRepositoryImpl
import da.chelimo.sharecost.data.repository.GroupRepositoryImpl
import da.chelimo.sharecost.data.repository.SettlementRepositoryImpl
import da.chelimo.sharecost.domain.repository.ExpenseRepository
import da.chelimo.sharecost.domain.repository.FxRepository
import da.chelimo.sharecost.domain.repository.GroupRepository
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
    single { get<ShareCostDatabase>().groupDao() }
    single { get<ShareCostDatabase>().memberDao() }
    single { get<ShareCostDatabase>().expenseDao() }
    single { get<ShareCostDatabase>().shareDao() }
    single { get<ShareCostDatabase>().settlementDao() }
    single { get<ShareCostDatabase>().fxRateDao() }

    // Remote
    single<FxRateFetcher> { FrankfurterFxFetcher(get()) }

    // Repositories
    single<GroupRepository> { GroupRepositoryImpl(get(), get()) }
    single<ExpenseRepository> { ExpenseRepositoryImpl(get(), get()) }
    single<SettlementRepository> { SettlementRepositoryImpl(get(), get()) }
    single<FxRepository> { FxRepositoryImpl(get(), get()) }
}
