package saarland.cispa.frontmatter.analyses

enum class AppPlatform {
    NORMAL,
    NATIVE,
    UNITY,
    XAMARIN,
    MONO,
    AIR,
    CORDOVA;

    val isAnalysable: Boolean
        get() = this == NORMAL
}
