package saarland.cispa.frontmatter.resolvers

import mu.KLogging
import saarland.cispa.frontmatter.ActivityOrFragmentClass
import saarland.cispa.frontmatter.AppModel
import saarland.cispa.frontmatter.FragmentClass
import saarland.cispa.frontmatter.FragmentWithContainer
import saarland.cispa.frontmatter.Resolver
import saarland.cispa.frontmatter.Utils.cartesianProduct
import saarland.cispa.frontmatter.Utils.implements
import saarland.cispa.frontmatter.Utils.isActivity
import saarland.cispa.frontmatter.Utils.isSystemMethod
import saarland.cispa.frontmatter.Utils.isAndroidClass
import saarland.cispa.frontmatter.Utils.isAndroidMethod
import saarland.cispa.frontmatter.Utils.isDummy
import saarland.cispa.frontmatter.Utils.isFragment
import saarland.cispa.frontmatter.Utils.isFromLib
import saarland.cispa.frontmatter.Utils.isReal
import saarland.cispa.frontmatter.data.StmtSite
import saarland.cispa.frontmatter.fragmentAddReplaceTransactionSubsignatures
import saarland.cispa.frontmatter.fragmentGetActivity
import saarland.cispa.frontmatter.getFragmentManager
import saarland.cispa.frontmatter.zeroContainerId
import soot.IntType
import soot.RefType
import soot.Scene
import soot.SootClass
import soot.SootMethod
import soot.jimple.AssignStmt
import soot.jimple.InstanceInvokeExpr
import soot.jimple.NewExpr
import soot.jimple.Stmt


/**
 * Activity.getFragmentManager
 * FragmentActivity.getSupportFragmentManager
 * TODO: getChildFragmentManger for nested fragments
 * */
data class FragmentWithContainerAndOrigin(val containerId: ResourceId, val fragment: FragmentClass, val origin: Set<ActivityOrFragmentClass>)

class FragmentResolver(sceneV: Scene, private val appModel: AppModel, private val valueResolver: ValueResolver) : Resolver(sceneV) {
    companion object : KLogging()

    //    private val callGraph = sceneV.callGraph
    private val activities = appModel.activities
    private val resourceResolver = ResourceResolver(valueResolver)

    /**
     * @return fragment to activities mapping
     * */
    fun resolve() {
        resolveFromActivities() // TODO: merge together as reachable methods from particular activities and fragments do intersect
        resolveFromFragments()
    }

    private fun resolveFromActivities() {
        for (activity in activities) {
            if (!activity.isReal())
                continue
            val attachedFragments = resolveFrom(activity)
            attachedFragments.forEach { (container, fragmentClass, origins) ->
                // activity in getContext(it.first.location)
                if (origins.isEmpty() || origins.any { sceneV.implements(activity, it) }) { // either we failed to get a real activity and use heuristic or use actual activity
                    val fragmentWithContainer = FragmentWithContainer(fragmentClass, container.value)
                    appModel.fragmentMapping.put(activity, fragmentWithContainer)
                }

            }
        }
        appModel.updateFragmentToActivity()
    }

    private fun resolveFromFragments() {
        val resolvedFragments = appModel.fragmentMapping
        // we should start from a fragment which is already in the mapping
        val toResolve = resolvedFragments.map { it.o2.value }
//            .filter{it.isFragment()}
            .toMutableList()
        val resolved = mutableSetOf<FragmentClass>()
        while (toResolve.isNotEmpty()) {
            val fromFragment = toResolve.removeAt(0)
            val fromActivities = appModel.fragmentToActivity[fromFragment]
            val mapping = resolveFrom(fromFragment)
            for ((resId, fragment, origins) in mapping) {
                if (fragment !in appModel.fragmentToActivity.keySet()) toResolve.add(fragment)
                val fragmentWithContainer = FragmentWithContainer(fragment, resId.value)
                val activityOrigins = origins.filter { it.isActivity() }
                activityOrigins.forEach {
                    appModel.fragmentToActivity.put(fragment, it)
                    appModel.fragmentMapping.put(it, fragmentWithContainer)
                }
                val fragmentOrigins = origins.filter { it.isFragment() }
                val originActivities = fragmentOrigins.flatMap { appModel.fragmentToActivity[it] }
                for (a in originActivities) {
                    appModel.fragmentToActivity.put(fragment, a)
                    appModel.fragmentMapping.put(a, fragmentWithContainer)
                }
                if (origins.isEmpty()) { // we could not resolve origin: likely an interface, use heuristic
                    fromActivities.forEach {
                        appModel.fragmentToActivity.put(fragment, it)
                        appModel.fragmentMapping.put(it, fragmentWithContainer)
                    }
                }
            }
        }
    }

    private fun resolveFrom(activityOrFragment: SootClass): List<FragmentWithContainerAndOrigin> {
        if (activityOrFragment.isAndroidClass()) // ignore default activities
            return emptyList()
        if (activityOrFragment.isFromLib()) // ignore activities from common libraries
            return emptyList()
        val reachableMethods = getReachableMethodsOfActivity(activityOrFragment)
        val scope = getActivityOrFragmentScope(activityOrFragment)
        return reachableMethods
            .filter { it.isConcrete }
            .filterNot { it.isAndroidMethod() }
            .filterNot { it.isDummy() }
            .filterNot { it.isSystemMethod() }
            .flatMap { resolveInMethod(it, scope).asSequence() }
            .toList()
    }

    private fun getActivityOrFragmentScope(activity: ActivityOrFragmentClass): Set<String> {
        return if (appModel.contextToScope.containsKey(activity)) appModel.contextToScope[activity]
        else {
            val scope = getContextFor(activity)
            appModel.contextToScope.putAll(activity, scope)
            scope
        }
    }

    /**
     * find FragmentTransaction add() and replace()
     * */
    private fun resolveInMethod(method: SootMethod, scope: Set<String>): List<FragmentWithContainerAndOrigin> {
        val addReplaceSites = filterStmtBySubsignature(method, fragmentAddReplaceTransactionSubsignatures)
        return addReplaceSites.flatMap { resolveTransactionCallSite(it, scope) }
    }

    /**
     * FragmentTransactions
     * e.g. android.app.FragmentTransaction add(android.app.Fragment,java.lang.String)
     * TODO: may need findActivity to reduce overapproximation
     * */
    private fun resolveTransactionCallSite(callSite: StmtSite, scope: Set<String>): List<FragmentWithContainerAndOrigin> {
        val containerId = resolveContainerId(callSite, scope)
        val targetFragmentUnsafe = resolveFragmentFromArg(callSite, scope)
        val targetFragment = targetFragmentUnsafe
            .onEach { if (!it.isFragment()) logger.warn("Obfuscated fragment found: `${it.name}`, excluded from analysis") }
            .filter { it.isFragment() }
        val activity = findActivity(callSite, scope)
        val fragmentsWithContainer = containerId.cartesianProduct(targetFragment)
        return fragmentsWithContainer.map { FragmentWithContainerAndOrigin(it.first, it.second, activity) }
    }

    /**
     * if returns 0, then fragment is not inserted into a container view
     */
    private fun resolveContainerId(callSite: StmtSite, scope: Set<String>): List<ResourceId> {
        return if (callSite.getInvokeExpr().methodRef.getParameterType(0) != IntType.v())
            listOf(ResourceId(zeroContainerId, null))
        else
            resourceResolver.resolveResourceId(callSite, scope, 0)//TODO limit scope
    }

    // TODO: resolve fragment tags
    private fun resolveFragmentFromArg(callSite: StmtSite, scope: Set<String>): Set<SootClass> {
        // at first get type of a fragment
        val fragmentArgIdx = callSite.stmt.invokeExpr.args.indexOfFirst { it.type != RefType.v("java.lang.String") && it.type != IntType.v() } //TODO: process int and string args
        val fragmentValue = callSite.stmt.invokeExpr.getArg(fragmentArgIdx)
        val fragmentClass = (fragmentValue.type as RefType).sootClass
        if (!fragmentClass.isAndroidClass() && fragmentClass.isConcrete) { // resolve by type
            logger.info("Fragment found $fragmentClass @ $callSite")
            return setOf(fragmentClass)
        }
        // got interface or abstract class - try to resolve fragment value
        val fragmentAllocationSites = valueResolver.resolveArg(callSite, fragmentArgIdx, scope)
        if (fragmentAllocationSites.isEmpty()) {
            logger.warn("==> Fragment @ $callSite cannot be resolved")
        }
        return fragmentAllocationSites.mapNotNull { resolveFragmentObject(it) }.toSet()
    }

    private fun resolveFragmentObject(callSite: StmtSite): SootClass? {
        val stmt = callSite.stmt
        // should find newExpr statement
        logger.info("Fragment found $stmt @ $callSite")
        return if (stmt is AssignStmt && stmt.rightOp is NewExpr) {
            (stmt.rightOp.type as RefType).sootClass
        } else {
            logger.warn("Ambiguous fragment found at $stmt in ${callSite.method}")
            null
        }
    }

    /**
     * The code can be inside an activity or in a fragment, then the activity is retrieved via getActivity()
     * */
    private fun findActivity(callSite: StmtSite, scope: Set<String>) = resolveTransactionCaller(callSite, scope)

    private fun resolveTransactionCaller(callSite: StmtSite, scope: Set<String>): Set<ActivityOrFragmentClass> {
        require(callSite.stmt.containsInvokeExpr())
        val transactionStmt = valueResolver.resolveInvokerBase(callSite, scope)
        val getFragmentManagerStmts = transactionStmt
            .filter { it.stmt.containsInvokeExpr() }
            .filter { it.stmt.invokeExpr.method.subSignature in getFragmentManager }
        return getFragmentManagerStmts.flatMap { resolveActivity(it, scope) }.toSet()
    }

    /**
     * at first resolve by type of a base
     * if failed, get Class
     * */
    private fun resolveActivity(getManagerStmtSite: StmtSite, scope: Set<String>): Set<ActivityOrFragmentClass> {
        require(getManagerStmtSite.containsInvokeExpr())
        val invokeExpr = getManagerStmtSite.stmt.invokeExpr
        require(invokeExpr is InstanceInvokeExpr)
        val activityValue = invokeExpr.base
        val activityClass = (activityValue.type as RefType).sootClass
        if (!activityClass.isAndroidClass() && activityClass.isConcrete) {
            return setOf(activityClass)
        }
        return findGetActivity(getManagerStmtSite, scope)
    }

    private fun findGetActivity(getManagerStmtSite: StmtSite, scope: Set<String>): Set<ActivityOrFragmentClass> {
        val activityAllocationSites = valueResolver.resolveInvokerBase(getManagerStmtSite, scope)
        if (activityAllocationSites.isEmpty()) return emptySet()
        val getActivityStmt = activityAllocationSites.map { it.stmt }
            .filterIsInstance<AssignStmt>()
            .filter { it.containsInvokeExpr() }
            .filter { it.invokeExpr.method.subSignature in fragmentGetActivity }
        if (getActivityStmt.isEmpty()) {
            logger.warn("Cannot resolve origin Activity or Fragment @ $getActivityStmt")
            return emptySet()
        }
        return getActivityStmt.flatMap { resolveCurrentFragment(it) }.toSet()
    }

    private fun resolveCurrentFragment(getActivityStmt: Stmt): Set<FragmentClass> {
        require(getActivityStmt.containsInvokeExpr())
        val invokeExpr = getActivityStmt.invokeExpr
        require(invokeExpr is InstanceInvokeExpr)
        val fragmentValue = invokeExpr.base
        val fragmentClass = (fragmentValue.type as RefType).sootClass
        if (!fragmentClass.isAndroidClass() && fragmentClass.isConcrete) {
            return setOf(fragmentClass)
        }
        logger.warn("Cannot resolve Fragment")
        return emptySet()
    }


}
