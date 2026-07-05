package da.chelimo.sharecost.di

import da.chelimo.sharecost.data.db.getRoomDatabase
import da.chelimo.sharecost.data.db.shareCostDatabaseBuilder
import da.chelimo.sharecost.data.remote.installShareCostDefaults
import da.chelimo.sharecost.platform.ConnectivityObserver
import da.chelimo.sharecost.platform.CurrentActivity
import da.chelimo.sharecost.platform.FilePicker
import da.chelimo.sharecost.platform.ImageProcessor
import da.chelimo.sharecost.platform.PlatformShare
import da.chelimo.sharecost.platform.PushService
import da.chelimo.sharecost.platform.ReceiptFileStore
import da.chelimo.sharecost.platform.ReceiptUploadScheduler
import da.chelimo.sharecost.platform.SecureStorage
import da.chelimo.sharecost.platform.UrlOpener
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.Module
import org.koin.dsl.module

actual fun platformModule(): Module = module {
    single { getRoomDatabase(shareCostDatabaseBuilder(androidContext())) }
    single { HttpClient(OkHttp) { installShareCostDefaults() } }

    // Platform abstractions (06 §5). Android actuals need a Context / the foreground Activity.
    single { SecureStorage(androidContext()) }
    single { ConnectivityObserver(androidContext()) }
    single { UrlOpener(androidContext()) }
    single { PlatformShare(androidContext()) }
    single { PushService() }
    single { FilePicker { CurrentActivity.get() } }
    single { ImageProcessor() }
    single { ReceiptFileStore(androidContext()) }
    single { ReceiptUploadScheduler(androidContext()) }
}
