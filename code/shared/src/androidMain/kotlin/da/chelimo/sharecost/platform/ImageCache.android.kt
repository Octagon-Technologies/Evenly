package da.chelimo.sharecost.platform

import coil3.PlatformContext
import okio.Path
import okio.Path.Companion.toPath
import java.io.File

actual fun imageCacheDir(context: PlatformContext): Path =
    File(context.cacheDir, "image_cache").absolutePath.toPath()
