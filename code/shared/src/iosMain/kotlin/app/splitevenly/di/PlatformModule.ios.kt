package app.splitevenly.di

import app.splitevenly.data.db.getRoomDatabase
import app.splitevenly.data.db.evenlyDatabaseBuilder
import app.splitevenly.data.remote.installEvenlyDefaults
import app.splitevenly.platform.AppleSignIn
import app.splitevenly.platform.CameraPermission
import app.splitevenly.platform.ConnectivityObserver
import app.splitevenly.platform.FilePicker
import app.splitevenly.platform.ImageProcessor
import app.splitevenly.platform.NotificationPermission
import app.splitevenly.platform.PlatformShare
import app.splitevenly.platform.PostHogAnalytics
import app.splitevenly.platform.PushService
import app.splitevenly.platform.ReceiptFileStore
import app.splitevenly.platform.ReceiptUploadScheduler
import app.splitevenly.platform.EvAnalytics
import app.splitevenly.platform.SecureStorage
import app.splitevenly.platform.UrlOpener
import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import org.koin.core.module.Module
import org.koin.dsl.module

actual fun platformModule(): Module = module {
    single { getRoomDatabase(evenlyDatabaseBuilder()) }
    single { HttpClient(Darwin) { installEvenlyDefaults() } }

    // Platform abstractions (06 §5). iOS actuals resolve their own system handles.
    single { PostHogAnalytics() }
    single<EvAnalytics> { get<PostHogAnalytics>() }
    single { SecureStorage() }
    single { ConnectivityObserver() }
    single { UrlOpener() }
    single { PlatformShare() }
    single { PushService() }
    single { FilePicker() }
    single { AppleSignIn() }
    single { NotificationPermission() }
    single { CameraPermission() }
    single { ImageProcessor() }
    single { ReceiptFileStore() }
    single { ReceiptUploadScheduler() }
}
