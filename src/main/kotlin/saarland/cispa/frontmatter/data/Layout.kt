package saarland.cispa.frontmatter.data

import soot.SootClass

class Layout(val name: String) {
    val content: MutableSet<SootClass> = mutableSetOf()
}
