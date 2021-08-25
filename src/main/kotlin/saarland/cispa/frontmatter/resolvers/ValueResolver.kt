package saarland.cispa.frontmatter.resolvers

import boomerang.BackwardQuery
import boomerang.Boomerang
import boomerang.ForwardQuery
import boomerang.callgraph.BoomerangICFG
import boomerang.callgraph.ObservableICFG
import boomerang.callgraph.ObservableStaticICFG
import boomerang.debugger.ConsoleDebugger
import boomerang.debugger.Debugger
import boomerang.jimple.Statement
import boomerang.jimple.Val
import boomerang.results.BackwardBoomerangResults
import boomerang.results.ForwardBoomerangResults
import mu.KLogging
import saarland.cispa.frontmatter.FrontmatterBoomerangOptions
import saarland.cispa.frontmatter.Utils.implements
import saarland.cispa.frontmatter.cg.InterproceduralCFGWrapper
import saarland.cispa.frontmatter.data.StmtSite
import saarland.cispa.frontmatter.inflatorSubsignatures
import soot.Modifier
import soot.Scene
import soot.SootMethod
import soot.Value
import soot.jimple.AssignStmt
import soot.jimple.CastExpr
import soot.jimple.ClassConstant
import soot.jimple.Constant
import soot.jimple.FieldRef
import soot.jimple.InstanceInvokeExpr
import soot.jimple.IntConstant
import soot.jimple.InvokeExpr
import soot.jimple.NewExpr
import soot.jimple.NullConstant
import soot.jimple.StaticFieldRef
import soot.jimple.Stmt
import wpds.impl.Weight
import java.util.*

class ForwardResults(private val forwardQueryResults: ForwardBoomerangResults<Weight.NoWeight>) {
    fun destructingStmt(): List<StmtSite> {
        val destructingStmtTable = forwardQueryResults.objectDestructingStatements
        return destructingStmtTable.rowKeySet().filter { it.unit.isPresent }.distinct().map { StmtSite(it.unit.get(), it.method) }
    }

    fun dataFlow(): List<DataFlowNode> {
        val dataFlow = forwardQueryResults.dataFlowPath
            .distinct()
            .map { DataFlowNode(StmtSite(it.stmt().unit.get(), it.stmt().method), it.fact().value()) }
        // Boomerang can produce incorrect results (overapproximation), use some heuristic to reduce the number of false statements
        val invokedMethods = forwardQueryResults.invokedMethodOnInstance.keys.map { StmtSite(it.unit.get(), it.method) }
        return dataFlow.filter { it.isFlowInArg() }
            .filter { it.stmtSite !in invokedMethods }
    }

    fun invokedMethods(): Set<StmtSite> {
        return forwardQueryResults.invokedMethodOnInstance.keys.map { StmtSite(it.unit.get(), it.method) }.toSet()
    }

    fun methodsTakingAsArg(): Set<StmtSite> {
        return forwardQueryResults.methodsTakingArg.keys.map { StmtSite(it.unit.get(), it.method) }.toSet()
    }
}

class BackwardResults(private val backwardQueryResults: BackwardBoomerangResults<Weight.NoWeight>) {

    fun aliases(): List<Val> {
        val aliases = backwardQueryResults.allAliases
        return aliases.map { it.base }
    }

    fun allocationSites(): Set<StmtSite> {
        val allocationSites = backwardQueryResults.allocationSites
        return allocationSites.keys.asSequence()
            .map { it.stmt() }
            .filter { it.unit.isPresent }
            .distinct()
            .map { StmtSite(it.unit.get(), it.method) }
            .toSet()
    }

}

data class ResolveVarQuery(val variable: Value, val stmtSite: StmtSite)

class ValueResolver(val sceneV: Scene, val icfg: InterproceduralCFGWrapper, val reset: Boolean = false) {
    companion object : KLogging()

    private var solver: Boomerang
    private var boomerangICFG = ObservableStaticICFG(BoomerangICFG(false))
    private val unitToAllocationSite = HashMap<ResolveVarQuery, Set<StmtSite>>()
    private val unitToInvokedMethods = HashMap<ResolveVarQuery, Set<StmtSite>>()
    private val unitToMethodsTakingAsArg = HashMap<ResolveVarQuery, Set<StmtSite>>()

    init {
        Objects.requireNonNull(sceneV)
        solver = instantiateSolver()
    }

    private fun instantiateSolver(): Boomerang {
        return object : Boomerang(FrontmatterBoomerangOptions()) {
            val observableICFG: ObservableICFG<soot.Unit, SootMethod> = boomerangICFG
//            val observableICFG: ObservableICFG<soot.Unit, SootMethod> = ObservableDynamicICFG(this)

            override fun icfg() = observableICFG

            override fun getSeedFactory() = null

            override fun createDebugger(): Debugger<Weight.NoWeight> = ConsoleDebugger()
        }
    }

    fun resolveInvokerBase(stmtSite: StmtSite, scope: Set<String>?): Set<StmtSite> {
        val stmt = stmtSite.stmt
        require(stmt.containsInvokeExpr()) { "Not an invoke statement: $stmt" }
        val invokeExpr = stmt.invokeExpr as InstanceInvokeExpr
        val baseBox = invokeExpr.baseBox
        return resolveVar(baseBox.value, stmtSite, scope)
    }

    /**
     * use Boomerang to find allocation site of arguments of invocation
     *
     * @param stmtSite  statement, which contains invocation expression
     * @param argId sequence number of a param to trace
     */
    fun resolveArg(stmtSite: StmtSite, argId: Int, scope: Set<String>?): Set<StmtSite> {
        val invokeExpr = stmtSite.stmt.invokeExpr
        val arg = invokeExpr.getArg(argId)
        if (arg is NullConstant) {
            logger.warn("Null argument in $stmtSite.stmt")
            return emptySet()
        }
        if (arg is Constant) {
            logger.warn("Constant $arg found in arguments of $stmtSite - the code is unreachable")
            return emptySet() //XXX:Stub! it can happen in unreachable methods
        }
        require(arg !is Constant) { "$arg is not constant @ $stmtSite" } // arg cannot be constant, as we use constant pre-transformation from boomerang
        return resolveVar(arg, stmtSite, scope)
    }

    private fun isVarReplacer(variable: Value): Boolean = variable.toString().startsWith("varReplacer")

    private fun getVarReplacer(variable: Value, stmtSite: StmtSite): StmtSite {
        val method = stmtSite.method
        var stmt = stmtSite.stmt
        val firstStmt = method.activeBody.units.first
        while (stmt != firstStmt) {
            stmt = method.activeBody.units.getPredOf(stmt) as Stmt
            if (stmt is AssignStmt && stmt.leftOp == variable) return StmtSite(stmt, method)
        }
        error("Illegal state")
    }

    fun resolveVar(variable: Value, stmtSite: StmtSite, scope: Set<String>?): Set<StmtSite> {
        if (isVarReplacer(variable)) {
            return setOf(getVarReplacer(variable, stmtSite))
        }
        val query = ResolveVarQuery(variable, stmtSite)
        if (!unitToAllocationSite.containsKey(query)) {
            resolveBackward(query)
        }
        val allocationSites = unitToAllocationSite[query]
        if (allocationSites == null || allocationSites.isEmpty()) {
            val simpleResolver = SimpleLocalValueResolver(sceneV, icfg, variable, stmtSite)
            return simpleResolver.resolveVar() // sometimes boomerang cannot resolve simple dataflow within one method
        }
        logger.info("All allocation sites of the query ($variable @$stmtSite) are: $allocationSites")
        return if (scope == null) allocationSites
        else allocationSites.filter { it.method.signature in scope }.toSet()
    }

    private fun resolveBackward(resolveQuery: ResolveVarQuery): Set<StmtSite> {
        val variable = resolveQuery.variable
        val stmtSite = resolveQuery.stmtSite
        val stmt = stmtSite.stmt
        val method = stmtSite.method
        logger.info("Searching $variable @ $stmt in ${method.signature}")
        val prevStmt = method.activeBody.units.getPredOf(stmt) as Stmt
        val query = BackwardQuery(Statement(prevStmt, method), Val(variable, method))
        if (reset)
            solver = instantiateSolver()
        try {
//            val forwardQuery = ForwardQuery(Statement(prevStmt, method), Val(variable, method))
//            val results = solver.backwardSolveUnderScope(query,forwardQuery, query.asNode())
            val results = solver.solve(query, true)
            val allocationSites = results.allocationSites
            val allocationStmts = allocationSites.keys.asSequence()
                .map { it.stmt() }
                .filter { it.unit.isPresent }
                .distinct()
                .map { StmtSite(it.unit.get(), it.method) }
                .toSet()
            unitToAllocationSite[resolveQuery] = allocationStmts
            return allocationStmts
        } catch (e: OutOfMemoryError) {
            logger.error("Boomerang failed with OutOfMemory")
        } catch (e: Throwable) {
            logger.error("Boomerang failed with ${e.message}")
        }
        unitToAllocationSite[resolveQuery] = emptySet()
        return emptySet()
    }


    fun resolveUses(variable: Value, stmtSite: StmtSite, scope: Set<String>?): Set<StmtSite> {
        logger.info("Searching methods taking ($variable @$stmtSite) as args")
        val query = ResolveVarQuery(variable, stmtSite)
        if (!unitToMethodsTakingAsArg.containsKey(query)) {
            resolveForward(query)
        }
        val methodsTakingAsArg = unitToMethodsTakingAsArg[query] ?: error("Cache miss")
        return if (scope == null) methodsTakingAsArg
        else {
            methodsTakingAsArg.filter { it.method.signature in scope }.toSet()
        }
    }

    private fun resolveForward(resolveQuery: ResolveVarQuery) {
        val variable = resolveQuery.variable
        val stmtSite = resolveQuery.stmtSite
        val stmt = stmtSite.stmt
        val method = stmtSite.method
        logger.info("Searching forward for statements $variable @ $stmt in ${method.signature}")
        val query = ForwardQuery(Statement(stmt, method), Val(variable, method))
        if (reset)
            solver = instantiateSolver()
        try {
            val forwardQueryResults = solver.solve(query)
            val invokedMethods = forwardQueryResults.invokedMethodOnInstance.keys.map { StmtSite(it.unit.get(), it.method) }.toSet()
            val methodsTakingArg = forwardQueryResults.methodsTakingArg.keys.map { StmtSite(it.unit.get(), it.method) }.toSet()
            unitToMethodsTakingAsArg[resolveQuery] = methodsTakingArg
            unitToInvokedMethods[resolveQuery] = invokedMethods
//            return ForwardResults(forwardQueryResults)
        } catch (e: OutOfMemoryError) {
            logger.error("Boomerang failed with OutOfMemory")
            unitToMethodsTakingAsArg[resolveQuery] = emptySet()
            unitToInvokedMethods[resolveQuery] = emptySet()
        } catch (e: Throwable) {
            logger.error("Boomerang failed with ${e.message}")
            unitToMethodsTakingAsArg[resolveQuery] = emptySet()
            unitToInvokedMethods[resolveQuery] = emptySet()
        }
    }


    /**
     * is used to trace forward variable usage (if it is an argument of a call of interest), e.g. if
     * */
//    fun resolveForwardDataflowToArgsStmt(variable: Value, stmtSite: StmtSite): List<DataFlowNode> {
//        logger.info("Searching dataflows $variable @$stmtSite")
//        val results = resolveForward(variable, stmtSite) ?: return emptyList()
//        val dataFlow = results.dataFlow()
//        val invokedMethods = results.invokedMethods()
//        val filteredDataFlows = dataFlow.filter { it.isFlowInArg() }
//            .filter { it.stmtSite !in invokedMethods }
//        logger.info("All dataFlow sites of the query ($variable @$stmtSite) are: $dataFlow")
//        return filteredDataFlows
//    }

    fun resolveInvokedMethods(variable: Value, stmtSite: StmtSite, scope: Set<String>?): Set<StmtSite> {
        logger.info("Searching invoked methods $variable @$stmtSite")
        val query = ResolveVarQuery(variable, stmtSite)
        if (!unitToInvokedMethods.containsKey(query)) {
            resolveForward(query)
        }
        val invokedMethods = unitToInvokedMethods[query] ?: error("Cache miss")
        return if (scope == null) invokedMethods
        else invokedMethods.filter { it.method.signature in scope }.toSet()
    }

}

/**
 * local intraprocedural value resolver
 * */
private class SimpleLocalValueResolver(val sceneV: Scene, val icfg: InterproceduralCFGWrapper, val variable: Value, val stmtSite: StmtSite) {
    companion object : KLogging()

    fun resolveVar(): Set<StmtSite> {
        val stmt = stmtSite.stmt
        val method = stmtSite.method
        logger.trace("Searching $variable @ $stmt locally in ${method.signature}")
        return propagate(stmt, variable, method)
    }

    private fun propagate(stmt: Stmt, fact: Value, method: SootMethod): Set<StmtSite> {
        val workList = mutableListOf<Pair<Stmt, Value>>()
        val visitedNodes = mutableSetOf(stmt)
        val allocationStmts = mutableSetOf<StmtSite>()
        workList.add(Pair(stmt, fact))
        while (workList.isNotEmpty()) {
            val (node, value) = workList.removeAt(0)
            visitedNodes.add(node)
            if (icfg.getMethodOf(node) != stmtSite.method)
                continue
            var nextValue = value
            var found = false
            if (node is AssignStmt && value.equivTo(node.leftOp)) {
                if (isAllocationVal(node)) {
                    allocationStmts.add(StmtSite(node, method))
                    found = true
                } else {
                    nextValue = when (val rigthOp = node.rightOp) {
                        is CastExpr -> rigthOp.op
                        is FieldRef -> rigthOp
                        is InvokeExpr -> {
                            if (rigthOp.argCount == 1 && (sceneV.implements(value.type, rigthOp.getArg(0).type) || sceneV.implements(rigthOp.getArg(0).type, value.type)))
                                rigthOp.getArg(0) // heuristic
                            else
                                if (rigthOp is InstanceInvokeExpr && (sceneV.implements(value.type, rigthOp.base.type) || sceneV.implements(rigthOp.base.type, value.type)))
                                    rigthOp.base
                                else rigthOp
                        }
                        else -> rigthOp
                    }
                }
            }
            if (!found) {
                icfg.getPredsOf(node)
                    .filterIsInstance<Stmt>()
                    .filterNot { it in visitedNodes }
                    .filterNot { icfg.getMethodOf(it) == stmtSite.method }
                    .forEach {
                        workList.add(Pair(it, nextValue))
                    }
            }

        }
        logger.trace("Found $allocationStmts")
        return allocationStmts
    }

    private fun isAllocationVal(stmt: Stmt): Boolean {
        if (stmt !is AssignStmt)
            return false
        if (stmt.containsInvokeExpr()) {
            val callee = stmt.invokeExpr.method
            // signatures
            when (callee.subSignature) {
//                in getFragmentManager,
//                in fragmentGetActivity,
                in inflatorSubsignatures ->
                    return true
            }
            val value = stmt.rightOp
            if (value is ClassConstant) return true
            if (value is IntConstant) return true
            if (value is StaticFieldRef) {
                if (Modifier.isFinal(value.field.modifiers))
                    return true
            }
            if (value is NullConstant) return true
            if (value is NewExpr) return true
        }
        return false
    }
}

@Deprecated("no use of dataflows")
data class DataFlowNode(val stmtSite: StmtSite, val variable: Value) {
    fun isFlowInArg(argId: Int = 0): Boolean {
        if (!stmtSite.containsInvokeExpr()) return false
        val invokeExpr = stmtSite.getInvokeExpr()
        if (invokeExpr.argCount == 0) return false
        val argVal = invokeExpr.getArg(argId)
        return argVal == variable

    }
}
