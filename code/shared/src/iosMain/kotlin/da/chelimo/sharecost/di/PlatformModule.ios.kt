package da.chelimo.sharecost.di

import da.chelimo.sharecost.data.db.getRoomDatabase
import da.chelimo.sharecost.data.db.shareCostDatabaseBuilder
import da.chelimo.sharecost.data.remote.installShareCostDefaults
import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import org.koin.core.module.Module
import org.koin.dsl.module

actual fun platformModule(): Module = module {
    single { getRoomDatabase(shareCostDatabaseBuilder()) }
    single { HttpClient(Darwin) { installShareCostDefaults() } }
}
