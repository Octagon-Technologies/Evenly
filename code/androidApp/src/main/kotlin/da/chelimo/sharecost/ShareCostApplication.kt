package da.chelimo.sharecost

import android.app.Application
import da.chelimo.sharecost.di.initKoin

class ShareCostApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        initKoin()
    }
}
