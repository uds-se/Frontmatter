package saarland.cispa.frontmatter

import mu.KLogging
import saarland.cispa.frontmatter.data.StmtSite
import saarland.cispa.frontmatter.resolvers.CallersResolver
import saarland.cispa.frontmatter.resolvers.NullView
import saarland.cispa.frontmatter.resolvers.ValueResolver
import saarland.cispa.frontmatter.resolvers.View
import saarland.cispa.frontmatter.resolvers.ViewResolver
import soot.Scene
import soot.SootClass
import soot.SootMethod
import soot.jimple.InvokeStmt
import soot.jimple.Stmt

/**
 * Identify callbacks
 *
 * set###Listener
 * */
class CallbackFinder(private val sceneV: Scene, private val valueResolver: ValueResolver,
                     private val viewResolver: ViewResolver) {

    companion object : KLogging()

    val icfg = valueResolver.icfg

    private val callersResolver = CallersResolver(sceneV, icfg)
    val callbacks by lazy { resolve() }

    fun resolve(): List<Triple<View, SootMethod, SootClass?>> {
        // search for all @{setText} calls, identify its context, and detect allocation site of the argument
        return sceneV.applicationClasses
            .asSequence()
            .flatMap { it.methods.asSequence() }
            .filter { it.isConcrete }
            .flatMap { processMethod(it).asSequence() }
            .toList()
    }

    private fun processMethod(method: SootMethod): List<Triple<View, SootMethod, SootClass?>> {
        require(method.isConcrete)
        val activeBody = method.retrieveActiveBody()
        return activeBody.units
            .filterIsInstance<Stmt>()
            .filter { it.containsInvokeExpr() }
            .filter { it.invokeExpr.method.subSignature in androidListeners }
            .flatMap { processSetCallbackCall(StmtSite(it, method)) }
    }

    private fun processSetCallbackCall(callSite: StmtSite): List<Triple<View, SootMethod, SootClass?>> {
//        if (!icfg.isReachable(callSite.stmt)) {
//            logger.warn("Statement ${callSite.stmt} @ ${callSite.method.declaringClass} is not in callGraph")
//            return emptyList()
//        }
        val views = viewResolver.resolveViewFromBase(callSite)
        val callbackStmt = getNextInvokeStmt(callSite.stmt)
            ?: return emptyList() // due to callback patching the next stmt should be a callback call in a patched callgraph (or can be varReplacer??? = #)
        logger.warn("==> $callbackStmt")
        val callback = callbackStmt.invokeExpr.methodRef.resolve()
        val results = ArrayList<Triple<View, SootMethod, SootClass?>>()
        if (views.isEmpty()) {
            //check Adapter membership
            val adapterClasses = callersResolver.adapters(callSite)
            adapterClasses.onEach { results.add(Triple(NullView(callSite), callback, it)) }
        }
        for (view in views) {
            results.add(Triple(view, callback, view.location?.method?.declaringClass))//FIXME
        }
        return results
    }

    private fun getNextInvokeStmt(unit: soot.Unit): InvokeStmt? {
        var stmt = unit
        do {
            stmt = icfg.getSuccsOf(stmt).first() //FIXME!
            if (stmt is InvokeStmt) return stmt
        } while (stmt.toString().startsWith("varReplacer"))
        return null // shoudl be illegal state
    }

}
