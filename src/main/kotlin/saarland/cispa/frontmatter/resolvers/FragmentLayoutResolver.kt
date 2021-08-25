package saarland.cispa.frontmatter.resolvers

import mu.KLogging
import saarland.cispa.frontmatter.ActivityClass
import saarland.cispa.frontmatter.AppModel
import saarland.cispa.frontmatter.FragmentClass
import saarland.cispa.frontmatter.FragmentWithContainer
import saarland.cispa.frontmatter.Resolver
import saarland.cispa.frontmatter.ResourceParser
import saarland.cispa.frontmatter.Utils.isAndroidClass
import saarland.cispa.frontmatter.Utils.isAndroidMethod
import saarland.cispa.frontmatter.Utils.isDummy
import saarland.cispa.frontmatter.Utils.isFragment
import saarland.cispa.frontmatter.Utils.isSystemMethod
import saarland.cispa.frontmatter.XmlLayoutAnalysis
import saarland.cispa.frontmatter.cg.InterproceduralCFGWrapper
import saarland.cispa.frontmatter.component1
import saarland.cispa.frontmatter.component2
import saarland.cispa.frontmatter.data.FragmentContainer
import saarland.cispa.frontmatter.data.FragmentType
import saarland.cispa.frontmatter.data.StmtSite
import saarland.cispa.frontmatter.data.UIViewGroupElement
import saarland.cispa.frontmatter.defaultContainerId
import saarland.cispa.frontmatter.dynamicFragmentId
import saarland.cispa.frontmatter.getViewFragmentMethodSubsignature
import saarland.cispa.frontmatter.onCreateViewSubsignature
import saarland.cispa.frontmatter.onViewCreatedSubsignature
import saarland.cispa.frontmatter.xmlFragmentContainer
import soot.Scene
import soot.SootClass
import soot.SootMethod
import soot.jimple.AssignStmt
import soot.jimple.Constant
import soot.jimple.IdentityStmt
import soot.jimple.ParameterRef
import soot.jimple.ReturnStmt
import soot.jimple.Stmt
import soot.toolkits.graph.ExceptionalUnitGraph
import soot.util.HashMultiMap
import soot.util.MultiMap

class FragmentLayoutResolver(sceneV: Scene, val icfg: InterproceduralCFGWrapper,
                             private val valueResolver: ValueResolver,
                             private val appModel: AppModel, private val resourceParser: ResourceParser, private val layoutAnalysis: XmlLayoutAnalysis) : Resolver(sceneV) {
    companion object : KLogging()

    fun resolve() {
        val fragmentLayouts = resolveLayouts()
        appModel.fragmentLayouts = fragmentLayouts
        bindStaticFragmentLayouts(fragmentLayouts)
        bindDynamicFragmentLayouts(fragmentLayouts)
    }

    private fun bindDynamicFragmentLayouts(fragmentLayouts: MultiMap<ActivityClass, UIView>) {
        for ((activity, fragment) in appModel.fragmentMapping) {
            if (fragment.containerId == xmlFragmentContainer) continue // skip static fragments
            val activityLayouts = appModel.activityLayouts[activity]
            val fragmentLayout = fragmentLayouts[fragment.value] ?: continue
            val connected = attachToActivityLayouts(activityLayouts, fragment, fragmentLayout)
            if (!connected) {
                logger.warn("Missing container for fragment ${fragment.value} in activity $activity")
                val fragmentContainer = FragmentContainer(dynamicFragmentId, fragment.value, FragmentType.DYNAMIC)
                fragmentLayout.forEach { fl -> fragmentContainer.addChild(fl.uiViewElement.copy()) }

                appModel.orphanedFragmentLayouts.put(activity, fragmentContainer)
            }
        }
    }

    private fun attachToActivityLayouts(activityLayouts: MutableSet<UIView>, fragment: FragmentWithContainer, fragmentLayout: Set<UIView>): Boolean {
        var connected = false
        val containerId = fragment.containerId
        if (containerId == -1 || containerId == xmlFragmentContainer) return false
        for (activityLayout in activityLayouts) {
            val containerView = activityLayout.uiViewElement.getChild(containerId) ?: continue //
//            if (containerViews.isEmpty()) continue
//            for (containerView in containerViews) {
            connected = true
            require(containerView is UIViewGroupElement)
            if (containerView.children.any { it is FragmentContainer && it.viewClass == fragment.value && it.type == FragmentType.XML }) continue
            val fragmentContainer = FragmentContainer(dynamicFragmentId, fragment.value, FragmentType.DYNAMIC)
            fragmentLayout.forEach { fl -> fragmentContainer.addChild(fl.uiViewElement.copy()) }
            containerView.addChild(fragmentContainer)
//            }
        }
        return connected
    }

    private fun bindStaticFragmentLayouts(fragmentLayouts: MultiMap<ActivityClass, UIView>) {
        for (activityLayout in appModel.activityLayouts.values()) {
            val fragmentContainers = activityLayout.uiViewElement.getAllChildrenFlatten().filterIsInstance<FragmentContainer>().filter { it.type == FragmentType.XML }
            for (fragmentContainer in fragmentContainers) {
                val fragmentClass = fragmentContainer.viewClass
                val fragmentLayout = fragmentLayouts[fragmentClass]
                fragmentLayout.filterIsInstance<UIView>().forEach { fragmentContainer.addChild(it.uiViewElement) }
            }
        }
    }

    private fun getFragmentScope(fragment: FragmentClass): Set<String> {
        return if (appModel.contextToScope.containsKey(fragment)) appModel.contextToScope[fragment]
        else {
            val scope = getContextFor(fragment)
            appModel.contextToScope.putAll(fragment, scope)
            scope
        }
    }

    /**
     * a layout must be explicitly returned from onCreateView (may have default impl)
     * next it can be updated in onViewCreated
     *
     */
    private fun resolveLayouts(): MultiMap<FragmentClass, UIView> {
        val res = HashMultiMap<FragmentClass, UIView>()
        val menuResolver = MenuResolver(sceneV, appModel, valueResolver, layoutAnalysis)
        val dialogResolver = DialogResolver(sceneV, appModel, valueResolver, resourceParser, layoutAnalysis)
        val findViewResolver = FindViewResolver(sceneV, appModel, valueResolver, resourceParser, layoutAnalysis)
        val adapterResolver = AdapterResolver(sceneV, appModel, valueResolver, resourceParser, layoutAnalysis)
        for (fragment in appModel.fragments) {
            if (fragment.isAndroidClass()) continue
            logger.info("> Resolving fragment layout $fragment")
            val viewResolver = ViewResolver(fragment, appModel, sceneV, valueResolver, resourceParser, layoutAnalysis)
            val layoutsFromCreateView = resolveCreateView(fragment, viewResolver).toMutableSet()
            logger.info("=> Layouts found ${layoutsFromCreateView.size}")
            if (layoutsFromCreateView.isEmpty()) { // may be default implementation like for ListFragment, provide mock container, OR a fragment without a layout which is also possible
                val mockView = getDefaultLayout()
                layoutsFromCreateView.add(mockView)
            }
            val reachableMethods = getReachableMethodsOfActivity(fragment)
                .filter { it.isConcrete }
                .filterNot { it.isSystemMethod() }
                .filterNot { it.isAndroidMethod() }
                .filterNot { it.isDummy() }
            logger.info("=> Resolving adapters for $fragment")
            adapterResolver.resolveAdaptersFrom(fragment, reachableMethods)

            resolveViewCreated(fragment, layoutsFromCreateView, viewResolver) //FIXME: must resolve listViews before proceeding
            resolveFromGetView(fragment, layoutsFromCreateView, viewResolver) // getView() can be used as well to get the layout root

            logger.info("=> Resolving findViews for $fragment")
            findViewResolver.resolveViewsFrom(fragment, reachableMethods)
            //TODO:
//            ActivityLayoutResolver.logger.info("=> Resolving menus for $fragment")
//            menuResolver.resolveMenusFrom(fragment)
//            ActivityLayoutResolver.logger.info("=> Resolving dialogs for $fragment")
//            dialogResolver.resolveDialogsFrom(fragment)
            res.putAll(fragment, layoutsFromCreateView)
        }
        return res
    }

    /**
     * like in LayoutMapper
     */
    private fun getDefaultLayout(): UIView {
        val content = UIViewGroupElement(defaultContainerId, sceneV.getSootClass("android.view.ViewGroup"))
        return UIView(content, null)
    }

    private fun resolveCreateView(fragment: SootClass, viewResolver: ViewResolver): Set<UIView> {
        val onCreateView = getTargetMethodBySubsignature(fragment, onCreateViewSubsignature) ?: return emptySet()
        if (!onCreateView.hasActiveBody()) return emptySet()
        logger.info("Resolving onCreateView for $fragment")
        val unitGraph = icfg.getOrCreateUnitGraph(onCreateView) as ExceptionalUnitGraph
        val returnViews = unitGraph.tails.filterIsInstance<ReturnStmt>().filterNot { it is Constant } // constant can be only null
        val scope = getFragmentScope(fragment)
        val layouts = returnViews.flatMap { resolveLayoutFromReturn(StmtSite(it, onCreateView), viewResolver, scope) }.toSet()
        appModel.fragmentLayouts.putAll(fragment, layouts)
        layouts.onEach { viewResolver.bindViewAttrs(it, it.location!!, onCreateView) }
        return layouts
    }

    /**
     * like in [saarland.cispa.frontmatter.ActivityLayoutResolver.resolveLayoutView]
     */
    private fun resolveLayoutFromReturn(stmtSite: StmtSite, viewResolver: ViewResolver, scope: Set<String>): List<UIView> {
        val viewAllocationSites = valueResolver.resolveVar((stmtSite.stmt as ReturnStmt).op, stmtSite, scope)
        val views = viewAllocationSites
            .filter { it.stmt is AssignStmt }
            .mapNotNull { viewResolver.initLayout(it) }
            .filterIsInstance<UIView>()
        if (views.isEmpty()) logger.warn("Could not resolve fragment View @ $stmtSite")
        return views
    }

    /**
     * TODO: resolve view editing in onViewCreated method
     * use either param value of getView value
     * */
    private fun resolveViewCreated(fragment: SootClass, layouts: Set<View>, viewResolver: ViewResolver) {
        val onViewCreated = getTargetMethodBySubsignature(fragment, onViewCreatedSubsignature) ?: return
        if (!onViewCreated.hasActiveBody()) return
        logger.info("Resolving onViewCreated for $fragment")
        // search from View parameter of fragment.getView() stmt
        val paramIdx = 0
        val paramStmt = onViewCreated.activeBody.units.asSequence().filterIsInstance<IdentityStmt>().first { (it.rightOp as? ParameterRef)?.index == paramIdx }
        val paramStmtSite = StmtSite(paramStmt, onViewCreated)
        for (layout in layouts) {
            viewResolver.bindViewAttrs(layout as UIView, paramStmtSite, onViewCreated) //TODO:test it
        }
    }

    private fun resolveFromGetView(fragment: SootClass, layouts: Set<View>,
                                   viewResolver: ViewResolver) {
        val appClasses = sceneV.applicationClasses
        val reachableMethods = getReachableMethodsOfActivity(fragment).map { it.method() }
            .filter { it.declaringClass in appClasses }
            .filter { it.isConcrete }
        val getViewSites = reachableMethods
            .flatMap { findGetViewSites(it).asSequence() }
            .toList()
        if (getViewSites.isNotEmpty())
            getViewSites.forEach { resolveGetView(it, layouts, viewResolver) }
    }

    private fun resolveGetView(getViewSite: StmtSite, layouts: Set<View>,
                               viewResolver: ViewResolver) {
        for (layout in layouts) {
            viewResolver.bindViewAttrs(layout as UIView, getViewSite, getViewSite.method)
        }
    }

    /**
     * similar to the one in LayoutMapper
     * TODO: refactor
     */
    private fun findGetViewSites(method: SootMethod): List<StmtSite> {
        require(method.isConcrete)
        val activeBody = method.retrieveActiveBody()
        return activeBody.units.asSequence()
            .filterIsInstance<Stmt>()
            .filter { it.containsInvokeExpr() }
            .filter { it.invokeExpr.method.subSignature == getViewFragmentMethodSubsignature && it.invokeExpr.method.declaringClass.isFragment() }
            .map { StmtSite(it, method) }
            .toList()
    }


}
