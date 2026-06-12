package da.chelimo.sharecost

import android.app.Application
import da.chelimo.sharecost.di.initKoinAndroid
import da.chelimo.sharecost.platform.installActivityTracking

class ShareCostApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        initKoinAndroid(this)
        // Track the foreground Activity so FilePicker (06 §5.1) can launch ActivityResult contracts.
        installActivityTracking(this)
    }
}
