package saarland.cispa.frontmatter

import mu.KLogging
import saarland.cispa.frontmatter.Utils.implements
import saarland.cispa.frontmatter.Utils.isAndroidMethod
import saarland.cispa.frontmatter.Utils.isDummy
import saarland.cispa.frontmatter.data.Dialog
import saarland.cispa.frontmatter.data.Menu
import saarland.cispa.frontmatter.data.StmtSite
import saarland.cispa.frontmatter.data.UIElement
import saarland.cispa.frontmatter.data.UIViewElement
import saarland.cispa.frontmatter.resolvers.ValueResolver
import saarland.cispa.frontmatter.resolvers.ViewId
import soot.Body
import soot.Kind
import soot.Local
import soot.RefType
import soot.Scene
import soot.SootClass
import soot.SootMethod
import soot.Unit
import soot.Value
import soot.jimple.AssignStmt
import soot.jimple.BinopExpr
import soot.jimple.ConditionExpr
import soot.jimple.EqExpr
import soot.jimple.IfStmt
import soot.jimple.IntConstant
import soot.jimple.InvokeStmt
import soot.jimple.LookupSwitchStmt
import soot.jimple.NeExpr
import soot.jimple.NewExpr
import soot.jimple.Stmt
import soot.jimple.toolkits.callgraph.Edge
import soot.toolkits.graph.Block
import soot.toolkits.graph.ExceptionalBlockGraph
import soot.toolkits.graph.ExceptionalUnitGraph
import soot.toolkits.graph.UnitGraph
import soot.toolkits.scalar.SimpleLocalDefs
import soot.toolkits.scalar.SimpleLocalDefsNoSSA
import soot.util.HashMultiMap
import soot.util.MultiMap

data class BlockEdge(val src: Block, val tgt: Block)
data class UIElementWithContext(val view: UIElement, val context: ActivityOrFragmentClass)
class CallchainWalker(val sceneV: Scene, val appModel: AppModel, val valueResolver: ValueResolver) {
    companion object : KLogging()

    val icfg = valueResolver.icfg
    val callgraph = icfg.callGraph
    private val viewClass = sceneV.getSootClass("android.view.View")
    private val menuClass = sceneV.getSootClass("android.view.MenuItem")
    private val uiCallbackMethods: MultiMap<String, SootMethod>

    private val listenersToUI: MultiMap<SootMethod, UIElementWithContext> = HashMultiMap()

    init {
        for ((activity, layout) in appModel.activityLayouts) {
            collectLayoutListeners(layout.uiViewElement, activity)
        }
        for ((activity, layout) in appModel.orphanedFragmentLayouts) {
            collectLayoutListeners(layout, activity)
        }
        for ((activity, menu) in appModel.activityMenus) {
            collectMenuListeners(menu, activity)
        }
        for ((activity, dialog) in appModel.activityDialogs) {
            collectDialogListeners(dialog, activity)
        }
        uiCallbackMethods = listenersToUI.keySet().map { it.subSignature to it }.toMultiMap()
    }

    private fun collectLayoutListeners(layout: UIViewElement, activity: ActivityClass) {
        val uiElements = layout.getAllChildrenFlatten()
        for (uiElement in uiElements) {
            uiElement.listeners.forEach { listenersToUI.put(it, UIElementWithContext(uiElement, activity)) }
        }
    }

    private fun collectMenuListeners(menu: Menu, activity: ActivityClass) {
        val menuItems = menu.getAllItems()
        for (menuItem in menuItems) {
            if (menu.listener != null)
                listenersToUI.put(menu.listener, UIElementWithContext(menuItem, activity))
        }
    }

    private fun collectDialogListeners(dialog: Dialog, activity: ActivityClass) {

        val buttons = dialog.buttons
        for (button in buttons) {
            button.listener.forEach { listenersToUI.put(it, UIElementWithContext(button, activity)) }
        }
    }

    /**
     * resolve UI view which triggers stmtSite call
     * */
    fun getUITriggerOfCall(stmtSite: StmtSite): Set<UIElementWithContext> {
        val callbacks = traceToCallback(stmtSite) // get a callback
        return callbacks.flatMap { resolveViewForCallback(it) }.toSet()
    }

    /**
     * identify the view to which this callback belongs
     * */
    private fun resolveViewForCallback(callbackSite: StmtSite): List<UIElementWithContext> {
        // 2) check if there is condition or lookup table with viewId
        val (targetViewId, excludedIds) = findViewIdsInConditions(callbackSite)
        // 3) bind callback with UI
        val callbackMethod = callbackSite.method //TODO: interfaces and inheritance
        val uiElements = listenersToUI[callbackMethod]
        if (uiElements.isEmpty()) logger.warn("Missing ui element for $callbackMethod")
        return if (targetViewId == 0)
            uiElements.filterNot { it.view.id in excludedIds }
        else
            uiElements.filter { it.view.id == targetViewId }
    }

    private fun findViewIdsInConditions(callbackSite: StmtSite): Pair<Int, Set<Int>> {
        val unitGraph = icfg.getOrCreateUnitGraph(callbackSite.method) as ExceptionalUnitGraph
        val defs = SimpleLocalDefsNoSSA(unitGraph)
        val blockGraph = ExceptionalBlockGraph(unitGraph)
        val block = getTargetBlock(blockGraph, callbackSite.stmt)
        return goAroundBlockGraph(block, defs)
    }

    /**
     * identify the callback method from which the stmtSite call occured
     * traverse the callgraph from stmtSite call till the callback method
     * */
    private fun traceToCallback(stmtSite: StmtSite): Set<StmtSite> {
        val method = stmtSite.method
        if (isCallback(method)) {
            return setOf(stmtSite)
        }
        val workList = mutableListOf<SootMethod>()
        val visitedNodes = mutableSetOf(method)
        val callbackNodes = mutableSetOf<StmtSite>()
        workList.add(method)
        visitedNodes.add(method)
        while (workList.isNotEmpty()) {
            val node = workList.removeAt(0)
            visitedNodes.add(node)
            val edges = callgraph.edgesInto(node)
                .asSequence()
                .filterNot { it.src().isAndroidMethod() }
                .filterNot { it.src().isDummy() }
                .filterNot { it.src() in visitedNodes }
                .filterNot { it.src() in workList }
                .toSet()
            edges.forEach {
                if (isCallback(it.src())) {
                    callbackNodes.add(StmtSite(it.srcStmt(), it.src()))
                } else {
                    workList.add(it.src())
                }
            }
        }
        return callbackNodes
    }

    private fun isCallback(method: SootMethod): Boolean {
        return method.subSignature in uiCallbackMethods
    }

    /**
     * return blocks that contain the callee
     * */
    private fun getTargetBlocks(blockGraph: ExceptionalBlockGraph, callee: SootMethod): List<Block> {
        return blockGraph.blocks.filter { isInBlock(it, callee) }
    }

    /**
     * return block that contain the stmt
     * */
    private fun getTargetBlock(blockGraph: ExceptionalBlockGraph, stmt: Stmt): Block {
        return blockGraph.blocks.first { isInBlock(it, stmt) }
    }

    /**
     * check if the statement is in the body of a block
     * */
    private fun isInBlock(block: Block, stmt: Unit): Boolean {
        return block.iterator().asSequence()
            .any { it == stmt }

    }

    /**
     * check if the callee is in the body of a block
     * */
    private fun isInBlock(block: Block, callee: SootMethod): Boolean {
        return block.iterator().asSequence()
            .filterIsInstance<Stmt>()
            .filter { it.containsInvokeExpr() }
            .any { it.invokeExpr.method == callee }
    }

    /**
     * FIXME: check the purpose
     * callback can be assignet to multiple views
     * expect view resolution by view id
     * check if viewId is encountered in if conditions
     * XXX: refactor, it can overapproximate
     * */
    private fun isMultiCallback(callback: SootMethod): Boolean {
        val body = callback.activeBody
        val viewIdStmts = getViewIdStmt(body)
        val viewIdVars = viewIdStmts.map { it.leftOp }
//        if (viewIdIsArg(body)) return false // TODO: propagate one level down
        return body.units.asSequence()
            .filterIsInstance<IfStmt>() // TODO: add lookup switch
            .map { it.condition }
            .filterIsInstance<BinopExpr>()
            .filter { it is EqExpr || it is NeExpr }
            .flatMap { sequenceOf(it.op1, it.op2) }
            .any { it in viewIdVars }
    }

    //FIXME: check
    fun traceToViewId(callback: SootMethod, method: SootMethod): Set<Int> {
        if (!isMultiCallback(callback))
            return setOf(0)
        val unitGraph = icfg.getOrCreateUnitGraph(callback) as ExceptionalUnitGraph
        val defs = SimpleLocalDefsNoSSA(unitGraph)
        val blockGraph = ExceptionalBlockGraph(unitGraph)
        val blocks = getTargetBlocks(blockGraph, method)
        return blocks.map { goAroundBlockGraph(it, defs).first }.toSet()
    }

    /**
     * identify viewId from findviewById by tracing back from set###Listener
     * */
    private fun findViewId(edgeToAssignListenerSite: Edge, callbackClass: SootClass): List<ViewId> {
        val clickStmt = edgeToAssignListenerSite.srcStmt()
        val clickMethod = edgeToAssignListenerSite.src()
        if (edgeToAssignListenerSite.kind().isInterface()) { //XXX: specialInvoke may be required as well
            // resolve by type if possible
            val callingContextSites = valueResolver.resolveInvokerBase(StmtSite(clickStmt, clickMethod), null)
            val callingContext = callingContextSites
                .filter { (((it.stmt as? AssignStmt)?.rightOp as? NewExpr)?.type as? RefType)?.sootClass == callbackClass }
                .toSet()
            logger.info("${callingContext.size}")
            //TODO: complete
        }
        val invokeExpr = clickStmt.invokeExpr
        val view = invokeExpr.args
            .filter { it.type is RefType }
            .firstOrNull() { sceneV.implements((it.type as RefType).sootClass, viewClass) } ?: return emptyList()
        val viewAllocation = valueResolver.resolveVar(view, StmtSite(clickStmt, clickMethod), null)
        val viewNewExpr = viewAllocation.filter { it.stmt is AssignStmt }
            .firstOrNull { (it.stmt as AssignStmt).rightOp is NewExpr } //XXX: we assume there is only one newExpression (there may be no new Expr)
        if (viewNewExpr == null) {
            logger.warn("BindCallback: Missing newExpr for ${viewAllocation.joinToString("; ")}")
            return emptyList()
        }
//        val findViewStmt = icfg.getPredsOf(viewNewExpr.stmt).first() // XXX: is it safe to use body instead of control flow graph
        val findViewStmt = viewNewExpr.method.activeBody.units.getPredOf(viewNewExpr.stmt) as Stmt // findViewById should be right before newExpr
        if (!(findViewStmt.containsInvokeExpr() && findViewStmt.invokeExpr.method.name == FIND_VIEW_METHOD_NAME)) {
            logger.warn("BindCallback: Could not find findViewById call for $viewNewExpr, found: ${findViewStmt}")
            return emptyList()
        }
        TODO("remove")
    }

    //XXX: No we consider only cases when a View.getId is called directly
    // we fail if viewId is a method parameter
    /**
     * walk back the block graph from targetBlock to the entry point of the method, collect conditions which contain viewIds
     * @return a pair of <viewId, set of ids which should be excluded>
     * */
    private fun goAroundBlockGraph(targetBlock: Block, localDefs: SimpleLocalDefsNoSSA): Pair<Int, Set<Int>> {
        val workList = mutableListOf<BlockEdge>()
        val visitedList = mutableListOf(targetBlock)
        val excludedIds = mutableSetOf<Int>()
        workList.addAll(targetBlock.preds.map { BlockEdge(it, targetBlock) })
        while (workList.isNotEmpty()) {
            val currentBlockEdge = workList.removeAt(0)
            val currentBlock = currentBlockEdge.src
            visitedList.add(currentBlock)
            when (val tail = currentBlock.tail) { //look at the last stmt of a block
                is IfStmt -> {//check if condition contains viewId reference
                    val condition = tail.condition
                    if (condition is ConditionExpr) {
                        val leftSideIsViewId = isViewId(condition.op1, tail, localDefs)
                        val rightSideIsViewId = isViewId(condition.op2, tail, localDefs)
                        if (leftSideIsViewId || rightSideIsViewId) {
                            val (_, viewId) = getViewIdFromCondition(tail.condition)
                            if (isEqBranchBlock(tail, currentBlockEdge.tgt) && viewId != null) {
                                return Pair(viewId, excludedIds) //XXX: we assume this is a hit and don't propagate further
                            }
                            viewId?.let { excludedIds.add(it) } // update list of ViewIds that don't trigger that block
                        }
                    }
                    // else propagate further
                    workList.addAll(currentBlock.preds.filter { it !in visitedList }.map { BlockEdge(it, currentBlock) })
                }
                is LookupSwitchStmt -> {
                    if (isViewId(tail.key, tail, localDefs)) {

                        val viewId = tail.targets.withIndex()
                            .filter { (_, target) -> target == currentBlockEdge.tgt.head }
                            .map { (index, _) -> tail.getLookupValue(index) }
                            .firstOrNull()
                        val lookupValues = tail.lookupValues.map { it.value }
                        excludedIds.addAll(lookupValues)
                        excludedIds.remove(viewId)
                        if (viewId != null)
                            return Pair(viewId, excludedIds)
                        // else we hit default branch of lookupStmt
                    }
                    workList.addAll(currentBlock.preds.filter { it !in visitedList }.map { BlockEdge(it, currentBlock) })
                }
                else -> // take all blocks, propagate up
                    workList.addAll(currentBlock.preds.filter { it !in visitedList }.map { BlockEdge(it, currentBlock) })
            }
        }
        return Pair(0, excludedIds)
    }


    private fun isDecidingCondition(condition: Value, viewIdVars: List<Value>): Boolean {
        return (condition is EqExpr || condition is NeExpr) && (condition is BinopExpr) && (condition.op1 in viewIdVars || condition.op2 in viewIdVars)
    }

    /**
     * check if the value is initialized with View.getId method
     * */
    private fun isViewId(value: Value, unit: Unit, localDefs: SimpleLocalDefs): Boolean {
        if (value !is Local)
            return false
        val defsOfCondition = localDefs.getDefsOfAt(value, unit)
        return defsOfCondition.filterIsInstance<AssignStmt>()
            .filter { it.containsInvokeExpr() }
            .any { it.invokeExpr.method.signature in getIdSignatures }
    }

    /**
     * we assume that there is just one getId call inside onClick(View)
     * which is reasonable but may not hold TODO: support for multiple getId()
     * */
    private fun getViewIdStmt(body: Body): List<AssignStmt> {
        return body.units.asSequence()
            .filterIsInstance<AssignStmt>()
            .filter { it.containsInvokeExpr() }
            .filter { it.invokeExpr.method.signature in getIdSignatures }
            .toList()
    }

    @Deprecated("unused")
    fun resolveCallbackUnitsById(callback: SootMethod, viewId: Int): List<Unit> {
        val body = callback.activeBody
        var viewVar = getViewParam(body)
        if (viewVar == null) {
            logger.warn("No appropriate param found in $callback")
            return emptyList()
        }
        val viewIdVal = IntConstant.v(viewId)
        val unitGraph = icfg.getOrCreateUnitGraph(callback) as UnitGraph
        val entryUnits = mutableListOf<Unit>()
        val visitedUnits = mutableSetOf<Unit>()
        val walkList = mutableListOf<Unit>(unitGraph.heads.single())

        while (walkList.isNotEmpty()) {
            val unit = walkList.removeAt(0)
            if (unit in visitedUnits) continue
            visitedUnits.add(unit)
            if (unit is AssignStmt && unit.containsInvokeExpr())
                if (unit.invokeExpr.method.signature in getIdSignatures) {
                    viewVar = unit.leftOp as Local
                } else {
                    entryUnits.add(unit)
                }
            if (unit is InvokeStmt) entryUnits.add(unit)
            val succs = unitGraph.getSuccsOf(unit)
            if (unit is IfStmt) {
                val condition = unit.condition
                val ifTarget = unit.target
                if (condition is EqExpr && (condition.op1 == viewVar || condition.op2 == viewVar)) {
                    if (condition.op1 == viewIdVal || condition.op2 == viewIdVal) {
                        walkList.add(ifTarget)
                        continue
                    } else {
                        if (condition.op1 is IntConstant || condition.op2 is IntConstant) { //exclude only branches which for sure are for other views, otherwise overapproximate
                            succs.filterNotTo(walkList) { it == ifTarget } //add to walklist
                            continue
                        }
                    }
                }
            }
            walkList.addAll(succs)
        }
        return entryUnits
    }

    fun getReachableAndroidMethods(root: Unit): Set<SootMethod> {
        val rootMethod = callgraph.edgesOutOf(root).asSequence().single().tgt()
        val visitedMethods = mutableSetOf<SootMethod>()
        val reachableAndroidMethods = mutableSetOf<SootMethod>()
        val walkList = mutableListOf<SootMethod>(rootMethod)
        while (walkList.isNotEmpty()) {
            val method = walkList.removeAt(0)
            visitedMethods.add(method)
            val callees = callgraph.edgesOutOf(method).asSequence()
                .map { it.tgt() }
                .filterNot { it in visitedMethods }
            callees.filterNotTo(walkList) { it.isAndroidMethod() }
            callees.filterTo(reachableAndroidMethods) { it.isAndroidMethod() }
        }
        return reachableAndroidMethods
    }

    /**
     * Entry method can be:
     * - a callback
     * - a call method of Runnable (or similar async class, like doInBackground) (unless we patched them)
     * */
    @Deprecated("unused")
    fun getEntryMethods(stmtSite: StmtSite): List<SootMethod> {
        val method = stmtSite.method
        if (isEntry(method)) {
            return listOf(method)
        }
        val entryMethods = mutableListOf<SootMethod>()
        val visitedMethods = mutableSetOf<SootMethod>() //prevent recursion
        val walkList = mutableListOf<SootMethod>(method)
        while (walkList.isNotEmpty()) {
            val tgt = walkList.removeAt(0)
            visitedMethods.add(tgt)
            val callers = callgraph.edgesInto(tgt).asSequence()
                .map { it.src() }
                .filterNot { it.isDummy() }
                .filterNot { it.isAndroidMethod() }
                .filterNot { it in visitedMethods }
                .toList()
            callers.filterTo(entryMethods) { isEntry(it) }
            walkList.addAll(callers)
        }
        return entryMethods
    }

    /**
     * Entry method can be:
     * - a callback
     * - a call method of Runnable (or similar async class, like doInBackground) (unless we patched them)
     * */
    fun getCallersAlongCallchain(stmtSite: StmtSite, isTarget: (SootClass) -> Boolean): List<SootClass> {
        val method = stmtSite.method
        if (isTarget(method.declaringClass) && !method.isStatic) {
            return listOf(method.declaringClass)
        }
        val entryMethods = mutableListOf<SootMethod>()
        val visitedMethods = mutableSetOf<SootMethod>() //prevent recursion
        val walkList = mutableListOf<SootMethod>(method)
        while (walkList.isNotEmpty()) {
            val tgt = walkList.removeAt(0)
            visitedMethods.add(tgt)
            val callers = callgraph.edgesInto(tgt).asSequence()
                .map { it.src() }
                .filterNot { it.isDummy() }
                .filterNot { it.isAndroidMethod() }
                .filterNot { it in visitedMethods }
                .toList()
            val targetCallers = callers.filter { isTarget(it.declaringClass) }.map { it.declaringClass }
            if (targetCallers.isNotEmpty()) return targetCallers //FIXME
            callers.filterTo(entryMethods) { isEntry(it) }
            callers.filterNotTo(walkList) { it.isDummy() }
        }
        return emptyList()
    }

    private fun isEntry(m: SootMethod): Boolean {
        return callgraph.edgesInto(m).asSequence()
            .map { it.src() }
            .filterNot { it.isDummy() }
            .none()
    }

    /**
     * get local associated with View param of the method
     * */
    private fun getViewParam(body: Body): Local? {
        return body.parameterLocals
            .firstOrNull {
                val type = it.type as? RefType
                if (type != null)
                    sceneV.implements(type.sootClass, viewClass) || sceneV.implements(type.sootClass, menuClass) else false
            }
    }

    private fun isEqBranchBlock(ifStmt: IfStmt, nextBlock: Block): Boolean {
        val condition = ifStmt.condition
        if (condition !is EqExpr && condition !is NeExpr) return false
        val target = ifStmt.target
        return target == nextBlock.head
    }

    private fun getBlockBranches(block: Block): Pair<Block, Block> {
        val tail = block.tail
        require(tail is IfStmt)
        val condition = tail.condition
        require(condition is EqExpr || condition is NeExpr)
        val nextBlocks = block.succs
        val target = tail.target
        return when (condition) {
            is EqExpr -> Pair(nextBlocks.first { it.head == target }, nextBlocks.first { it.head != target })
            is NeExpr -> Pair(nextBlocks.first { it.head != target }, nextBlocks.first { it.head == target })
            else -> error("Unreachable state")
        }
    }

    /**
     * parse condition and return <viewIdVariable, int value> pair
     * currently supporting constant integers only
     * */
    private fun getViewIdFromCondition(condition: Value): Pair<Value?, Int?> {
        require(condition is ConditionExpr)
        val op1 = condition.op1
        val op2 = condition.op2
        if (op1 is IntConstant) //FIXME: add staticFieldRef
            return Pair(op2, op1.value)
        if (op2 is IntConstant)
            return Pair(op1, op2.value)
        return Pair(null, null)

    }
}

private fun Kind.isInterface(): Boolean = this == Kind.INTERFACE
