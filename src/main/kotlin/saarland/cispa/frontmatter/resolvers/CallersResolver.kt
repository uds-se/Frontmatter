package saarland.cispa.frontmatter.resolvers

import saarland.cispa.frontmatter.cg.InterproceduralCFGWrapper
import saarland.cispa.frontmatter.Utils.isDummy
import saarland.cispa.frontmatter.data.StmtSite
import soot.MethodOrMethodContext
import soot.Scene
import soot.SootClass
import soot.SootMethod

class CallersResolver(val sceneV: Scene, val icfg: InterproceduralCFGWrapper) {
    val callgraph = icfg.callGraph

    fun getCallers(stmtSite: StmtSite): Set<SootMethod> {
        val method = stmtSite.method
        val callersSet = mutableSetOf<MethodOrMethodContext>(method)
        fillCallersSet(method, callersSet)
        return callersSet.map { it.method() }.toSet()
    }

    private fun fillCallersSet(method: MethodOrMethodContext, result: MutableSet<MethodOrMethodContext>): Set<SootMethod> {
        val callers = callgraph.edgesInto(method).asSequence().map { it.src }.toSet()
        return callers
            .filterNot { it.method().isDummy() }
            .filter { it !in result }
            .onEach { result.add(it) }
            .flatMap { fillCallersSet(it, result) }
            .toSet()
    }

    fun adapters(stmtSite: StmtSite): List<SootClass> {
        val callers = getCallers(stmtSite)
        return callers.filter { it.name == "getView" || it.name == "bindView" }.map { it.declaringClass }

    }
}
