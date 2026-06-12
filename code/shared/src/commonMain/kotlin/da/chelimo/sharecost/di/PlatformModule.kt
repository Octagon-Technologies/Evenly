package da.chelimo.sharecost.di

import org.koin.core.module.Module

/**
 * Platform-supplied singletons that need a platform constructor: the [da.chelimo.sharecost.data.db.ShareCostDatabase]
 * (Android needs a `Context`; iOS resolves a sandbox path) and the Ktor `HttpClient` engine
 * (OkHttp on Android, Darwin on iOS).
 */
expect fun platformModule(): Module
