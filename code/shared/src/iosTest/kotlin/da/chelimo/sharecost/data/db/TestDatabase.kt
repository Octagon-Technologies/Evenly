package da.chelimo.sharecost.data.db

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.Dispatchers

/**
 * Fresh in-memory [ShareCostDatabase] for tests. In-memory means each test gets an isolated DB that
 * vanishes on close — no file paths, no Context.
 *
 * Lives in `iosTest` (not `commonTest`) because the no-arg `inMemoryDatabaseBuilder<T>()` only
 * exists on non-Android targets — Android's overload needs a `Context`, which a plain JVM host test
 * doesn't have (that path needs Robolectric or an on-device instrumented test). The DAOs are the
 * same generated logic on every platform, so verifying them on the iOS target is sufficient.
 *
 * Query context is [Dispatchers.Default] so Room's suspend queries run on a real background
 * dispatcher under `runTest`.
 */
fun inMemoryTestDatabase(): ShareCostDatabase =
    Room.inMemoryDatabaseBuilder<ShareCostDatabase>()
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.Default)
        .build()
