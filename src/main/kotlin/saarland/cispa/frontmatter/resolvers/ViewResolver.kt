package saarland.cispa.frontmatter.resolvers

import mu.KLogging
import saarland.cispa.frontmatter.ActivityClass
import saarland.cispa.frontmatter.ActivityOrFragmentClass
import saarland.cispa.frontmatter.AppModel
import saarland.cispa.frontmatter.Attr
import saarland.cispa.frontmatter.Resolver
import saarland.cispa.frontmatter.ResourceParser
import saarland.cispa.frontmatter.Utils.getIntConstant
import saarland.cispa.frontmatter.Utils.getStringConstant
import saarland.cispa.frontmatter.Utils.implements
import saarland.cispa.frontmatter.Utils.isActivity
import saarland.cispa.frontmatter.Utils.isAndroidClass
import saarland.cispa.frontmatter.Utils.isAndroidMethod
import saarland.cispa.frontmatter.Utils.isFragment
import saarland.cispa.frontmatter.WRONG_STRING
import saarland.cispa.frontmatter.XmlLayoutAnalysis
import saarland.cispa.frontmatter.adapterMethodSubsignatures
import saarland.cispa.frontmatter.androidListeners
import saarland.cispa.frontmatter.containerIds
import saarland.cispa.frontmatter.data.MergeContainer
import saarland.cispa.frontmatter.data.StmtSite
import saarland.cispa.frontmatter.data.UIText
import saarland.cispa.frontmatter.data.UIViewElement
import saarland.cispa.frontmatter.data.UIViewGroupElement
import saarland.cispa.frontmatter.ignoredViews
import saarland.cispa.frontmatter.setAdapterMethodNames
import saarland.cispa.frontmatter.setImageSubsignatures
import saarland.cispa.frontmatter.setInputTypeSubsignatures
import saarland.cispa.frontmatter.setTextSubsignatures
import soot.NullType
import soot.RefType
import soot.Scene
import soot.SootClass
import soot.SootMethod
import soot.Unit
import soot.Value
import soot.jimple.AssignStmt
import soot.jimple.Constant
import soot.jimple.DefinitionStmt
import soot.jimple.InstanceInvokeExpr
import soot.jimple.InvokeExpr
import soot.jimple.NewExpr
import soot.jimple.ReturnStmt
import soot.jimple.Stmt
import soot.toolkits.graph.ExceptionalUnitGraph

/**
 * starting value for ids for dynamically created views, so that they don't interfere with android ids
 *
 * it would be better to initialize our own resource with specific resourceIds,
 * see [soot.jimple.infoflow.android.resources.ARSCFileParser.ResourceId]
 */
const val ID_STARTING_VALUE = -90000

sealed class View(open val location: StmtSite?)
class NullView(location: StmtSite) : View(location)
class UIView(val uiViewElement: UIViewElement, location: StmtSite?) : View(location) {
    fun addChildren(nodes: Collection<View>) {
        nodes.forEach {
            when (it) {
                is UIView -> uiViewElement.addChild(it.uiViewElement)
                is MergeUIView -> uiViewElement.addChildren(it.uiViewElements)
            }
        }
    }

    fun getChild(id: Int): UIViewElement? {
        return uiViewElement.getChild(id)
    }

    fun getViewClass(): SootClass {
        return uiViewElement.viewClass
    }
}

class MergeUIView(val uiViewElements: Collection<UIViewElement>, location: StmtSite?) : View(location)

data class Inflation(val view: List<View>, val container: Set<UIView>, val attach: Boolean)

class ViewResolver(
    val context: ActivityOrFragmentClass, val appModel: AppModel,
    sceneV: Scene, private val valueResolver: ValueResolver,
    private val resources: ResourceParser,
    private val layoutAnalysis: XmlLayoutAnalysis
) : Resolver(sceneV) {
    companion object : KLogging()

    private val icfg = valueResolver.icfg
    private val scope = appModel.contextToScope[context]
    private val resourceResolver = ResourceResolver(valueResolver)
    private val stringResolver = StringResolver(valueResolver, resources)
    private val dynamicViewIds = generateSequence((ID_STARTING_VALUE), { it - 1 }).iterator()
    private val intentResolver = IntentResolver(sceneV, valueResolver, emptyMap(), resources)
    private val viewGroupSootType = RefType.v("android.view.ViewGroup")
    private val textViewSootType = RefType.v("android.widget.TextView")
    private val searchViewSootClasses = setOf(
        sceneV.getSootClass("android.widget.SearchView"),
        sceneV.getSootClass("android.support.v7.widget.SearchView")
    )

    /**
     * resolve Views from newExpr
     * create new View with custom id and put into the hierarchy
     * setText, setImage, and addView invoked by the new View and inflate(,rootView) should be relolved afterwards
     * */
    private fun resolveViewFromNewExpr(stmtSite: StmtSite): View {
        val stmt = stmtSite.stmt
        require(stmt is AssignStmt)
        require(stmt.rightOp is NewExpr)
        val id = dynamicViewIds.next() // internal id
        val resolveInvokedMethods = valueResolver.resolveInvokedMethods(stmt.leftOp, stmtSite, scope)
        val viewId = getIdOrNull(resolveInvokedMethods) ?: id
        val viewClass = (stmt.rightOp.type as RefType).sootClass
        val uiElement = createViewElement(viewId, viewClass)
        return UIView(uiElement, stmtSite)
    }

    /**
     */
    fun bindViewAttrs(layout: UIView, allocSite: StmtSite, localScope: SootMethod?): View {
        logger.debug("Binding view attrs: $layout @ $allocSite")
        if (isIgnoredView(layout)) // skip binding for some views
            return layout
        val invokedMethodUnderContextStmts = valueResolver.resolveInvokedMethods((allocSite.stmt as DefinitionStmt).leftOp, allocSite, scope)
//        val invokedMethodUnderContextStmts = invokedMethodStmts.filter { it.method.signature in context}
        bindSetText(invokedMethodUnderContextStmts, layout, localScope)
        bindSetImage(invokedMethodUnderContextStmts, layout)
        bindSetListener(invokedMethodUnderContextStmts, layout)
        if (layout.uiViewElement is UIViewGroupElement) {
            bindAddView(invokedMethodUnderContextStmts, layout)
            bindInflateForward(layout, allocSite)
            processFindViewByIdForward(invokedMethodUnderContextStmts, layout, localScope)
            bindGetChildAt(invokedMethodUnderContextStmts, layout, localScope)
        }
        if (layout.uiViewElement is UIText) {
            bindInputType(invokedMethodUnderContextStmts, layout)
        }
        if (isAdapterView(layout))
            bindAdapter(invokedMethodUnderContextStmts, layout)
        //TODO: complete
//        if (isTabHost(layout))
//            bindTabHost(invokedMethodUnderContextStmts, layout)
        return layout
    }

    /**
     * overapproximation - take all c
     */
    private fun bindGetChildAt(invokedMethods: Set<StmtSite>, layout: UIView, localScope: SootMethod?) {
        val childViews = invokedMethods.filter { it.isGetChildAt() }.flatMap { getViewGetChildAt(it, layout) }
        childViews.forEach { bindViewAttrs(it, it.location!!, localScope) }
//        val setInputTypeCalls = invokedMethods
//            .filter { it.getInvokeExprSubsignature() in setOf("getChildAt") }
//        TODO("not implemented")
    }

    private fun isIgnoredView(layout: UIView): Boolean {
        return layout.getViewClass().name in ignoredViews
    }

    private fun bindInputType(invokedMethods: Collection<StmtSite>, layout: UIView) {
        require(layout.uiViewElement is UIText)
        val setInputTypeCalls = invokedMethods
            .filter { it.getInvokeExprSubsignature() in setInputTypeSubsignatures }
        if (setInputTypeCalls.isNotEmpty()) {
            val inputTypes = setInputTypeCalls
                .flatMap { valueResolver.resolveArg(it, 0, scope) } // TODO: resolve bitwise operators
                .filter { it.stmt is AssignStmt }
                .mapNotNull { getIntConstant((it.stmt as AssignStmt).rightOp) }
            layout.uiViewElement.inputTypeValues.addAll(inputTypes)
        }
    }

    private fun isAdapterView(view: UIView): Boolean {
//        val tmp = Scene.v().getRefTypeUnsafe("android.widget.AdapterView")
        return sceneV.implements(view.getViewClass().type, RefType.v("android.widget.AdapterView"))
    }

    private fun isTabHost(view: UIView): Boolean {
        return sceneV.implements(view.getViewClass().type, RefType.v("android.widget.TabHost"))
    }

    /**
     * process view.findViewById(id), which was found during forward search for uses of View
     */
    private fun processFindViewByIdForward(invokedMethods: Collection<StmtSite>, layout: UIView, localScope: SootMethod?) {
        val viewById = invokedMethods.filter { it.isFindViewById() }.flatMap { getViewFindViewById(it, layout) }
        viewById.forEach { bindViewAttrs(it, it.location!!, localScope) }
    }

    /**
     * resolve viewById call when the instance (rootView) in known
     */
    private fun getViewFindViewById(findViewSite: StmtSite, rootView: UIView): List<UIView> {
        val viewIds = resourceResolver.resolveResourceId(findViewSite, scope = null) //XXX: point of overapproximation, use scoped queries
        if (viewIds.isEmpty())
            return emptyList()
        val newViewStmt = findViewSite.getNewStmtFromViewById() // v = new View, associated with findViewById
        return viewIds.mapNotNull {
            val viewElement = rootView.getChild(it.value)
            if (viewElement != null)
                UIView(viewElement, newViewStmt)
            else {
                logger.warn { "No view with id $it found within $findViewSite" }
                null
            }
        }
    }

    private fun getViewGetChildAt(childAtSite: StmtSite, rootView: UIView): List<UIView> {
        val newViewStmt = childAtSite.getNewStmtFromGetChildAt() // v = new View, associated with findViewById
        if (rootView.uiViewElement !is UIViewGroupElement)
            return emptyList()
        return rootView.uiViewElement.children.map {
            UIView(it, newViewStmt)
        }
    }

    private fun bindSetListener(invokedMethods: Collection<StmtSite>, layout: UIView) {
        val setListenerCalls = invokedMethods
            .filter { it.getInvokeExprSubsignature() in androidListeners }
            .flatMap { getListener(it) }.toSet()
        layout.uiViewElement.listeners.addAll(setListenerCalls)
    }

    private fun bindSetImage(invokedMethods: Collection<StmtSite>, layout: UIView) {
        val setImageCalls = invokedMethods
            .filter { it.getInvokeExprSubsignature() in setImageSubsignatures }
            .flatMap { getImage(it) }
        setImageCalls.withIndex().forEach { (idx, text) -> layout.uiViewElement.addOtherAttr("image$idx", Attr("image", text, "dynamic")) }
    }

    private fun bindSetText(
        invokedMethods: Collection<StmtSite>,
        layout: UIView, localScope: SootMethod?
    ) {
        val setTextCalls = invokedMethods // setText and setDescription are indistinguishable
            .filter { it.getInvokeExprSubsignature() in setTextSubsignatures }
            .flatMap { getText(it, localScope) }
        layout.uiViewElement.textAttributes.addAll(setTextCalls)
    }

    fun bindAddView(allocSite: StmtSite, layout: UIView): Boolean {
        val stmt = allocSite.stmt as AssignStmt
        val invokedMethods = valueResolver.resolveInvokedMethods(stmt.leftOp, allocSite, scope)
        return bindAddView(invokedMethods, layout)
    }

    private fun bindAddView(invokedMethods: Collection<StmtSite>, layout: UIView): Boolean {
        val addedView = invokedMethods.filter { it.isAddView() }.flatMap { getAddedView(it) } //TODO: localContext should be parent view???
        addedView.filterIsInstance<UIView>().forEach { layout.uiViewElement.addChild(it.uiViewElement) }
        return addedView.isNotEmpty()
    }

    /**
     * adapter is custom and not a superclass
     */
    fun isCustomAdapter(adapterClass: SootClass): Boolean {
        if (adapterClass.isAndroidClass()) return false
        if (adapterClass.isAbstract || adapterClass.isInterface) return false
        return sceneV.activeHierarchy.getSubclassesOf(adapterClass).isEmpty()
    }

    private fun bindAdapter(invokedMethods: Collection<StmtSite>, containerView: UIView): Boolean {
        val setAdapterStmts = invokedMethods.filter { isSetAdapter(it) } //addheaderview
        var isBound = false
        for (setAdapterStmt in setAdapterStmts) {
            val argClassType = setAdapterStmt.getInvokeExpr().getArg(0).type
            if (argClassType is NullType)
                continue
            require(argClassType is RefType)
            val adapterClasses = if (isCustomAdapter(argClassType.sootClass)) {
                listOf(argClassType.sootClass)
            } else {
                val adapters = valueResolver.resolveArg(setAdapterStmt, 0, scope)  // TODO: make a workaround if boomerang failed
                adapters.filterNot { it.isNullType() }
                    .filter { it.stmt is AssignStmt }
                    .map { ((it.stmt as AssignStmt).leftOp.type as RefType).sootClass }
            }
            for (adapterClass in adapterClasses) {
                val isAttached = attachAdapter(adapterClass, setAdapterStmt, containerView)
                isBound = isBound || isAttached
            }
        }
        return isBound
    }

    //TODO add passthrough for tabhost methods
    private fun bindTabHost(invokedMethods: Collection<StmtSite>, containerView: UIView): Boolean {
        //[WIP]
        TODO("Not implemented yet")
        val addTabStmts = invokedMethods.filter { isAddTab(it) }
        var isBound = false
        for (addTabStmt in addTabStmts) {
            val tabSpecVar = addTabStmt.getInvokeExpr().getArg(0)
            if (tabSpecVar.type is NullType)
                continue
            val tabSpecVars = valueResolver.resolveArg(addTabStmt, 0, scope)//TODO:!!! check if we can skip resolution of new stmt and immediately proceed with invokedMethods
            val tabSpecStmts = tabSpecVars.filterNot { it.isNullType() }
                .filter { it.stmt is AssignStmt }

            for (tabSpecStmt in tabSpecStmts) {
                val tabSpecInvokedMethods = valueResolver.resolveInvokedMethods((tabSpecStmt.stmt as AssignStmt).leftOp, tabSpecStmt, scope)
                val setContentStmts = tabSpecInvokedMethods.filter {
                    it.getInvokeExprSubsignature() in listOf(
                        "android.view.TabHost\$TabSpec setContent(android.content.Intent)",
                        "android.view.TabHost\$TabSpec setContent(android.view.TabHost\$TabContentFactory)",
                        "android.view.TabHost\$TabSpec setContent(int)"
                    )
                }
                val indicatorStmt = tabSpecInvokedMethods.filter {
                    it.getInvokeExprSubsignature() in
                        listOf(
                            "android.view.TabHost\$TabSpec setIndicator(android.view.View)",
                            "android.view.TabHost\$TabSpec setIndicator(java.lang.CharSequence)",
                            "android.view.TabHost\$TabSpec setIndicator(java.lang.CharSequence,android.graphics.drawable.Drawable)"
                        )
                }
                val indicatorLabel = getIndicatorLabel(indicatorStmt)
                for (setContentStmt in setContentStmts) {
                    val content = getTabHostContent(setContentStmt) ?: continue
                }
                // TODO resolve indicator string
//                val intentAllocationSites = setContentStmts.flatMap { valueResolver.resolveArg(it, 0, scope) }
//                val tabSpecTargets = intentAllocationSites.mapNotNull { intentResolver.getTargetsFromIntent(it) }
//                val isAttached = attachTabSpec(tabSpecTargets, containerView)
//                isBound = isBound || isAttached
            }
        }
        return isBound
    }

    private fun getTabHostContent(contentStmt: StmtSite) {
        when (contentStmt.getInvokeExprSubsignature()) {
            "android.view.TabHost\$TabSpec setContent(android.content.Intent)" -> {
                val intentAllocationSites = valueResolver.resolveArg(contentStmt, 0, scope)
                val tabSpecTargets = intentAllocationSites.mapNotNull { intentResolver.getTargetsFromIntent(it) }
            }
            "android.view.TabHost\$TabSpec setContent(android.view.TabHost\$TabContentFactory)",
            "android.view.TabHost\$TabSpec setContent(int)" -> {
            }
        }

    }

    private fun getIndicatorLabel(indicatorStmt: List<StmtSite>): String {
        TODO("not implemented")
    }

    private fun attachTabSpec(tabSpecTargets: List<ActivityClass>, containerView: UIView): Boolean {
        TODO("Inflate layout of the target activity into containerView")
    }

    fun attachAdapter(adapterClass: SootClass, setAdapterStmt: StmtSite, containerView: UIView): Boolean {
        var isAttached = false
        val getViewMethods = adapterMethodSubsignatures.mapNotNull { adapterClass.getMethodUnsafe(it) }
        for (getViewMethod in getViewMethods) {
            val layouts = when (getViewMethod.subSignature) {
                "android.view.View getView(int,android.view.View,android.view.ViewGroup)",
                "android.view.View newView(android.content.Context,android.database.Cursor,android.view.ViewGroup)" -> getAdapterLayout(getViewMethod)
                "void bindView(android.view.View,android.content.Context,android.database.Cursor)" -> null //getAdapterLayoutFromBind(getViewMethod) TODO
                else -> error("Unknown getViewMethod $getViewMethod while binding adapter")
            } ?: continue
            if (layouts.isEmpty())
                logger.warn("Failed to resolve adapter layout @ $setAdapterStmt")
            isAttached = true
            containerView.addChildren(layouts)
        }
        return isAttached
    }

    private fun getAdapterLayout(method: SootMethod): Set<UIView> {
//        val stmt = viewMethodSite.stmt
//        val method = stmt.invokeExpr.method
        if (!method.hasActiveBody() || method.isAndroidMethod()) return emptySet() //TODO: add default layouts defined in android methods
        val unitGraph = icfg.getOrCreateUnitGraph(method) as ExceptionalUnitGraph
        val returnViews = unitGraph.tails.filterIsInstance<ReturnStmt>().filterNot { it is Constant } // constant can be only null
        val layouts = returnViews.flatMap { resolveLayoutFromReturn(StmtSite(it, method)) }.toSet()
        return layouts
    }

    /**
     * like in [saarland.cispa.frontmatter.ActivityLayoutResolver.resolveLayoutView]
     */
    private fun resolveLayoutFromReturn(stmtSite: StmtSite): List<UIView> {
        val viewAllocationSites = valueResolver.resolveVar((stmtSite.stmt as ReturnStmt).op, stmtSite, scope)
        val views = viewAllocationSites
            .filter { it.stmt is AssignStmt }
            .mapNotNull { initLayout(it) }
            .filterIsInstance<UIView>()
            .onEach { bindViewAttrs(it, it.location!!, null) }
        if (views.isEmpty()) logger.warn("Could not resolve layout for adapter @ $stmtSite")
        return views
    }

    private fun SootMethod.getSuccNotConstStmt(srcUnit: Unit): Stmt {
        val stmt = this.activeBody.units.getSuccOf(srcUnit)
        val isVarRepl = stmt is AssignStmt && stmt.leftOp.toString().startsWith("varReplacer")
        val isAdapterMethod = stmt is Stmt && stmt.containsInvokeExpr() && stmt.invokeExpr.methodRef.subSignature.toString() in adapterMethodSubsignatures
        return if (isVarRepl || isAdapterMethod)
            this.getSuccNotConstStmt(stmt)
        else
            stmt as Stmt
    }

    /**
     * checks if a stmt contains setAdapter() of any AdapterView
     * complex adapters like PagerAdapter is not covered
     * use heuristic for some custom setAdapter methods with more than one argument
     */
    private fun isSetAdapter(stmtSite: StmtSite): Boolean {
        if (!stmtSite.containsInvokeExpr()) return false
        val stmt = stmtSite.stmt
        val methodRef = stmt.invokeExpr.methodRef
        val parameterTypes = methodRef.parameterTypes

        return methodRef.name in setAdapterMethodNames &&
            sceneV.implements(methodRef.declaringClass.type, RefType.v("android.widget.AdapterView")) &&
            parameterTypes.any { sceneV.implements(it, RefType.v("android.widget.Adapter")) }
    }

    private fun isAddTab(stmtSite: StmtSite): Boolean {
        if (!stmtSite.containsInvokeExpr()) return false
        val stmt = stmtSite.stmt
        val methodRef = stmt.invokeExpr.methodRef
        val parameterTypes = methodRef.parameterTypes
        return methodRef.name in listOf("addTab") &&
            sceneV.implements(methodRef.declaringClass.type, RefType.v("android.widget.TabHost")) &&
            parameterTypes.any { sceneV.implements(it, RefType.v("android.widget.TabHost\$TabSpec")) }
    }

    /**
     * check if a view from StmtSite is a root in some inflate(id, root) invocation
     * */
    fun bindInflateForward(layout: UIView, allocSite: StmtSite): Boolean { //TODO: check if we need to enforce context
        // 1) get inflate statements if any
        val stmt = (allocSite.stmt as DefinitionStmt)
        val stmtWithArgs = valueResolver.resolveUses(stmt.leftOp, allocSite, scope)
        val inflatedView = stmtWithArgs.asSequence()
            .filter { it.isInflate() }
            .map { getInflatedView(it, withRoot = false) } // we already know the root
            .filter { it.attach } // take only if attached
            .flatMap { it.view.asSequence() }
            .toSet()
        layout.addChildren(inflatedView)
        return inflatedView.filterNot { it is NullView }.any()
    }

    // TODO: process other listeners
    private fun getListener(callSite: StmtSite): Set<SootMethod> {
        return resourceResolver.resolveListener(callSite, scope)
    }

    private fun createViewElement(id: Int, viewClass: SootClass): UIViewElement {
        return when {
            sceneV.implements(viewClass.type, viewGroupSootType) -> UIViewGroupElement(id, viewClass)
            sceneV.implements(viewClass.type, textViewSootType) ||
                searchViewSootClasses.any { sceneV.implements(viewClass, it) } -> UIText(id, viewClass)
            else -> UIViewElement(id, viewClass)
        }
    }

    private fun getText(callSite: StmtSite, localScope: SootMethod?): Collection<Attr> {
        val textAttrName = getTextAttrName(callSite.getInvokeExpr())
        val allocationSites = valueResolver.resolveArg(callSite, 0, scope).filter { it.stmt is AssignStmt }
        if (localScope != null) {
            return allocationSites.filter { localScope in getScopeMethods(it) }.mapNotNull { processSetText(it) }.map { Attr(textAttrName, it, "dynamic") }
        }
        return allocationSites.mapNotNull { processSetText(it) }.map { Attr(textAttrName, it, "dynamic") }
    }

    private fun getTextAttrName(invokeExpr: InvokeExpr): String {
        return when (val setTextName = invokeExpr.methodRef.name.replace("set", "").decapitalize()) {
            "textKeepState",
            "textOn",
            "textOff" -> "text"
            else -> setTextName
        }
    }

    private fun getImage(callSite: StmtSite): List<String> {
        val imageIds = resourceResolver.resolveImage(callSite, scope)
        return imageIds.mapNotNull { resources.drawable[it.value] }
    }

    private fun getAddedView(stmtSite: StmtSite): Collection<View> {
        return resolveViewFromArg(stmtSite, 0)
    }

    private fun processSetText(stmtSite: StmtSite): String? {
        if (stmtSite.stmt !is AssignStmt) return null
        val stmt = stmtSite.stmt
        val rightOp = stmt.rightOp
        val stringId = getIntConstant(rightOp)
        if (stringId != null) {
            return resources.strings[stringId] ?: WRONG_STRING + stringId // add default strings
        }
        val stringValue = getStringConstant(rightOp)
        if (stringValue != null) {
            return stringValue
        }
        val labels = stringResolver.resolveAssignedString(stmtSite)
        return labels.joinToString("")
    }

    /**
     * resolve only const values and static fields
     * */
    private fun getIdOrNull(resolveInvokedMethods: Collection<StmtSite>): Int? {
        val setIdCalls = resolveInvokedMethods.filter { it.getInvokeExpr().methodRef.name == "setId" }
        return setIdCalls.flatMap { valueResolver.resolveArg(it, 0, scope) }
            .map { it.stmt }
            .filterIsInstance<AssignStmt>()
            .mapNotNull { getIntConstant(it.rightOp) }
            .firstOrNull()
    }

    /**
     * can come from
     * 1) View = inflate()
     * 2) View = null
     * 3) View = newExpr
     * 4) findViewById(); View = newExpr;
     *
     * */
    fun resolveView(allocationSite: StmtSite, localScope: SootMethod?): List<View> {
        val stmt = allocationSite.stmt
        require(stmt is AssignStmt) { "expected AssignStmt but was $stmt" }
        val views = when {
            allocationSite.isNullType() -> listOf(NullView(allocationSite))
            allocationSite.isInflate() -> resolveInflate(allocationSite) // it should not have a root as we hit this method from resolving addView
            allocationSite.isGetListView() -> error("Unexpected getListView")
            allocationSite.isFindViewById() -> resolveActivityFindViewById(allocationSite) // may be used to add new child views or set props XXX
            allocationSite.isNewExpr() -> listOf(resolveViewFromNewExpr(allocationSite))
            else -> {
                logger.warn("Unexpected statement in resolveView @ $stmt")
                emptyList()
            }
        }
        if (allocationSite.isFindViewById())
            views.forEach { if (it is UIView) bindViewAttrs(it, it.location!!, localScope) }  // TODO:check correctness
        else
            views.forEach { if (it is UIView) bindViewAttrs(it, allocationSite, localScope) }
        return views
    }

    /**
     * resolve view from setContentView(android.view.View)
     * can come from
     * 1) View = inflate()
     * 2) View = null
     * 3) View = newExpr
     * 4) findViewById() / View = newExpr; Not possible
     * 5) getView() Not possible
     *
     * it does not resolve other views or layout properties
     * */
    fun initLayout(allocationSite: StmtSite): View? {
        val stmt = allocationSite.stmt
        require(stmt is AssignStmt) { "expected AssignStmt but was $stmt" }
        return when {
            allocationSite.isNullType() -> NullView(allocationSite)
            allocationSite.isInflate() -> resolveContentViewInflate(allocationSite)
            allocationSite.isGetListView() -> {
                logger.warn("Unexpected getListView @ $stmt")
                null
            }
            allocationSite.isFindViewById() -> processFindInflate(allocationSite) //may come from View:inflate
            allocationSite.isNewExpr() -> resolveViewFromNewExpr(allocationSite)
            else -> {
                logger.warn("Unexpected statement during layout initialization @ $stmt")
                null
            }
        }
    }

    /**
     * special case when a view is inflated and then its root is taken via findViewById
     */
    private fun processFindInflate(allocationSite: StmtSite): View {
        require(allocationSite.isFindViewById())
        val findViewSite = allocationSite.getFindViewByIdStmt()
        val viewIds = resourceResolver.resolveResourceId(findViewSite, scope)
        val rootViewAllocationSites = valueResolver.resolveInvokerBase(findViewSite, scope)
        rootViewAllocationSites.forEach {
            if (!it.isInflate())
                logger.warn("Unexpected allocationSite during processFindInflate @ $it")
        }
        val rootViews = rootViewAllocationSites.filter { it.isInflate() }.flatMap { resolveInflate(it) }.filterIsInstance<UIView>()
        val layouts = viewIds.flatMap { viewId ->
            rootViews.mapNotNull { it.getChild(viewId.value) }
        }.map { UIView(it, allocationSite) }
        return when {
            layouts.isEmpty() -> NullView(allocationSite)
            layouts.size > 1 -> {
                logger.warn(" More than one layout resolved during processFindInflate @ $findViewSite & $allocationSite")
                layouts.first()
            }
            else -> layouts.first()
        }
    }

    /**
     * this inflate cannot be attached to root as this is incompatible with setContentView
     * */
    private fun resolveContentViewInflate(allocationSite: StmtSite): View {
        val (layouts, _, _) = getInflatedView(allocationSite, withRoot = false) //
        if (layouts.size > 1)
            return layouts.first()
        //error("More than one layout for inflation resolved @ $allocationSite") // TODO: resolve
        return layouts.firstOrNull() ?: NullView(allocationSite)
    }

    /**
     * this view is inflated from the context
     */
    private fun resolveInflate(allocationSite: StmtSite): List<View> {
        val (layouts, rootViews, attachToRoot) = getInflatedView(allocationSite, withRoot = true)
        return if (attachToRoot)
            rootViews.onEach { rootView -> rootView.addChildren(layouts) }.toList()
        else
            layouts
    }

    /**
     * return view from an inflated layout
     * Activity::findViewById
     */
    fun resolveActivityFindViewById(allocationSite: StmtSite): List<UIView> {
        val findViewSite = allocationSite.getFindViewByIdStmt()
        val callingObject = findViewSite.getInvokeExpr().methodRef.declaringClass
        if (!callingObject.isActivity())
            return emptyList() // illigal state, likely due to incorrect boomerang resolution
        require(callingObject.isActivity())
        val layoutIdsContext = resourceResolver.resolveResourceId(findViewSite, scope)
        if (layoutIdsContext.isEmpty())
            return emptyList()
        val newSite = allocationSite.getNewStmtFromViewById()
        var layouts = when {
            context.isActivity() -> appModel.activityLayouts.get(context)
            context.isFragment() -> appModel.fragmentLayouts.get(context) //search for all activities which have this fragment
            else -> error("Unknown context $context")
        }
        var views = layouts.flatMap { layout ->
            layoutIdsContext.mapNotNull {
                if (it.value in containerIds) // if this view is a container
                    layout.uiViewElement
                else if (it.value == layout.uiViewElement.id)
                    layout.uiViewElement
                else
                    layout.uiViewElement.getChild(it.value)
            }
        }.map { UIView(it, newSite) }
        if (views.isEmpty() && context.isFragment()) { // fragment changes some view from its activity
            layouts = appModel.fragmentToActivity[context].flatMap { appModel.activityLayouts.get(it) }.toSet()
            views = layouts.flatMap { layout -> layoutIdsContext.mapNotNull { layout.uiViewElement.getChild(it.value) } }.map { UIView(it, newSite) }
        }
        return views
    }

// TODO("resolve AdapterView from the hierarchy provided by LayoutMapper") -> getListViewSubsignature

    /**
     * process view Allocation sites
     * it can be:
     * 1) $r0 = null
     * 2) $r0 = new View()
     *  2a) previous statement is findViewById -> we can get an Id
     *  2b) previous statement is not findViewById
     * */
    private fun resolveViews(viewAllocationSites: Collection<StmtSite>, localScope: SootMethod?): Collection<View> {
        return viewAllocationSites
            .filter { it.stmt is AssignStmt }
            .flatMap { resolveView(it, localScope) }
    }

    /**
     * stmtSite: $view = inflate(..)
     * XXX: point of overapproximation, use contextRestricted resolver to get more precision, or use inlining
     * */
    fun getInflatedView(stmtSite: StmtSite, withRoot: Boolean): Inflation {
        val inflateParams = getInflateParams(stmtSite)
        val layoutIdIdx: Int = inflateParams.first
        val viewGroupIdx: Int = inflateParams.second
        var attachToRoot: Boolean = inflateParams.third
        val layoutId = resourceResolver.resolveResourceId(stmtSite, scope, layoutIdIdx)
        val layouts = layoutId.mapNotNull { createUIViewFromXml(it, stmtSite) }
        val viewGroup = if (attachToRoot && withRoot) resolveViewFromArg(stmtSite, viewGroupIdx) else emptyList() // resolve only if it will be the root
        // if viewGroup.isEmpty() then we could not (or want not) resolve it
        if (viewGroup.isNotEmpty() && viewGroup.all { it is NullView })
            attachToRoot = false
        return Inflation(layouts, viewGroup.filterIsInstance<UIView>().toSet(), attachToRoot)
    }

    private fun getInflateParams(stmtSite: StmtSite): Triple<Int, Int, Boolean> {
        val attachToRoot: Boolean
        val layoutIdIdx: Int
        val viewGroupIdx: Int
        require(stmtSite.stmt.containsInvokeExpr())
        when (stmtSite.stmt.invokeExpr.method.subSignature) {
            "android.view.View inflate(int,android.view.ViewGroup)",
            "void inflate(int,android.view.ViewGroup,android.support.v4.view.AsyncLayoutInflater.OnInflateFinishedListener)" -> {
                layoutIdIdx = 0
                viewGroupIdx = 1
                attachToRoot = true
            }
            "android.view.View inflate(int,android.view.ViewGroup,boolean)" -> {
                layoutIdIdx = 0
                viewGroupIdx = 1
                attachToRoot = valueResolver.resolveArg(stmtSite, 2, scope)
                    .mapNotNull { it.stmt as? AssignStmt }
                    .mapNotNull { getIntConstant(it.rightOp) }
                    .any { it > 0 }
            }
            "android.view.View inflate(android.content.Context,int,android.view.ViewGroup)" -> { // static View:
                layoutIdIdx = 1
                viewGroupIdx = 2
                attachToRoot = true
            }
            else -> {
                throw IllegalStateException("Unknown inflate() method @ $stmtSite")
            }
        }
        return Triple(layoutIdIdx, viewGroupIdx, attachToRoot)
    }

    private fun createUIViewFromXml(layoutId: ResourceId, stmtSite: StmtSite): View? {
        val layout = getXmlLayout(layoutId) ?: return null
        return if (layout.size == 1)
            UIView(layout.first(), stmtSite) // XXX: should it borrow the context of layoutId?
        else MergeUIView(layout, stmtSite)
    }

    /***
     * get an inflated layout respecting the context: the context of a resource should be the same or of a superclass as the context of viewResolver
     * in case of merge layout a list should be returned instead of one root
     */
    private fun getXmlLayout(layoutId: ResourceId): Collection<UIViewElement>? {
        val layoutContext = getContext(layoutId.location)
        return if (layoutContext.isEmpty() || context in layoutContext) {
            when (val layout = layoutAnalysis.getLayout(layoutId.value, context)) {
                is MergeContainer -> {
                    layout.children
                }
                is UIViewElement -> {
                    listOf(layout)
                }
                else -> null
            }
        } else null
    }


    /**
     * resolve viewId of the caller object
     * e.g. of a view v in v.setText()
     * it searches for the following statements
     *  $r0 = findViewById($id)
     *  $r1 = new Button() //points to $r0
     *  ...
     *  $r1.callSite(smth)
     *
     *  from V.getView, V.newView, etc
     * */
    fun resolveViewFromBase(callSite: StmtSite): Collection<View> {
        val invokeExpr = callSite.stmt.invokeExpr
        require(invokeExpr is InstanceInvokeExpr)
        val viewAllocationSites = valueResolver.resolveInvokerBase(callSite, scope)
        val views = resolveViews(viewAllocationSites, null)
        if (views.isEmpty()) logger.warn("Could not resolve View @ $callSite")
        return views
    }

    /**
     *
     */
    private fun resolveViewFromArg(callSite: StmtSite, argId: Int): Collection<View> {
        val viewAllocationSites = valueResolver.resolveArg(callSite, argId, scope)
        val views = resolveViews(viewAllocationSites, callSite.method)
        if (views.isEmpty()) logger.warn("Could not resolve View @ $callSite")
        return views
    }

    fun resolveViewFromVar(callSite: StmtSite, variable: Value): Collection<View> {
        val viewAllocationSites = valueResolver.resolveVar(variable, callSite, scope)
        val views = resolveViews(viewAllocationSites, null)
        if (views.isEmpty()) logger.warn("Could not resolve View @ $callSite")
        return views
    }
}
