package saarland.cispa.frontmatter.resolvers

import mu.KLogging
import saarland.cispa.frontmatter.ActivityClass
import saarland.cispa.frontmatter.ActivityOrFragmentClass
import saarland.cispa.frontmatter.AppModel
import saarland.cispa.frontmatter.CallchainWalker
import saarland.cispa.frontmatter.Resolver
import saarland.cispa.frontmatter.ResourceParser
import saarland.cispa.frontmatter.UIElementWithContext
import saarland.cispa.frontmatter.Utils.isActivity
import saarland.cispa.frontmatter.Utils.isAndroidClass
import saarland.cispa.frontmatter.Utils.isAndroidMethod
import saarland.cispa.frontmatter.Utils.isDummy
import saarland.cispa.frontmatter.Utils.isFragment
import saarland.cispa.frontmatter.Utils.isFromLib
import saarland.cispa.frontmatter.Utils.isReal
import saarland.cispa.frontmatter.Utils.isSystemMethod
import saarland.cispa.frontmatter.cg.InterproceduralCFGWrapper
import saarland.cispa.frontmatter.data.StmtSite
import saarland.cispa.frontmatter.startActivityForResultSubsignatures
import saarland.cispa.frontmatter.startActivitySubsignatures
import soot.Local
import soot.RefType
import soot.Scene
import soot.SootClass
import soot.SootMethod
import soot.jimple.InstanceInvokeExpr
import soot.jimple.Stmt

data class Transition(val src: SootClass, val trigger: Set<UIElementWithContext>, val dest: SootClass, val withReturn: Boolean)
class TransitionResolver(sceneV: Scene, val appModel: AppModel,
                         private val icfg: InterproceduralCFGWrapper,
                         private val valueResolver: ValueResolver,
                         resources: ResourceParser,
                         intentFilters: Map<String, Set<String>>) : Resolver(sceneV) {

    companion object : KLogging()

    private val intentResolver = IntentResolver(sceneV, valueResolver, intentFilters, resources)
    // TODO: may be also other classes like Application

    var startActivityStmtCount = 0
    private val callchainWalker: CallchainWalker = CallchainWalker(sceneV, appModel, valueResolver)

    fun resolve() {
        resolveTransitionsTo(appModel.transitions)
    }

    private fun resolveTransitionsTo(activityTransitions: MutableSet<Transition>) {
        for (activity in appModel.activities) {
            if (activity.isFromLib()) continue // ignore activities from common libs
            if (activity.isAndroidClass()) // ignore default activities
                continue
            if (!activity.isReal()) // ignore default activities
                continue
            logger.info("=> Resolving transitions for activity $activity")
            val transitions = resolveTransitionsFrom(activity)
            activityTransitions.addAll(transitions)
        }
    }

    private fun resolveTransitionsFrom(activity: ActivityOrFragmentClass): List<Transition> {
        val reachableMethods = getReachableMethodsOfActivity(activity)
            .filter { it.isConcrete }
            .filterNot { it.isSystemMethod() }
            .filterNot { it.isAndroidMethod() }
            .filterNot { it.isDummy() }
        val startActivitySites = reachableMethods
            .flatMap { findStartActivitySites(it).asSequence() }
            .toList()
        return startActivitySites.flatMap { resolveStartActivityCallSite(it, activity) }
    }

    private fun findStartActivitySites(method: SootMethod): List<StmtSite> {
        require(method.isConcrete)
        val activeBody = method.retrieveActiveBody()
        return activeBody.units.asSequence()
            .filterIsInstance<Stmt>()
            .filter { it.containsInvokeExpr() }
            .filter { it.invokeExpr.method.subSignature in startActivitySubsignatures && !it.invokeExpr.method.isStatic }
            .map { StmtSite(it, method) }
            .toList()
    }

    /**
     * callSite: startActivity(...)
     */
    private fun resolveStartActivityCallSite(callSite: StmtSite, activity: ActivityOrFragmentClass): List<Transition> {
        val source = getStartActivitySource(callSite, activity)
        val target = findTarget(callSite)
        val triggers = getTrigger(callSite, activity)
        val withReturn = isStartActivityWithResult(callSite)
        return target.map { Transition(source, triggers, it, withReturn) }
    }

    private fun isStartActivityWithResult(callSite: StmtSite): Boolean {
        return callSite.getInvokeExprSubsignature() in startActivityForResultSubsignatures
    }

    private fun findTarget(callSite: StmtSite): Set<ActivityClass> {
        val intentAllocationSites = valueResolver.resolveArg(callSite, 0, null) //resolver returns not the Intent, but string
        if (intentAllocationSites.isEmpty()) {
            logger.warn("@@@ Intent at $callSite in ${callSite.method} cannot be resolved")
        }
        val targets = intentAllocationSites.mapNotNull { intentResolver.getTargetsFromIntent(it) }.toSet()
        return targets
    }

    private fun getTrigger(startActivityCallSite: StmtSite, activity: ActivityOrFragmentClass): Set<UIElementWithContext> {
        val uiElementsWithContext = callchainWalker.getUITriggerOfCall(startActivityCallSite)
        return uiElementsWithContext.filter { it.context == activity }.toSet()
    }

    private fun getStartActivitySource(startActivityCallSite: StmtSite, activity: ActivityOrFragmentClass): ActivityOrFragmentClass {
        val stmt = startActivityCallSite.stmt
        val invokeExpr = stmt.invokeExpr
        require(invokeExpr is InstanceInvokeExpr)
        val context = (invokeExpr.base.type as RefType).sootClass
        if (!context.isAndroidClass() && !context.isAbstract)
            return context
        val typedContext = getBaseClass(startActivityCallSite)
        if (!typedContext.isAndroidClass() && !typedContext.isAbstract)
            return typedContext

        val caller = startActivityCallSite.method.declaringClass
        if (caller.isInnerClass) {
            val innerCallers = resolveInnerCaller(caller)
            if (innerCallers != null) return innerCallers
        }
        logger.warn("Default start activity: $activity for transition @ $startActivityCallSite")
        return activity
    }

    private fun resolveInnerCaller(caller: SootClass): ActivityOrFragmentClass? {
        require(caller.isInnerClass)
        var classToCheck = caller
        do {
            classToCheck = classToCheck.outerClass
            if (classToCheck.isActivity() || classToCheck.isFragment()) return classToCheck
        } while (classToCheck.isInnerClass)
        return null
    }

    private fun getBaseClass(callSite: StmtSite): SootClass {
        val invokeExpr = callSite.stmt.invokeExpr as InstanceInvokeExpr
        val baseBox = invokeExpr.baseBox
        val baseLocal = baseBox.value as Local
        val method = callSite.method
        return (method.activeBody.locals.first { it == baseLocal }.type as RefType).sootClass
    }

}
