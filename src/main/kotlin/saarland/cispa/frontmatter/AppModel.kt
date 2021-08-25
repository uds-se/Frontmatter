package saarland.cispa.frontmatter

import saarland.cispa.frontmatter.analyses.FrontmatterMetadata
import saarland.cispa.frontmatter.data.Dialog
import saarland.cispa.frontmatter.data.Menu
import saarland.cispa.frontmatter.data.UIViewElement
import saarland.cispa.frontmatter.resolvers.Transition
import saarland.cispa.frontmatter.resolvers.UIView
import saarland.cispa.frontmatter.results.ApiModel
import saarland.cispa.frontmatter.results.SimpleLayoutUIElement
import saarland.cispa.frontmatter.results.SimpleUIElement
import soot.SootClass
import soot.util.HashMultiMap
import soot.util.MultiMap

typealias ActivityClass = SootClass
typealias FragmentClass = SootClass
typealias ActivityOrFragmentClass = SootClass

data class FragmentWithContainer(val value: FragmentClass, val containerId: Int)


class AppModel(activityList: Collection<ActivityClass>, val meta: FrontmatterMetadata) {
    val activities: List<ActivityClass> = activityList.filter { it.isConcrete }
    val fragments: Set<FragmentClass>
        get() {
            return fragmentToActivity.keySet()
        }
    val activityLabels: MultiMap<ActivityClass, String> = HashMultiMap()
    fun setXmlLabels(activityLabels: Map<ActivityClass, String>) {
        this.activityLabels.putAll(activityLabels.entries)
    }

    val toasts: MultiMap<ActivityClass, String> = HashMultiMap()
    val transitions: MutableSet<Transition> = mutableSetOf()
    var activityLayouts: MultiMap<ActivityClass, UIView> = HashMultiMap()
    var fragmentLayouts: MultiMap<ActivityClass, UIView> = HashMultiMap()
    var fragmentMapping: MultiMap<ActivityClass, FragmentWithContainer> = HashMultiMap()
    val fragmentToActivity: MultiMap<FragmentClass, ActivityClass> = HashMultiMap()
    var orphanedFragmentLayouts: MultiMap<ActivityClass, UIViewElement> = HashMultiMap()

    var activityMenus: MultiMap<ActivityClass, Menu> = HashMultiMap()
    val activityDialogs: MultiMap<ActivityClass, Dialog> = HashMultiMap()
    val contextToScope: MultiMap<ActivityOrFragmentClass, String> = HashMultiMap()

    fun updateFragmentToActivity() {
        fragmentMapping.map { (k, v) -> v.value to k }.toMultiMap(fragmentToActivity)
    }

    fun getFlatUI(): List<SimpleUIElement> {
        val uiList = mutableListOf<SimpleUIElement>()
        activityLayouts.values().flatMap { it.uiViewElement.getAllChildrenFlatten() }.forEach { uiElement ->
            val listeners = uiElement.listeners.map { it.name }.toSet()
            uiList.add(SimpleLayoutUIElement(uiElement.guid, uiElement.id, listeners))
        }
        fragmentLayouts.values().flatMap { it.uiViewElement.getAllChildrenFlatten() }.forEach { uiElement ->
            val listeners = uiElement.listeners.map { it.name }.toSet()
            uiList.add(SimpleLayoutUIElement(uiElement.guid, uiElement.id, listeners))
        }
        activityDialogs.values().flatMap { it.buttons }.forEach { button ->
            val listeners = button.listener.map { it.name }.toSet()
            uiList.add(SimpleLayoutUIElement(button.guid, button.id, listeners))
        }
        for (menu in activityMenus.values()) {
            val listeners = menu.listener?.let { setOf(it.name) } ?: emptySet()
            for (item in menu.getAllItems()) {
                uiList.add(SimpleLayoutUIElement(item.guid, item.id, listeners))
            }
        }
        return uiList.toList()
    }
}
