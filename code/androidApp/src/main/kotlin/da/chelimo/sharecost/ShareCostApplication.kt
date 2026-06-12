package da.chelimo.sharecost

import android.app.Application
import da.chelimo.sharecost.di.initKoinAndroid

class ShareCostApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        initKoinAndroid(this)
    }
}
