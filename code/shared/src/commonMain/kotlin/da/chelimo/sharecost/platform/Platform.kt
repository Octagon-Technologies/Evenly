package da.chelimo.sharecost.platform

/** Minimal platform probe + the home for expect/actual surfaces (06 §5). */
interface Platform {
    val name: String
}

expect fun getPlatform(): Platform
