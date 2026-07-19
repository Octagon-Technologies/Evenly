package da.chelimo.sharecost.di

import da.chelimo.sharecost.data.db.getRoomDatabase
import da.chelimo.sharecost.data.db.shareCostDatabaseBuilder
import da.chelimo.sharecost.data.remote.installShareCostDefaults
import da.chelimo.sharecost.platform.ConnectivityObserver
import da.chelimo.sharecost.platform.FilePicker
import da.chelimo.sharecost.platform.ImageProcessor
import da.chelimo.sharecost.platform.NotificationPermission
import da.chelimo.sharecost.platform.PlatformShare
import da.chelimo.sharecost.platform.PostHogAnalytics
import da.chelimo.sharecost.platform.PushService
import da.chelimo.sharecost.platform.ReceiptFileStore
import da.chelimo.sharecost.platform.ReceiptUploadScheduler
import da.chelimo.sharecost.platform.ScAnalytics
import da.chelimo.sharecost.platform.SecureStorage
import da.chelimo.sharecost.platform.UrlOpener
import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import org.koin.core.module.Module
import org.koin.dsl.module

actual fun platformModule(): Module = module {
    single { getRoomDatabase(shareCostDatabaseBuilder()) }
    single { HttpClient(Darwin) { installShareCostDefaults() } }

    // Platform abstractions (06 §5). iOS actuals resolve their own system handles.
    single<ScAnalytics> { PostHogAnalytics() }
    single { SecureStorage() }
    single { ConnectivityObserver() }
    single { UrlOpener() }
    single { PlatformShare() }
    single { PushService() }
    single { FilePicker() }
    single { NotificationPermission() }
    single { ImageProcessor() }
    single { ReceiptFileStore() }
    single { ReceiptUploadScheduler() }
}
