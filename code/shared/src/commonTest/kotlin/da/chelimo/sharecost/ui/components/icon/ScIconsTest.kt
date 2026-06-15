package da.chelimo.sharecost.ui.components.icon

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Forces every icon's lazy [androidx.compose.ui.graphics.vector.ImageVector] to build, which runs
 * `PathParser` over its (converted) path data — so a malformed path fails here rather than at first
 * render in a screen.
 */
class ScIconsTest {

    @Test
    fun every_icon_parses_and_builds() {
        val all = ScIcons.byName
        assertEquals(66, all.size, "icon count drifted from the design set")
        all.forEach { (name, vector) ->
            assertTrue(vector.defaultWidth.value > 0f, "icon '$name' did not build")
        }
    }
}
