package da.chelimo.sharecost.platform

import coil3.PlatformContext
import okio.Path

/**
 * Directory for Coil's on-disk image cache. Android → app `cacheDir`, iOS → the Caches directory; both are
 * OS-managed scratch space that survives process death (so a receipt viewed once renders from disk with no
 * second network fetch) but can be reclaimed under storage pressure. See [da.chelimo.sharecost.App].
 */
expect fun imageCacheDir(context: PlatformContext): Path
