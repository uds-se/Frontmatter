package saarland.cispa.frontmatter.cg

import soot.tagkit.Tag

class ArtificialTag(val isIgnored: Boolean = false) : Tag {
    override fun getName(): String = "artificialTag"

    override fun getValue(): ByteArray = throw RuntimeException("ArtificialTag has no value for bytecode")
}
