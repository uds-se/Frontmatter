package saarland.cispa.frontmatter

import saarland.cispa.frontmatter.data.StmtSite
import saarland.cispa.frontmatter.Utils.isActivity
import saarland.cispa.frontmatter.Utils.isFragment
import saarland.cispa.frontmatter.Utils.implements
import saarland.cispa.frontmatter.Utils.isAndroidClass
import saarland.cispa.frontmatter.Utils.isAndroidMethod
import saarland.cispa.frontmatter.Utils.isDummy
import saarland.cispa.frontmatter.Utils.isSystemMethod
import soot.Scene
import soot.SootClass
import soot.SootMethod
import soot.jimple.Stmt
import soot.jimple.toolkits.callgraph.CallGraph
import soot.jimple.toolkits.callgraph.EdgePredicate
import soot.jimple.toolkits.callgraph.Filter
import soot.jimple.toolkits.callgraph.ReachableMethods

open class Resolver(val sceneV: Scene) {
    protected val callgraph: CallGraph = sceneV.callGraph

    /**
     * returns all methods reachable for a certain activity (from activity_dummy_method)
     */
    protected fun getContextFor(activity: ActivityOrFragmentClass): Set<String> {
        return getReachableMethodsOfActivity(activity)
            .filter { it.isConcrete }
            .filterNot { it.isSystemMethod() }
            .filterNot { it.isAndroidMethod() }
//            .filterNot { it.isDummy() } must be there
            .map { it.signature }
            .toSet()
    }

    protected fun getReachableMethodsOfActivity(activityOrFragment: ActivityOrFragmentClass): Sequence<SootMethod> {
        val entryPoints = listOfNotNull(getDummyMethod(activityOrFragment))
        return getReachableMethodsWithinContext(entryPoints, activityOrFragment)
    }

    /***
     * search for methods reachable from the context: activity or fragment, don't consider siblings in class hierarchy
     */
    protected fun getReachableMethodsWithinContext(entryPoints: List<SootMethod>, context: ActivityOrFragmentClass): Sequence<SootMethod> {
        val filter = Filter(EdgePredicate { edge ->
            val targetClass = edge.tgt().declaringClass
            if (targetClass.isInnerClass) {
                val outerClass = targetClass.outerClass //TODO: multiple inner classes
                !(outerClass.isActivity() && context.isActivity() && !(sceneV.implements(context, outerClass) || sceneV.implements(outerClass, context)) ||
                    (outerClass.isFragment() && context.isFragment() && !(sceneV.implements(context, outerClass) || sceneV.implements(outerClass, context))))
            } else
                !(targetClass.isActivity() && context.isActivity() && !(sceneV.implements(context, targetClass) || sceneV.implements(targetClass, context)) ||
                    (targetClass.isFragment() && context.isFragment() && !(sceneV.implements(context, targetClass) || sceneV.implements(targetClass, context))))
        })
        val reachableMethods = ReachableMethods(callgraph, entryPoints.iterator(), filter)
        reachableMethods.update()
        return Sequence { reachableMethods.listener() }.map { it.method() } //  .filterNot { it.isAndroidMethod() }
    }

    protected fun getDummyMethod(activityOrFragment: ActivityOrFragmentClass): SootMethod? {
        val componentPart = activityOrFragment.name.replace("_", "__").replace(".", "_")
        val baseMethodName = dummyMainMethod + "_" + componentPart
        return sceneV.getSootClass(dummyMainClass).getMethodByNameUnsafe(baseMethodName) ?: null //error("No dummy method for $activityOrFragment")
    }

    protected fun filterStmtBySubsignature(method: SootMethod, targetSubsignatures: Set<String>): List<StmtSite> {
        require(method.isConcrete)
        val activeBody = method.retrieveActiveBody()
        return activeBody.units.asSequence()
            .filterIsInstance<Stmt>()
            .filter { it.containsInvokeExpr() }
            .filter { it.invokeExpr.method.subSignature in targetSubsignatures }
            .map { StmtSite(it, method) }
            .toList()
    }

    protected fun getTargetMethodBySubsignature(activity: ActivityOrFragmentClass, targetMethodSubsignature: String): SootMethod? {
        val targetMethod = activity.getMethodUnsafe(targetMethodSubsignature)
        if (targetMethod != null) return targetMethod
        val superclass = activity.superclassUnsafe
        if (superclass == null || superclass.isAndroidClass()) return null
        return getTargetMethodBySubsignature(superclass, targetMethodSubsignature)
    }

    /**
     * get Context (top calling class: ActivityOrFragment) of a statement using callgraph
     */
    protected fun getContext(location: StmtSite?): Set<ActivityOrFragmentClass> {
        if (location == null) return emptySet()
        val method = location.method
        val workList = mutableListOf(method)
        val visitedNodes = mutableSetOf(method)
        val contexts = mutableSetOf<SootClass>()
        while (workList.isNotEmpty()) {
            val node = workList.removeAt(0)
            if (node.declaringClass.isActivity() || node.declaringClass.isFragment())
                if (contexts.none { sceneV.implements(it, node.declaringClass) })// don't take superclasses
                    contexts.add(node.declaringClass)
            visitedNodes.add(node)
            callgraph.edgesInto(node)
                .asSequence()
                .filterNot { it.src().isAndroidMethod() }
                .filterNot { it.src().isDummy() }
                .filterNot { it.src() in visitedNodes }
                .filterNot { it.src() in workList }
                .filterNot { it.isSpecial && !it.tgt().isConstructor && it.src().declaringClass != it.tgt().declaringClass } //exclude only superclass calls
                .forEach {
                    workList.add(it.src())
                }
        }
        return contexts
    }

    /**
     * get Context (top calling class: ActivityOrFragment) of a statement using callgraph
     */
    protected fun getScopeMethods(location: StmtSite?): Set<SootMethod> {
        if (location == null) return emptySet()
        val method = location.method
        val workList = mutableListOf(method)
        val visitedNodes = mutableSetOf(method)
        val contexts = mutableSetOf<SootMethod>()
        while (workList.isNotEmpty()) {
            val node = workList.removeAt(0)
            if (!node.isAndroidMethod() && !node.isSystemMethod()) {
                contexts.add(node)
                if (node.declaringClass.isActivity()) {
                    // add lifecycle methods to the context
                    val onCreate = node.declaringClass.getMethodByNameUnsafe("onCreate")
                    if (onCreate != null)
                        contexts.add(onCreate)
                }
            }
            visitedNodes.add(node)
            callgraph.edgesInto(node)
                .asSequence()
                .filterNot { it.src().isAndroidMethod() }
                .filterNot { it.src().isDummy() }
                .filterNot { it.src() in visitedNodes }
                .filterNot { it.src() in workList }
                .filterNot { it.isSpecial && !it.tgt().isConstructor && it.src().declaringClass != it.tgt().declaringClass } //exclude only superclass calls
                .forEach {
                    workList.add(it.src())
                }
        }
        return contexts
    }

//    protected fun getTargetMethodByName(activity: ActivityOrFragmentClass, targetMethodName: String): SootMethod? {
//        val targetMethod = activity.getMethodByNameUnsafe(targetMethodName)
//        if (targetMethod != null) return targetMethod
//        val superclass = activity.superclassUnsafe
//        if (superclass == null || superclass.isAndroidClass()) return null
//        return getTargetMethodBySubsignature(superclass, targetMethodName)
//    }

}
