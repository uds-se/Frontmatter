package saarland.cispa.frontmatter.resolvers

import saarland.cispa.frontmatter.ApkInfo
import saarland.cispa.frontmatter.UIFactory
import saarland.cispa.frontmatter.Utils.isAndroidMethod
import saarland.cispa.frontmatter.Utils.isSensitiveSystemMethod
import saarland.cispa.frontmatter.cg.InterproceduralCFGWrapper
import saarland.cispa.frontmatter.data.StmtSite
import saarland.cispa.frontmatter.results.ApiCall
import saarland.cispa.frontmatter.results.DialogButton
import saarland.cispa.frontmatter.results.SimpleLayoutUIElement
import saarland.cispa.frontmatter.results.SimpleUIElement
import soot.IntType
import soot.Local
import soot.Scene
import soot.SootMethod
import soot.Value
import soot.jimple.AssignStmt
import soot.jimple.ConditionExpr
import soot.jimple.EqExpr
import soot.jimple.IdentityStmt
import soot.jimple.IfStmt
import soot.jimple.IntConstant
import soot.jimple.InvokeExpr
import soot.jimple.InvokeStmt
import soot.jimple.LookupSwitchStmt
import soot.jimple.NeExpr
import soot.jimple.ParameterRef
import soot.jimple.StaticFieldRef
import soot.jimple.Stmt
import soot.tagkit.IntegerConstantValueTag
import soot.toolkits.graph.Block
import soot.toolkits.graph.ExceptionalBlockGraph
import soot.toolkits.graph.ExceptionalUnitGraph

class UIApiResolver(
    sceneV: Scene, icfg: InterproceduralCFGWrapper, valueResolver: ValueResolver,
    apkInfo: ApkInfo, private val uiFactory: UIFactory
) : ApiResolver(sceneV, icfg, valueResolver, apkInfo) {

    private val stringResolver = StringResolver(valueResolver, resourceParser)
    private val intentResolver = IntentResolver(sceneV, valueResolver, emptyMap(), resourceParser) // pass empty intentFilters as it's not relevant for services

    fun resolve(layoutUiElements: List<SimpleUIElement>) {
        for (uiElement in layoutUiElements) {
            uiElement.listeners.let {
                when (uiElement) {
                    is SimpleLayoutUIElement -> it.flatMapTo(uiElement.apis) { listener -> resolveApiForCallback(sceneV.getMethod(listener), uiElement.id) }
                    is DialogButton -> it.filter { listener -> listener.isNotBlank() }.flatMapTo(uiElement.apis) { listener -> resolveApiForCallback(sceneV.getMethod(listener)) }
                }
            }
        }
    }

    private fun getViewIdFromCondition(condition: Value?, method: SootMethod): Int? {
        if (condition !is ConditionExpr) return null
        val op1 = condition.op1
        val op2 = condition.op2
        return retrieveId(op2, method) ?: retrieveId(op1, method)
    }

    /**
     * @param value: value from if condition or lookup table
     */
    private fun retrieveId(value: Value, method: SootMethod): Int? {
        if (value.type !is IntType) return null
        if (value is IntConstant && isId(value)) return value.value
        // try to resolve value (simple intraprocedural resolution)
        // due to special soot mode used variables have unique names (#not allways)
        val assignedValueStmt = method.activeBody.units
            .filterIsInstance<AssignStmt>()
            .singleOrNull { it.leftOp == value } ?: return null // can be function call which we don't process now
        return when (val assignedValue = assignedValueStmt.rightOp) {
            is IntConstant -> assignedValue.value
            is StaticFieldRef -> resolveIntStaticField(assignedValue)
            else -> null
        }
    }

    /**
     * use heuristic: any condition in if statement or lookupswitch that contain target viewId is considered relevant
     * but only in top callback method, so we can overapproximate if it is propagated further
     */
    private fun resolveApiForCallback(callback: SootMethod, viewId: Int): List<ApiCall> {
        val calledBlocks = resolveCalledBlocks(callback, viewId)
        return calledBlocks.flatMap { resolveApiFromBlock(it, callback) }
//        val invokedMethods = valueResolver.resolveInvokedMethods(viewParamStmt.leftOp, StmtSite(viewParamStmt, callback), null)
//        val getIdMethod = invokedMethods.firstOrNull { it.getInvokeExprSubsignature() == "int getId()" }
//        require(getIdMethod != null)
//        val viewIdLocal = (getIdMethod.stmt as AssignStmt).leftOp
//        val resolveUses = valueResolver.resolveUses(viewIdLocal, getIdMethod, null)
    }

    private fun resolveApiFromBlock(block: Block, method: SootMethod): List<ApiCall> {
        val seeds = block.flatMap { icfg.getCalleesOfCallAt(it) }

        val androidApis = block
            .filterIsInstance<Stmt>()
            .mapNotNull {
                when {
                    it is InvokeStmt -> StmtSite(it, method) //it.getInvokeExpr().method)
                    it is AssignStmt && (it.containsInvokeExpr()) -> StmtSite(it, method) //it.invokeExpr.method)
                    else -> null
                }
            }
            .filter { it.getInvokeExpr().method.isAndroidMethod() || it.getInvokeExpr().method.isSensitiveSystemMethod() }
            .toMutableList()
        androidApis.addAll(
            getReachableApi(seeds, false)
        )
        return androidApis.map { addApiMeta(it) }
    }

    private fun resolveCalledBlocks(callback: SootMethod, viewId: Int): MutableList<Block> {
        val unitGraph = icfg.getOrCreateUnitGraph(callback) as ExceptionalUnitGraph
        //        val defs = SimpleLocalDefsNoSSA(unitGraph)
        val blockGraph = ExceptionalBlockGraph(unitGraph)
        val viewParamStmt = getFirstParameterStmt(callback) //FIXME can be null
        require(viewParamStmt != null) { "no ParameterStmt found in callback $callback" }
        val workList = mutableListOf<Block>(blockGraph.blocks[0])
        val seenBlocks = mutableListOf<Block>()
        val calledBlocks = mutableListOf<Block>()
        while (workList.isNotEmpty()) {
            val block = workList.removeAt(0)
            if (block in seenBlocks) continue
            seenBlocks.add(block)
            calledBlocks.add(block)
            when {
                isNextIfBlock(block) -> {
                    val conditionStmt = block.tail as IfStmt
                    val targetBlock = block.succs.first { it.head == conditionStmt.target }
                    val elseBlock = block.succs.firstOrNull { it.head != conditionStmt.target }
                    val condition = conditionStmt.condition as? ConditionExpr
                    val conditionId = getViewIdFromCondition(condition, callback)
                    if (conditionId == null) {
                        workList.addAll(block.succs)
                    } else {
                        when {
                            conditionId == viewId && condition is EqExpr -> workList.add(targetBlock)
                            conditionId != viewId && condition is EqExpr && elseBlock != null -> workList.add(elseBlock)
                            conditionId == viewId && condition is NeExpr && elseBlock != null -> workList.add(elseBlock)
                            conditionId != viewId && condition is NeExpr -> workList.add(targetBlock)
                            else -> workList.addAll(block.succs)
                        }
                    }
                }
                isNextLookupBlock(block) -> {
                    val lookupStmt = block.tail as LookupSwitchStmt
                    val targetId = lookupStmt.lookupValues.indexOfFirst { it.value == viewId }
                    if (targetId != -1) {
                        val targetBlock = block.succs.first { it.head == lookupStmt.getTarget(targetId) }
                        workList.add(targetBlock)
                    } else {
                        // check if this lookup switch operates on id like ints
                        // if yes, follow default branch, else follow all branches
                        if (lookupStmt.lookupValues.any { isId(it) }) {
                            val targetBlock = block.succs.first { it.head == lookupStmt.defaultTarget }
                            workList.add(targetBlock)
                        } else {
                            workList.addAll(block.succs)
                        }
                    }
                }
                else -> {
                    workList.addAll(block.succs)
                }
            }
        }
        return calledBlocks
    }

    private fun isId(value: IntConstant) = uiFactory.isId(value.value)

    private fun isNextIfBlock(block: Block): Boolean {
        return block.tail is IfStmt
    }

    private fun isNextLookupBlock(block: Block): Boolean {
        return block.tail is LookupSwitchStmt
    }

    private fun getFirstParameterStmt(callback: SootMethod): IdentityStmt? {
        return callback.activeBody.units.firstOrNull { it is IdentityStmt && (it.rightOp is ParameterRef) } as? IdentityStmt
    }

    /**
     * simple heuristic, search for values in static class initializer
     * take only the first value
     */
    private fun resolveIntStaticField(fieldRef: StaticFieldRef): Int? {
        if (fieldRef.field.hasTag("IntegerConstantValueTag"))
            return (fieldRef.field.getTag("IntegerConstantValueTag") as IntegerConstantValueTag).intValue
        val declaringClass = fieldRef.field.declaringClass
        if (declaringClass.declaresMethod("void <clinit>()")) {
            val clinit = declaringClass.getMethod("void <clinit>()")
            val fieldAllocation = clinit.activeBody.units
                .filterIsInstance<AssignStmt>()
                //.lastOrNull { (it.leftOp as StaticFieldRef).field == fieldRef.field }
                .lastOrNull { it.leftOp.toString() == fieldRef.toString() } ?: return null
            if (fieldAllocation.rightOp is Local && fieldAllocation.rightOp.toString().startsWith("varReplacer")) {
                val varReplacerStmt = clinit.activeBody.units.getPredOf(fieldAllocation) as? AssignStmt
                return (varReplacerStmt?.rightOp as? IntConstant)?.value
            }
            if (fieldAllocation.rightOp is InvokeExpr)
                return null // int from method call
        }
        return null
    }

}

