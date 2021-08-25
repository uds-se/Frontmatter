package saarland.cispa.frontmatter.results

import mu.KLogging
import saarland.cispa.frontmatter.data.UIViewElement
import soot.SootClass
import soot.jimple.infoflow.android.resources.controls.AndroidLayoutControl
import soot.util.MultiMap

class Results(indexedLayouts: MultiMap<Int, UIViewElement>, val layoutMapping: MultiMap<SootClass, Int>, val activityTransitions: MultiMap<SootClass, SootClass>?) {
    companion object : KLogging()

    val activityContent: Map<String, List<UIViewElement>>

    init {
        activityContent = mutableMapOf()
        for (activity in layoutMapping.keySet()) {
            val activityName = activity.name
            val layouts = layoutMapping.get(activity)
            val uiControls = layouts.flatMap { indexedLayouts.get(it) }
            activityContent[activityName] = uiControls
        }
    }

}
