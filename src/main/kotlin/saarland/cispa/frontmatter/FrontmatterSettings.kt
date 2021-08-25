package saarland.cispa.frontmatter

import java.nio.file.Path

class FrontmatterSettings(val androidPath: Path, val withTransitions: Boolean = false, val boomerangTimeout: Int = 30, val detectLang: Boolean = false, val analyseToasts: Boolean = false) {
    var filterFile: Path? = null
}
