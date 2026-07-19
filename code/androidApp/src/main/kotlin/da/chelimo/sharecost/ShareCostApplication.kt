package da.chelimo.sharecost

import android.app.Application
import da.chelimo.sharecost.di.initKoinAndroid
import da.chelimo.sharecost.platform.installActivityTracking
import da.chelimo.sharecost.platform.setupAnalytics
import io.github.samuolis.posthog.PostHogContext

class ShareCostApplication : Application() {
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
