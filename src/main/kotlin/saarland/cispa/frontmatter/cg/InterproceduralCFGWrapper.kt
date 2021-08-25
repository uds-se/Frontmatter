package saarland.cispa.frontmatter.cg

import boomerang.preanalysis.BoomerangPretransformer
import soot.Body
import soot.Scene
import soot.SootMethod
import soot.Unit
import soot.dexpler.DalvikThrowAnalysis
import soot.jimple.infoflow.solver.cfg.InfoflowCFG
import soot.jimple.toolkits.callgraph.CallGraph
import soot.jimple.toolkits.ide.icfg.BiDiInterproceduralCFG
import soot.jimple.toolkits.ide.icfg.JimpleBasedInterproceduralCFG
import soot.toolkits.graph.DirectedGraph
import soot.toolkits.graph.ExceptionalUnitGraph

class InterproceduralCFGWrapper(sceneV: Scene, val callGraph: CallGraph) : InfoflowCFG(getGraph())

fun getGraph(): BiDiInterproceduralCFG<Unit, SootMethod>? {
    if (!BoomerangPretransformer.v().isApplied) {
        BoomerangPretransformer.v().apply() // works only on reachable methods!
    }
    val baseCFG = object : JimpleBasedInterproceduralCFG(true, true) {
        override fun makeGraph(body: Body): DirectedGraph<Unit> {
            return ExceptionalUnitGraph(body, DalvikThrowAnalysis.interproc(), true)
        }
    }
    baseCFG.setIncludePhantomCallees(true)
    return baseCFG
}
