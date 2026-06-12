package da.chelimo.sharecost.di

import android.content.Context
import org.koin.android.ext.koin.androidContext

/**
 * Android entry point for DI start. Binds the application [Context] into Koin so [platformModule] can
 * build the Room database. Keeps `koin-android` out of the `:androidApp` module — the app just calls
 * this from its `Application.onCreate`.
 */
fun initKoinAndroid(context: Context) = initKoin {
    androidContext(context)
}
