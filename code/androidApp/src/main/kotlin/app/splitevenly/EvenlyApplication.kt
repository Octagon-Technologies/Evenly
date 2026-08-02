package app.splitevenly

import android.app.Application
import app.splitevenly.di.initKoinAndroid
import app.splitevenly.platform.installActivityTracking
import app.splitevenly.platform.setupAnalytics
import io.github.samuolis.posthog.PostHogContext

class EvenlyApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        // PostHog must be set up before Koin so PostHogAnalytics (bound in platformModule) can call
        // PostHog.* immediately after Koin finishes starting.
        setupAnalytics(PostHogContext(this), debug = BuildConfig.DEBUG)

        initKoinAndroid(this)
        // Track the foreground Activity so FilePicker (06 §5.1) can launch ActivityResult contracts.
        installActivityTracking(this)
    }
}
