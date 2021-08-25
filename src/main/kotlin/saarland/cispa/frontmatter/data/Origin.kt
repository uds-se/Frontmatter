package saarland.cispa.frontmatter.data

import soot.SootClass

sealed class Origin(open val value: SootClass)
data class ActivityOrigin(override val value: SootClass, val label: String = "") : Origin(value)
data class Fragment(override val value: SootClass) : Origin(value)

/**
 * a fragment has an ID
    // The optional identifier for this fragment -- either the container ID if it
    // was dynamically added to the view hierarchy, or the ID supplied in
    // layout.
    int mFragmentId;

    // When a fragment is being dynamically added to the view hierarchy, this
    // is the identifier of the parent container it is being added to.
    int mContainerId;

    // The optional named tag for this fragment -- usually used to find
    // fragments that are not part of the layout.
    String mTag;

 * */
