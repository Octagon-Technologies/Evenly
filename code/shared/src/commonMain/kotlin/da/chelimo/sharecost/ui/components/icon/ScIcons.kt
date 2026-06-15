package da.chelimo.sharecost.ui.components.icon

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * The ShareCost line-icon set — ported 1:1 from `design/src/icons.jsx` (24x24, single-weight,
 * monochrome via `currentColor`). GENERATED; do not hand-edit. The built-in black is overridden by
 * the tint applied in [ScIcon] (defaults to `LocalContentColor`), preserving the web's currentColor
 * behavior. `<circle>`/`<rect>` primitives were converted to path commands.
 */
private fun lineIcon(pathData: String): ImageVector =
    ImageVector.Builder(defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).run {
        addPath(
            pathData = PathParser().parsePathString(pathData).toNodes(),
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.6f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        )
        build()
    }

private fun fillIcon(pathData: String): ImageVector =
    ImageVector.Builder(defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).run {
        addPath(pathData = PathParser().parsePathString(pathData).toNodes(), fill = SolidColor(Color.Black))
        build()
    }

object ScIcons {
    val Plus: ImageVector by lazy { lineIcon("M12 5v14M5 12h14") }
    val Minus: ImageVector by lazy { lineIcon("M5 12h14") }
    val Search: ImageVector by lazy { lineIcon("M4 11a7 7 0 1 0 14 0a7 7 0 1 0 -14 0Z M21 21l-4-4") }
    val Filter: ImageVector by lazy { lineIcon("M3 5h18M6 12h12M10 19h4") }
    val Sort: ImageVector by lazy { lineIcon("M7 4v16M7 20l-3-3M7 4l3 3M17 20V4M17 4l3 3M17 20l-3-3") }
    val ChevR: ImageVector by lazy { lineIcon("M9 6l6 6-6 6") }
    val ChevL: ImageVector by lazy { lineIcon("M15 6l-6 6 6 6") }
    val ChevD: ImageVector by lazy { lineIcon("M6 9l6 6 6-6") }
    val ChevU: ImageVector by lazy { lineIcon("M6 15l6-6 6 6") }
    val Back: ImageVector by lazy { lineIcon("M19 12H5M5 12l6-6M5 12l6 6") }
    val Close: ImageVector by lazy { lineIcon("M6 6l12 12M18 6L6 18") }
    val More: ImageVector by lazy { lineIcon("M3.6 12a1.4 1.4 0 1 0 2.8 0a1.4 1.4 0 1 0 -2.8 0Z M10.6 12a1.4 1.4 0 1 0 2.8 0a1.4 1.4 0 1 0 -2.8 0Z M17.6 12a1.4 1.4 0 1 0 2.8 0a1.4 1.4 0 1 0 -2.8 0Z") }
    val Check: ImageVector by lazy { lineIcon("M5 12l5 5L20 6") }
    val CheckCircle: ImageVector by lazy { lineIcon("M3 12a9 9 0 1 0 18 0a9 9 0 1 0 -18 0Z M8 12l3 3 5-6") }
    val User: ImageVector by lazy { lineIcon("M8.5 8a3.5 3.5 0 1 0 7 0a3.5 3.5 0 1 0 -7 0Z M5 20c0-3.9 3.1-6 7-6s7 2.1 7 6") }
    val Users: ImageVector by lazy { lineIcon("M6 8a3 3 0 1 0 6 0a3 3 0 1 0 -6 0Z M3 19c0-3.3 2.7-5 6-5s6 1.7 6 5 M16 5.5a3 3 0 010 5.8M21 19c0-2.6-1.4-4.2-3.5-4.8") }
    val Receipt: ImageVector by lazy { lineIcon("M6 3h12v18l-2.5-1.5L13 21l-2.5-1.5L8 21l-2-1.5V3z M9 8h6M9 12h6") }
    val Calendar: ImageVector by lazy { lineIcon("M6.5 5h11a2.5 2.5 0 0 1 2.5 2.5v11a2.5 2.5 0 0 1 -2.5 2.5h-11a2.5 2.5 0 0 1 -2.5 -2.5v-11a2.5 2.5 0 0 1 2.5 -2.5Z M4 9h16M8 3v4M16 3v4") }
    val Tag: ImageVector by lazy { lineIcon("M3 12l8-8 9 1 1 9-8 8-10-10z M14.1 8.5a1.4 1.4 0 1 0 2.8 0a1.4 1.4 0 1 0 -2.8 0Z") }
    val Food: ImageVector by lazy { lineIcon("M6 3v8a2 2 0 002 2v8M6 3v5M9 3v5M8 8h1M16 3c-1.5 0-2.5 2-2.5 5s1 3 2.5 3v9") }
    val Car: ImageVector by lazy { lineIcon("M5 13l1.5-4.5A2 2 0 018.4 7h7.2a2 2 0 011.9 1.5L19 13M4 13h16v4H4zM7 17v2M17 17v2 M6.9 14.5a0.6 0.6 0 1 0 1.2 0a0.6 0.6 0 1 0 -1.2 0Z M15.9 14.5a0.6 0.6 0 1 0 1.2 0a0.6 0.6 0 1 0 -1.2 0Z") }
    val Home: ImageVector by lazy { lineIcon("M4 11l8-7 8 7M6 9.5V20h12V9.5") }
    val Cart: ImageVector by lazy { lineIcon("M7.7 20a1.3 1.3 0 1 0 2.6 0a1.3 1.3 0 1 0 -2.6 0Z M15.7 20a1.3 1.3 0 1 0 2.6 0a1.3 1.3 0 1 0 -2.6 0Z M3 4h2l2.2 11h10l1.8-8H6") }
    val Ticket: ImageVector by lazy { lineIcon("M4 8a2 2 0 012-2h12a2 2 0 012 2 2 2 0 000 4 2 2 0 000 4 2 2 0 01-2 2H6a2 2 0 01-2-2 2 2 0 000-4 2 2 0 000-4z M14 6v12") }
    val Bolt: ImageVector by lazy { lineIcon("M13 3L5 13h6l-1 8 8-10h-6l1-8z") }
    val Bed: ImageVector by lazy { lineIcon("M3 18v-7a2 2 0 012-2h14a2 2 0 012 2v7M3 14h18M7 9V7a1 1 0 011-1h3v3") }
    val Gift: ImageVector by lazy { lineIcon("M5.5 9h13a1.5 1.5 0 0 1 1.5 1.5v8a1.5 1.5 0 0 1 -1.5 1.5h-13a1.5 1.5 0 0 1 -1.5 -1.5v-8a1.5 1.5 0 0 1 1.5 -1.5Z M4 13h16M12 9v11M12 9c-2-4-6-3-6-1s4 1 6 1c2 0 6 1 6-1s-4-3-6 1z") }
    val Gear: ImageVector by lazy { lineIcon("M9 12a3 3 0 1 0 6 0a3 3 0 1 0 -6 0Z M12 2v3M12 19v3M5 5l2 2M17 17l2 2M2 12h3M19 12h3M5 19l2-2M17 7l2-2") }
    val Bell: ImageVector by lazy { lineIcon("M6 9a6 6 0 0112 0c0 5 2 6 2 6H4s2-1 2-6zM10 20a2 2 0 004 0") }
    val Lock: ImageVector by lazy { lineIcon("M7 10h10a2 2 0 0 1 2 2v6a2 2 0 0 1 -2 2h-10a2 2 0 0 1 -2 -2v-6a2 2 0 0 1 2 -2Z M8 10V7a4 4 0 018 0v3") }
    val Share: ImageVector by lazy { lineIcon("M3.5 12a2.5 2.5 0 1 0 5 0a2.5 2.5 0 1 0 -5 0Z M14.5 6a2.5 2.5 0 1 0 5 0a2.5 2.5 0 1 0 -5 0Z M14.5 18a2.5 2.5 0 1 0 5 0a2.5 2.5 0 1 0 -5 0Z M8.2 10.8l6.6-3.6M8.2 13.2l6.6 3.6") }
    val Qr: ImageVector by lazy { lineIcon("M5 4h4a1 1 0 0 1 1 1v4a1 1 0 0 1 -1 1h-4a1 1 0 0 1 -1 -1v-4a1 1 0 0 1 1 -1Z M15 4h4a1 1 0 0 1 1 1v4a1 1 0 0 1 -1 1h-4a1 1 0 0 1 -1 -1v-4a1 1 0 0 1 1 -1Z M5 14h4a1 1 0 0 1 1 1v4a1 1 0 0 1 -1 1h-4a1 1 0 0 1 -1 -1v-4a1 1 0 0 1 1 -1Z M14 14h3v3M20 14v6M17 20h3") }
    val Link: ImageVector by lazy { lineIcon("M9 15l6-6M10.5 6.5l1.8-1.8a4 4 0 015.6 5.6L15.5 12M13.5 17.5l-1.8 1.8a4 4 0 01-5.6-5.6L8.5 12") }
    val Download: ImageVector by lazy { lineIcon("M12 4v11M12 15l-4-4M12 15l4-4M5 19h14") }
    val Upload: ImageVector by lazy { lineIcon("M12 20V9M12 9L8 13M12 9l4 4M5 5h14") }
    val Archive: ImageVector by lazy { lineIcon("M5 5h14a1 1 0 0 1 1 1v2a1 1 0 0 1 -1 1h-14a1 1 0 0 1 -1 -1v-2a1 1 0 0 1 1 -1Z M5 9v9a2 2 0 002 2h10a2 2 0 002-2V9M10 13h4") }
    val Trash: ImageVector by lazy { lineIcon("M5 7h14M9 7V5a1 1 0 011-1h4a1 1 0 011 1v2M7 7l1 13h8l1-13") }
    val Edit: ImageVector by lazy { lineIcon("M4 20h4L19 9l-4-4L4 16v4z M14 6l4 4") }
    val Refund: ImageVector by lazy { lineIcon("M4 9h11a5 5 0 010 10h-3M4 9l4-4M4 9l4 4") }
    val Copy: ImageVector by lazy { lineIcon("M11 9h7a2 2 0 0 1 2 2v7a2 2 0 0 1 -2 2h-7a2 2 0 0 1 -2 -2v-7a2 2 0 0 1 2 -2Z M5 15V5a2 2 0 012-2h8") }
    val Camera: ImageVector by lazy { lineIcon("M5.5 7h13a2.5 2.5 0 0 1 2.5 2.5v8a2.5 2.5 0 0 1 -2.5 2.5h-13a2.5 2.5 0 0 1 -2.5 -2.5v-8a2.5 2.5 0 0 1 2.5 -2.5Z M8.5 13.5a3.5 3.5 0 1 0 7 0a3.5 3.5 0 1 0 -7 0Z M8 7l1.5-3h5L16 7") }
    val Image: ImageVector by lazy { lineIcon("M5.5 5h13a2.5 2.5 0 0 1 2.5 2.5v9a2.5 2.5 0 0 1 -2.5 2.5h-13a2.5 2.5 0 0 1 -2.5 -2.5v-9a2.5 2.5 0 0 1 2.5 -2.5Z M6.7 10a1.8 1.8 0 1 0 3.6 0a1.8 1.8 0 1 0 -3.6 0Z M21 16l-5-5L5 19") }
    val Comment: ImageVector by lazy { lineIcon("M4 6a2 2 0 012-2h12a2 2 0 012 2v8a2 2 0 01-2 2H9l-5 4V6z") }
    val Clock: ImageVector by lazy { lineIcon("M3.5 12a8.5 8.5 0 1 0 17 0a8.5 8.5 0 1 0 -17 0Z M12 7.5V12l3 2") }
    val History: ImageVector by lazy { lineIcon("M4 12a8 8 0 108-8 8 8 0 00-7 4M4 4v4h4 M12 8v4l3 2") }
    val WifiOff: ImageVector by lazy { lineIcon("M3 3l18 18M8.5 13.5a6 6 0 017 0M5 10a11 11 0 0114 0M12 18h.01") }
    val Alert: ImageVector by lazy { lineIcon("M12 3l9 16H3l9-16zM12 10v4M12 17h.01") }
    val Info: ImageVector by lazy { lineIcon("M3 12a9 9 0 1 0 18 0a9 9 0 1 0 -18 0Z M12 11v5M12 8h.01") }
    val Wallet: ImageVector by lazy { lineIcon("M5.5 6h13a2.5 2.5 0 0 1 2.5 2.5v8a2.5 2.5 0 0 1 -2.5 2.5h-13a2.5 2.5 0 0 1 -2.5 -2.5v-8a2.5 2.5 0 0 1 2.5 -2.5Z M3 10h18M16 14h2") }
    val Percent: ImageVector by lazy { lineIcon("M5 7.5a2.5 2.5 0 1 0 5 0a2.5 2.5 0 1 0 -5 0Z M14 16.5a2.5 2.5 0 1 0 5 0a2.5 2.5 0 1 0 -5 0Z M6 18L18 6") }
    val Calculator: ImageVector by lazy { lineIcon("M7.5 3h9a2.5 2.5 0 0 1 2.5 2.5v13a2.5 2.5 0 0 1 -2.5 2.5h-9a2.5 2.5 0 0 1 -2.5 -2.5v-13a2.5 2.5 0 0 1 2.5 -2.5Z M8 7h8M8 11h.01M12 11h.01M16 11h.01M8 15h.01M12 15h.01M16 15v3M8 18h4") }
    val Sparkle: ImageVector by lazy { lineIcon("M12 3l1.8 5.2L19 10l-5.2 1.8L12 17l-1.8-5.2L5 10l5.2-1.8L12 3z") }
    val Swap: ImageVector by lazy { lineIcon("M7 7h11M18 7l-3-3M18 7l-3 3M17 17H6M6 17l3-3M6 17l3 3") }
    val Split: ImageVector by lazy { lineIcon("M12 4v6M12 10l-5 5M12 10l5 5M7 15v5M17 15v5") }
    val Globe: ImageVector by lazy { lineIcon("M3.5 12a8.5 8.5 0 1 0 17 0a8.5 8.5 0 1 0 -17 0Z M3.5 12h17M12 3.5c2.5 2.4 2.5 14.6 0 17M12 3.5c-2.5 2.4-2.5 14.6 0 17") }
    val Chart: ImageVector by lazy { lineIcon("M4 20V4M4 20h16M8 16v-4M12 16V8M16 16v-7") }
    val Flag: ImageVector by lazy { lineIcon("M6 21V4M6 4h11l-2 4 2 4H6") }
    val Mail: ImageVector by lazy { lineIcon("M5.5 5h13a2.5 2.5 0 0 1 2.5 2.5v9a2.5 2.5 0 0 1 -2.5 2.5h-13a2.5 2.5 0 0 1 -2.5 -2.5v-9a2.5 2.5 0 0 1 2.5 -2.5Z M4 7l8 6 8-6") }
    val Apple: ImageVector by lazy { lineIcon("M16 12c0-2 1.5-3 1.6-3.1A3.7 3.7 0 0014.5 7c-1.3 0-2 .7-2.5.7S10.7 7 9.5 7C7.4 7 6 8.8 6 11.4c0 3 2.2 6.6 3.8 6.6.8 0 1.3-.6 2.2-.6s1.3.6 2.2.6c1.3 0 2.8-2.4 3.3-3.6-1.4-.6-1.5-2.2-1.5-2.4zM13 5.5c.6-.8.5-1.9.5-2-.9 0-1.7.6-2 1-.5.5-.6 1.4-.5 1.9.9.1 1.6-.4 2-.9z") }
    val Google: ImageVector by lazy { fillIcon("M21 12.2c0-.7-.1-1.4-.2-2H12v3.8h5.1a4.4 4.4 0 01-1.9 2.9v2.4h3.1c1.8-1.7 2.7-4.2 2.7-7.1z M12 21c2.4 0 4.5-.8 6-2.2l-3.1-2.4c-.8.6-1.9 1-2.9 1a5 5 0 01-4.8-3.5H4v2.4A9 9 0 0012 21z M7.2 13.9a5.4 5.4 0 010-3.4V8.1H4a9 9 0 000 8.1l3.2-2.3z M12 6.5c1.3 0 2.5.5 3.5 1.4l2.6-2.6A9 9 0 004 8.1l3.2 2.4A5 5 0 0112 6.5z") }
    val Facebook: ImageVector by lazy { fillIcon("M14 8h2V5h-2a3 3 0 00-3 3v2H9v3h2v6h3v-6h2.2l.8-3H14V8.5c0-.3.2-.5.5-.5z") }
    val Reload: ImageVector by lazy { lineIcon("M20 12a8 8 0 11-2.3-5.6M20 4v4h-4") }
    val Pin: ImageVector by lazy { lineIcon("M12 21s7-6 7-11a7 7 0 10-14 0c0 5 7 11 7 11z M9.5 10a2.5 2.5 0 1 0 5 0a2.5 2.5 0 1 0 -5 0Z") }
    val Star: ImageVector by lazy { lineIcon("M12 3l2.6 5.6 6 .8-4.4 4.2 1.1 6L12 17l-5.3 2.6 1.1-6L3.4 9.4l6-.8L12 3z") }
    val EyeOff: ImageVector by lazy { lineIcon("M3 3l18 18M10.6 10.6a2 2 0 002.8 2.8M6.7 6.8A10.5 10.5 0 002 12s3.5 6 10 6a9.8 9.8 0 004.6-1.1M9.5 5.2A10.6 10.6 0 0112 5c6.5 0 10 7 10 7a18 18 0 01-2.2 3") }
    val Send: ImageVector by lazy { lineIcon("M4 12l16-7-7 16-2.5-6.5L4 12z") }

    /** Lookup by the design's string name (used by the category→icon map). */
    val byName: Map<String, ImageVector> by lazy {
        mapOf(
            "plus" to Plus,
            "minus" to Minus,
            "search" to Search,
            "filter" to Filter,
            "sort" to Sort,
            "chevR" to ChevR,
            "chevL" to ChevL,
            "chevD" to ChevD,
            "chevU" to ChevU,
            "back" to Back,
            "close" to Close,
            "more" to More,
            "check" to Check,
            "checkCircle" to CheckCircle,
            "user" to User,
            "users" to Users,
            "receipt" to Receipt,
            "calendar" to Calendar,
            "tag" to Tag,
            "food" to Food,
            "car" to Car,
            "home" to Home,
            "cart" to Cart,
            "ticket" to Ticket,
            "bolt" to Bolt,
            "bed" to Bed,
            "gift" to Gift,
            "gear" to Gear,
            "bell" to Bell,
            "lock" to Lock,
            "share" to Share,
            "qr" to Qr,
            "link" to Link,
            "download" to Download,
            "upload" to Upload,
            "archive" to Archive,
            "trash" to Trash,
            "edit" to Edit,
            "refund" to Refund,
            "copy" to Copy,
            "camera" to Camera,
            "image" to Image,
            "comment" to Comment,
            "clock" to Clock,
            "history" to History,
            "wifiOff" to WifiOff,
            "alert" to Alert,
            "info" to Info,
            "wallet" to Wallet,
            "percent" to Percent,
            "calculator" to Calculator,
            "sparkle" to Sparkle,
            "swap" to Swap,
            "split" to Split,
            "globe" to Globe,
            "chart" to Chart,
            "flag" to Flag,
            "mail" to Mail,
            "apple" to Apple,
            "google" to Google,
            "facebook" to Facebook,
            "reload" to Reload,
            "pin" to Pin,
            "star" to Star,
            "eyeOff" to EyeOff,
            "send" to Send,
        )
    }
}
