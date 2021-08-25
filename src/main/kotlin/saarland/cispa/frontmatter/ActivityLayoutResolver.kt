package saarland.cispa.frontmatter

import mu.KLogging
import saarland.cispa.frontmatter.Utils.isActivity
import saarland.cispa.frontmatter.Utils.isAndroidClass
import saarland.cispa.frontmatter.Utils.isDialog
import saarland.cispa.frontmatter.Utils.isReal
import saarland.cispa.frontmatter.Utils.isSystemMethod
import saarland.cispa.frontmatter.Utils.isAndroidMethod
import saarland.cispa.frontmatter.Utils.isDummy
import saarland.cispa.frontmatter.Utils.isFragment
import saarland.cispa.frontmatter.Utils.isFromLib
import saarland.cispa.frontmatter.Utils.isListActivity
import saarland.cispa.frontmatter.Utils.isListView
import saarland.cispa.frontmatter.data.FragmentContainer
import saarland.cispa.frontmatter.data.MergeContainer
import saarland.cispa.frontmatter.data.StmtSite
import saarland.cispa.frontmatter.data.UIElement
import saarland.cispa.frontmatter.data.UIViewElement
import saarland.cispa.frontmatter.data.UIViewGroupElement
import saarland.cispa.frontmatter.resolvers.AdapterResolver
import saarland.cispa.frontmatter.resolvers.DialogResolver
import saarland.cispa.frontmatter.resolvers.ResourceResolver
import saarland.cispa.frontmatter.resolvers.FindViewResolver
import saarland.cispa.frontmatter.resolvers.MenuResolver
import saarland.cispa.frontmatter.resolvers.UIView
import saarland.cispa.frontmatter.resolvers.ValueResolver
import saarland.cispa.frontmatter.resolvers.View
import saarland.cispa.frontmatter.resolvers.ViewResolver
import soot.NullType
import soot.RefType
import soot.Scene
import soot.SootMethod
import soot.jimple.AssignStmt
import soot.jimple.InstanceInvokeExpr
import soot.util.MultiMap

/**
 * void setContentView(View view, ViewGroup.LayoutParams params)
 * void setContentView(View view)
 * void setContentView(int layoutResID)
 *
 * setContentView applies for Activities only
 * for Fragments one should return a view in onCreateView
 *
 * Override
 * public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
 *     return inflater.inflate(R.layout.ads_tab, container, false);
 * }
 */

/**
 * Determine mapping between layouts and activities
 * */


class ActivityLayoutResolver(sceneV: Scene, private val appModel: AppModel,
                             val valueResolver: ValueResolver,
                             val resourceParser: ResourceParser,
                             private val layoutAnalysis: XmlLayoutAnalysis) : Resolver(sceneV) {
    companion object : KLogging()

    val activities = appModel.activities
    private val resourceResolver = ResourceResolver(valueResolver)

    fun resolve(): AppModel {
        resolveLayoutMappingsTo(appModel.activityLayouts)
        bindFragmentsFromXmlTo(appModel.fragmentMapping, appModel.activityLayouts)
        appModel.updateFragmentToActivity()
        return appModel
    }

    /**
     * Layout binding as a rule is performed in onCreate method.
     * setContentView can be invoked by:
     * 1) the target activity - in this case we capture it, or
     * 2) in the superclass (or abstract superclass). The current approach prevents us from identifying the real caller,
     * so we assume that the activity id is defined in real target activity (which may be not the case sometimes)
     * If the id allocation site is not in the Activity class we fail to resolveArg Layout mapping.
     *
     * @return
     */
    private fun resolveLayoutMappingsTo(results: MultiMap<ActivityClass, UIView>) {
        val menuResolver = MenuResolver(sceneV, appModel, valueResolver, layoutAnalysis)
        val dialogResolver = DialogResolver(sceneV, appModel, valueResolver, resourceParser, layoutAnalysis)
        val findViewResolver = FindViewResolver(sceneV, appModel, valueResolver, resourceParser, layoutAnalysis)
        val adapterResolver = AdapterResolver(sceneV, appModel, valueResolver, resourceParser, layoutAnalysis)
        val titleResolver = TitleResolver(sceneV, appModel, valueResolver, resourceParser)
        for (activity in activities) {
            if (activity.isFromLib()) continue // ignore activities from common libs
            if (activity.isAndroidClass()) // ignore default activities
                continue
            if (!activity.isReal()) // ignore abstract activities
                continue
            logger.info("=> Resolving activity layout $activity")
            val layouts = resolveLayoutMapping(activity)
            results.putAll(activity, layouts)
            val reachableMethods = getReachableMethodsOfActivity(activity)
                .filter { it.isConcrete }
                .filterNot { it.isSystemMethod() }
                .filterNot { it.isAndroidMethod() }
                .filterNot { it.isDummy() }
            logger.info("=> Layouts found ${layouts.size}")
            logger.info("=> Resolve titles for $activity")
            titleResolver.resolveTitleFrom(activity, reachableMethods)
            logger.info("=> Resolving findViews for $activity")
            findViewResolver.resolveViewsFrom(activity, reachableMethods)
            logger.info("=> Resolving adapters for $activity")
            adapterResolver.resolveAdaptersFrom(activity, reachableMethods)
            if (activity.isListActivity()) {
                //TODO: listActivity listener fot itemclicks
            }
            logger.info("=> Resolving menus for $activity")
            menuResolver.resolveMenusFrom(activity)
            logger.info("=> Resolving dialogs for $activity")
            dialogResolver.resolveDialogsFrom(activity, reachableMethods)
        }
    }

    private fun getActivityScope(activity: ActivityClass): Set<String> {
        return if (appModel.contextToScope.containsKey(activity)) appModel.contextToScope[activity]
        else {
            val scope = getContextFor(activity)
            appModel.contextToScope.putAll(activity, scope)
            scope
        }
    }

    private fun resolveLayoutMapping(activity: ActivityClass): Set<UIView> {
        // search for all @{setContentView} calls, identify its context, and detect allocation site of the argument
        val scope = getActivityScope(activity)
        val viewResolver = ViewResolver(activity, appModel, sceneV, valueResolver, resourceParser, layoutAnalysis)
        val reachableMethods = getOnCreateReachableMethods(activity)
//            .filter { it.declaringClass in appClasses } superclass may come from a library
            .filterNot { it.isSystemMethod() }
            .filterNot { it.isAndroidMethod() }
            .filter { it.isConcrete }

        val listViews = if (activity.isListActivity())
            resolveByListAdapter(reachableMethods, activity, viewResolver, scope)
        else null
        val contentViews = resolveByContentView(reachableMethods, activity, viewResolver)
//        if (activity.isListActivity()) {
//            val listViews = resolveByListAdapter(reachableMethods, activity, viewResolver, scope)
//        }
        if (contentViews != null) {
            if (listViews != null)
                attachList(contentViews, listViews)
            return contentViews
        }
        val findView = resolveByFindView(reachableMethods, viewResolver, scope)
        if (findView != null) {
            if (listViews != null)
                attachList(findView, listViews)
            return findView
        }
        if (listViews != null)
            return listViews

        logger.warn("Unknown layout binding for activity: $activity, may have default implementation")
        return setOf(getDefaultLayout())
    }

    private fun attachList(contentViews: Set<UIView>, listViews: Set<UIView>) {
        for (contentView in contentViews) {
            val listChildren = contentView.uiViewElement.getAllChildrenFlatten()
                .filter { it.viewClass.isListView() }
            listChildren.forEach {
                it.addChildren(listViews.flatMap { lv -> (lv.uiViewElement as UIViewGroupElement).children })
            }
        }
    }

    private fun resolveByListAdapter(reachableMethods: Sequence<SootMethod>, activity: ActivityClass, viewResolver: ViewResolver, scope: Set<String>): Set<UIView>? {
        val setListAdapterSites = reachableMethods
            .flatMap { findSetListAdapterSites(it).asSequence() }
            .toList()
        val containerView = UIView(UIViewGroupElement(defaultContainerId, sceneV.getSootClass("android.widget.ListView")), null)
        for (setListAdapterSite in setListAdapterSites) {
            processSetListAdapterSite(setListAdapterSite, containerView, viewResolver, scope)
        }
        return if (containerView.uiViewElement.hasChildren())
            setOf(containerView)
        else null
    }

    private fun processSetListAdapterSite(setListAdapterSite: StmtSite, containerView: UIView, viewResolver: ViewResolver, scope: Set<String>) {
        val argClassType = setListAdapterSite.getInvokeExpr().getArg(0).type
        if (argClassType is NullType)
            return
        require(argClassType is RefType)
        val adapterClasses = if (viewResolver.isCustomAdapter(argClassType.sootClass)) {
            listOf(argClassType.sootClass)
        } else {
            val adapters = valueResolver.resolveArg(setListAdapterSite, 0, scope)  // TODO: make a workaround if boomerang failed
            adapters.filterNot { it.isNullType() }
                .filter { it.stmt is AssignStmt }
                .map { ((it.stmt as AssignStmt).leftOp.type as RefType).sootClass }
        }
        for (adapterClass in adapterClasses) {
            val attached = viewResolver.attachAdapter(adapterClass, setListAdapterSite, containerView)
        }
    }

    /***
     * some activities have in framework implementation of onCreate() with default layouts attached
     * currently just create dummy container
     * TODO: for specific activities inflate and attach default layouts
     */
    private fun getDefaultLayout(location: StmtSite? = null): UIView {
        val content = UIViewGroupElement(defaultContainerId, sceneV.getSootClass("android.view.ViewGroup"))
        return UIView(content, location)
    }

    private fun resolveByContentView(reachableMethods: Sequence<SootMethod>, activity: ActivityClass,
                                     viewResolver: ViewResolver): Set<UIView>? {
        val contentViewSites = reachableMethods
            .flatMap { findSetContentViewSites(it).asSequence() }
            .toList()
        return if (contentViewSites.isNotEmpty())
            contentViewSites.flatMap { processSetContentViewSite(it, activity, viewResolver) }.toSet()
        else null
    }

    private fun resolveByFindView(reachableMethods: Sequence<SootMethod>, viewResolver: ViewResolver, scope: Set<String>): Set<UIView>? {
        val findViewSites = reachableMethods
            .flatMap { findViewByIdSites(it).asSequence() }
        val containerViewSites = findViewSites.filter { isContainerView(it, scope) }.toList()
        return if (containerViewSites.isNotEmpty())
            containerViewSites.map { processFindViewSite(it, viewResolver) }.filterIsInstance<UIView>().toSet()
        else null
    }

    private fun isContainerView(stmtSite: StmtSite, scope: Set<String>): Boolean {
        val viewId = resourceResolver.resolveResourceId(stmtSite, scope)
        return viewId.any { it.value in containerIds }
    }

    /**
     * stmtSite - like r0 = findViewById(R.id.content)
     * we need to get another variable to get proper reference
     * the case, when a layout is directly attached to the content view of an activity
     * */
    private fun processFindViewSite(stmtSite: StmtSite, viewResolver: ViewResolver): View {
        val container = UIViewGroupElement(defaultContainerId, sceneV.getSootClass("android.view.ViewGroup"))
        val newExprStmtSite = stmtSite.getNewStmtFromViewById()
        val layout = UIView(container, stmtSite) //XXX: or newExprStmtSite???
        val viewAdded = viewResolver.bindAddView(newExprStmtSite, layout)
        if (!viewAdded) {
            val viewInflated = viewResolver.bindInflateForward(layout, newExprStmtSite)
            if (!viewInflated)
                logger.warn("Failed to add any view to the activity content")
        }
        return layout
    }

    private fun getOnCreateReachableMethods(activity: ActivityClass): Sequence<SootMethod> {
        val entryPoints = onCreateSubsignatures.mapNotNull { getTargetMethodBySubsignature(activity, it) }
        return if (entryPoints.isNotEmpty()) getReachableMethodsWithinContext(entryPoints, activity)
        else emptySequence()
    }

    private fun findSetContentViewSites(method: SootMethod): List<StmtSite> {
        return filterStmtBySubsignature(method, setContentViewSubsignatures)
    }

    private fun findViewByIdSites(method: SootMethod): List<StmtSite> {
        return filterStmtBySubsignature(method, findViewByIdSubsignatures)
    }

    private fun findSetListAdapterSites(method: SootMethod): List<StmtSite> {
        return filterStmtBySubsignature(method, setListAdapterSubsignature)
    }

    private fun bindFragmentsFromXmlTo(result: MultiMap<ActivityClass, FragmentWithContainer>, layouts: MultiMap<ActivityClass, UIView>) {
        for ((activity, layoutView) in layouts) {
            if (layoutView !is UIView) continue
            val layout = layoutView.uiViewElement
            val fragments = layout.getAllChildrenFlatten().filterIsInstance<FragmentContainer>()
                .onEach { if (!it.viewClass.isFragment()) logger.warn("Obfuscated fragment found : `${it.viewClass}`, excluded") }
                .filter { it.viewClass.isFragment() }
                .map { FragmentWithContainer(it.viewClass, xmlFragmentContainer) }.toSet()
            result.putAll(activity, fragments)
        }
    }

    /**
     * input - setContentView stmt
     * return - now viewIds, planned - View object
     *
     * TODO: we should consider only the last setContentView (from the target class or the closest superclass)
     * */
    private fun processSetContentViewSite(callSite: StmtSite, activity: ActivityClass,
                                          viewResolver: ViewResolver): Set<UIView> {
        val invokeExpr = callSite.stmt.invokeExpr
        require(invokeExpr is InstanceInvokeExpr)
        val callingActivity = (invokeExpr.base.type as RefType).sootClass
        if (callingActivity.isDialog()) {
            return emptySet()
        }
        if (!callingActivity.isActivity()) {
            logger.warn("setContentView called by non-Activity @$callSite")
            return emptySet()
        }
        val callSiteActivitySubclasses = sceneV.activeHierarchy.getSubclassesOfIncluding(callingActivity) // get all subclasses
        if (!callingActivity.isAndroidClass() && activity !in callSiteActivitySubclasses) {
            logger.warn("setContentView invoked on an Activity which has nothing in common with the target")
        }
        val layouts = resolveLayout(callSite, viewResolver, activity)
        appModel.activityLayouts.putAll(activity, layouts)
        if (invokeExpr.method.subSignature in setOf("void setContentView(android.view.View)", "void setContentView(android.view.View,android.view.ViewGroup\$LayoutParams)"))
            layouts.onEach { viewResolver.bindViewAttrs(it, it.location!!, callSite.method) }
        return layouts
    }

    private fun resolveLayout(callSite: StmtSite, viewResolver: ViewResolver,
                              context: ActivityOrFragmentClass): Set<UIView> {
        val invokeExpr = callSite.stmt.invokeExpr
        val scope = getActivityScope(context)
        return when (invokeExpr.method.subSignature) {
            "void setContentView(int)" -> {
                val ids = resourceResolver.resolveResourceId(callSite, scope)
                ids.mapNotNull { id -> layoutAnalysis.getLayout(id.value, context)?.let { layout -> getLayoutView(layout, callSite) } }.toSet()
            }
            "void setContentView(android.view.View)",
            "void setContentView(android.view.View,android.view.ViewGroup\$LayoutParams)" -> {
                resolveLayoutView(callSite, viewResolver, scope)
            }
            else -> error("Unexpected method during layout resolution: $invokeExpr")
        }
    }

    private fun getLayoutView(layout: UIElement, callSite: StmtSite): UIView {
        return when (layout) {
            is MergeContainer -> {
                val rootLayout = getDefaultLayout(callSite)
                rootLayout.uiViewElement.addChildren(layout.children)
                rootLayout
            }
            is UIViewElement -> {
                UIView(layout, callSite)
            }
            else -> error("Unknown layout type $layout")
        }
    }

    /**
     * callSite = setContentView(android.view.View)
     */
    private fun resolveLayoutView(callSite: StmtSite, viewResolver: ViewResolver, scope: Set<String>): Set<UIView> {
        val viewAllocationSites = valueResolver.resolveArg(callSite, 0, scope)
        val views = viewAllocationSites
            .asSequence()
            .filter { it.stmt is AssignStmt }
            .mapNotNull { viewResolver.initLayout(it) }
            .filterIsInstance<UIView>()
            .toSet()
        if (views.isEmpty()) logger.warn("Could not resolve View @ $callSite")
        return views
    }


}
