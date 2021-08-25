package saarland.cispa.frontmatter.resolvers

import mu.KLogging
import saarland.cispa.frontmatter.ActivityOrFragmentClass
import saarland.cispa.frontmatter.AppModel
import saarland.cispa.frontmatter.Resolver
import saarland.cispa.frontmatter.ResourceParser
import saarland.cispa.frontmatter.Utils.implements
import saarland.cispa.frontmatter.Utils.isAndroidClass
import saarland.cispa.frontmatter.Utils.isAndroidMethod
import saarland.cispa.frontmatter.Utils.isReal
import saarland.cispa.frontmatter.Utils.isSystemMethod
import saarland.cispa.frontmatter.Utils.isActivity
import saarland.cispa.frontmatter.Utils.isDummy
import saarland.cispa.frontmatter.Utils.isFragment
import saarland.cispa.frontmatter.XmlLayoutAnalysis
import saarland.cispa.frontmatter.adapterMethodSubsignatures
import saarland.cispa.frontmatter.data.StmtSite
import saarland.cispa.frontmatter.data.UIViewElement
import saarland.cispa.frontmatter.getListViewSubsignature
import saarland.cispa.frontmatter.listId
import saarland.cispa.frontmatter.setAdapterMethodNames
import soot.RefType
import soot.Scene
import soot.SootMethod
import soot.Unit
import soot.jimple.AssignStmt
import soot.jimple.Stmt


class AdapterResolver(sceneV: Scene, val appModel: AppModel, val valueResolver: ValueResolver,
                      val resourceParser: ResourceParser, val layoutAnalysis: XmlLayoutAnalysis) : Resolver(sceneV) {
    // TODO: refactor to ViewResolver
    private val activities = appModel.activities
    private val activityLayouts = appModel.activityLayouts
    private val fragments = appModel.fragments
    private val fragmentLayouts = appModel.fragmentLayouts

    companion object : KLogging()

    // not used
    private fun resolve() {
        for (activity in activities) {
            if (activity.isAndroidClass()) // ignore default activities
                continue
            if (!activity.isReal()) // ignore default activities
                continue
            val reachableMethods = getReachableMethodsOfActivity(activity)
                .filter { it.isConcrete }
                .filterNot { it.isSystemMethod() }
                .filterNot { it.isAndroidMethod() }
                .filterNot { it.isDummy() }
            resolveAdaptersFrom(activity, reachableMethods)
        }
        for (fragment in fragments) {
            if (fragment.isAndroidClass()) // ignore default activities
                continue
            if (!fragment.isReal()) // ignore default activities
                continue
            val reachableMethods = getReachableMethodsOfActivity(fragment)
                .filter { it.isConcrete }
                .filterNot { it.isSystemMethod() }
                .filterNot { it.isAndroidMethod() }
                .filterNot { it.isDummy() }
            resolveAdaptersFrom(fragment, reachableMethods)
        }
    }

    fun resolveAdaptersFrom(activity: ActivityOrFragmentClass, reachableMethods: Sequence<SootMethod>) {
        val viewResolver = ViewResolver(activity, appModel, sceneV, valueResolver, resourceParser, layoutAnalysis)
        val getListViewSites = reachableMethods
            .flatMap { findGetListViewSites(it).asSequence() }
            .toList()
        for (getListViewSite in getListViewSites) {
            val listViewElements = getListViewElement(activity)
            for (listViewElement in listViewElements) {
                viewResolver.bindViewAttrs(UIView(listViewElement, getListViewSite), getListViewSite, getListViewSite.method)
            }
        }
    }

    /*
    * either
    * 1) a root should be a ListView
    * 2) a view with listId
    *
    * */
    private fun getListViewElement(activity: ActivityOrFragmentClass): List<UIViewElement> {
        val layouts = when {
            activity.isActivity() -> activityLayouts[activity]
            activity.isFragment() -> fragmentLayouts[activity]
            else -> error("$activity is neither an Activity nor a Fragment")
        }
        return layouts.flatMap { layout -> listId.mapNotNull { layout.getChild(it) } }
    }

    private fun findGetListViewSites(method: SootMethod): List<StmtSite> {
        require(method.isConcrete)
        val activeBody = method.retrieveActiveBody()
        return activeBody.units.asSequence()
            .filterIsInstance<Stmt>()
            .filter { it.containsInvokeExpr() }
            .filter { isGetListView(it) }
            .map { StmtSite(it, method) }
            .toList()
    }

    /**
     * checks if a stmt contains setAdapter() of any AdapterView
     * complex adapters like PagerAdapter is not covered
     */
    private fun isGetListView(stmt: Stmt): Boolean {
        if (!stmt.containsInvokeExpr()) return false
        val methodRef = stmt.invokeExpr.methodRef
        return methodRef.subSignature.toString() in getListViewSubsignature
    }

    /**
     * checks if a stmt contains setAdapter() of any AdapterView
     * complex adapters like PagerAdapter is not covered
     */
    private fun isSetAdapter(stmt: Stmt): Boolean {
        if (!stmt.containsInvokeExpr()) return false
        val methodRef = stmt.invokeExpr.methodRef
        val parameterTypes = methodRef.parameterTypes
        return methodRef.name in setAdapterMethodNames &&
            sceneV.implements(methodRef.declaringClass.type, RefType.v("android.widget.AdapterView")) &&
            parameterTypes.size == 1 &&
            sceneV.implements(parameterTypes[0], RefType.v("android.widget.Adapter"))
    }


//    /** search for all getView methods, identify its return object, and detect allocation site of its call
//     * return ids of layouts containerView->LayoutId
//     * */
//    fun resolveAdaptersOld(): MultiMap<View, View> {
//        logger.info("Resolving adapter views")
//
//        val appClasses = sceneV.applicationClasses
//        val viewType = sceneV.getSootClassUnsafe("android.view.View").type
//
//        val adapterMethods = sceneV.reachableMethods.listener()
//            .asSequence()
//            .map { it.method() }
//            .filter { it.declaringClass in appClasses }
//            .filter { it.isConcrete }
//            .filter { it.subSignature in adapterMethodSubsignatures }
//            .filter { it.returnType == viewType }
//            .toList()
//        val associations = adapterMethods
//            .flatMap { processGetView(it) }.toMultiMap()
//        logger.info("Inflation size ${associations.size()}")
//        return associations
//    }
//
//    private fun processGetView(method: SootMethod): List<Pair<View, View>> {
//        val containers = processGetViewLocation(method)
//        if (containers.isEmpty())
//            return emptyList()
//        val views = processGetViewReturn(method)
//        return containers.cartesianProduct(views)
//    }
//
//    private fun processGetViewLocation(method: SootMethod): List<View> {
//        val locations = callGraph.edgesInto(method).asSequence().filterNot { it.src().isDummy() }.toList()
//        val setAdapterStmts = locations.map { it.src().getPrevNotConstStmt(it.srcUnit()) }
//            .filter { it.isSetAdaper() }
//        return setAdapterStmts.filter { it.invokeExpr is InstanceInvokeExpr }.flatMap { viewResolver.resolveViewFromBase(StmtSite(it, icfg.getMethodOf(it))) }
//    }
//
//    /**
//     * analyse return value in getView method
//     * */
//    private fun processGetViewReturn(method: SootMethod): List<View> {
//        val unitGraph = icfg.getOrCreateUnitGraph(method) as ExceptionalUnitGraph
//        val returnViews = unitGraph.tails.filterIsInstance<ReturnStmt>().filterNot { it is NullConstant }
//        return returnViews.flatMap { viewResolver.resolveViewFromVar(StmtSite(it, method), it.op) }
//    }
}

private fun SootMethod.getPrevNotConstStmt(srcUnit: Unit): Stmt {
    val stmt = this.activeBody.units.getPredOf(srcUnit)
    val isVarRepl = stmt is AssignStmt && stmt.leftOp.toString().startsWith("varReplacer")
    val isAdapterMethod = stmt is Stmt && stmt.containsInvokeExpr() && stmt.invokeExpr.methodRef.subSignature.toString() in adapterMethodSubsignatures
    return if (isVarRepl || isAdapterMethod)
        this.getPrevNotConstStmt(stmt)
    else
        stmt as Stmt
}

private fun Unit.isSetAdaper(): Boolean {
    if (this !is Stmt) return false
    if (!this.containsInvokeExpr()) return false
    return this.invokeExpr.methodRef.name in setAdapterMethodNames

}

