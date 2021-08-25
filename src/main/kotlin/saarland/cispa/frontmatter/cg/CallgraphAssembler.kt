package saarland.cispa.frontmatter.cg

import mu.KLogging
import saarland.cispa.frontmatter.ApkInfo
import saarland.cispa.frontmatter.DUMMY_ACTIVITY_NAME
import saarland.cispa.frontmatter.FIND_VIEW_METHOD_NAME
import saarland.cispa.frontmatter.Utils.implements
import saarland.cispa.frontmatter.Utils.isAndroidClass
import saarland.cispa.frontmatter.Utils.isFragment
import saarland.cispa.frontmatter.Utils.isAndroidMethod
import saarland.cispa.frontmatter.Utils.isHandlerSubclass
import saarland.cispa.frontmatter.Utils.isInSystemPackage
import saarland.cispa.frontmatter.Utils.isReceiver
import saarland.cispa.frontmatter.Utils.isSystemMethod
import saarland.cispa.frontmatter.VIEW_CONSTRUCTOR_SIGNATURE
import saarland.cispa.frontmatter.VIEW_CONSTRUCTOR_SIGNATURE2
import saarland.cispa.frontmatter.VIEW_SET_ID_SIGNATURE
import saarland.cispa.frontmatter.adapterMethodSubsignatures
import saarland.cispa.frontmatter.androidListeners
import saarland.cispa.frontmatter.asyncMethodSubsignatures
import saarland.cispa.frontmatter.registerBroadcastSubsignatures
import saarland.cispa.frontmatter.setAdapterMethodNames
import saarland.cispa.frontmatter.setListAdapterSubsignature
import soot.Body
import soot.IntType
import soot.IntegerType
import soot.Local
import soot.PackManager
import soot.PrimType
import soot.RefType
import soot.Scene
import soot.SootClass
import soot.SootMethod
import soot.SootMethodRef
import soot.Value
import soot.VoidType
import soot.javaToJimple.LocalGenerator
import soot.jimple.AssignStmt
import soot.jimple.IdentityStmt
import soot.jimple.InstanceInvokeExpr
import soot.jimple.IntConstant
import soot.jimple.InvokeStmt
import soot.jimple.Jimple
import soot.jimple.NullConstant
import soot.jimple.SpecialInvokeExpr
import soot.jimple.Stmt
import soot.jimple.VirtualInvokeExpr
import soot.jimple.infoflow.AbstractInfoflow
import soot.jimple.infoflow.InfoflowConfiguration.CallgraphAlgorithm
import soot.jimple.infoflow.android.callbacks.AndroidCallbackDefinition
import soot.jimple.infoflow.android.entryPointCreators.AbstractAndroidEntryPointCreator
import soot.jimple.infoflow.android.manifest.ProcessManifest
import soot.jimple.internal.JCastExpr
import soot.jimple.toolkits.callgraph.CallGraph
import soot.options.Options
import soot.tagkit.Tag
import soot.toolkits.graph.ExceptionalUnitGraph
import soot.toolkits.scalar.SimpleLocalDefs
import soot.toolkits.scalar.SimpleLocalUses
import soot.toolkits.scalar.UnitValueBoxPair
import soot.util.HashMultiMap
import soot.util.MultiMap
import java.util.*


//private fun SootClass.isFragment(sceneV: Scene): Boolean {
//    return sceneV.activeHierarchy.getSuperclassesOfIncluding(this).any { it.name in fragmentClasses }
//}

class CallgraphAssembler(
    private val sceneV: Scene, callbackDefs: MultiMap<SootClass, AndroidCallbackDefinition>,
    apkInfo: ApkInfo, callgraphAlgorithm: CallgraphAlgorithm = CallgraphAlgorithm.SPARK
) {
    companion object : KLogging()

    lateinit var entryPointCreator: FrontmatterEntryPointCreator
    private val fragments = collectFragments()
    private val adapterInterface = sceneV.getSootClass("android.widget.Adapter")
    private val viewConstructorArguments = listOf("android.content.Context", "android.util.AttributeSet")
    fun getAllFragments(): Set<SootClass> {
        return fragments.get(sceneV.getSootClassUnsafe(DUMMY_ACTIVITY_NAME)).toSet()
    }

    val callgraph: CallGraph

    init {
        logger.info("Patching the code")
        configureCallgraph(callgraphAlgorithm)
//            logger.warn("=>> patchFindViewMethod disabled")
        logger.info("Patching findViewById")
        patchMethods(::patchFindViewMethod)
        logger.info("Patching BroadcastReceivers")
        patchMethods(::patchBroadcasts)
//            patchSubclasses() // not used
        logger.info("Patching Adapters")
        patchMethods(::patchSetAdapter)
        logger.info("Patching AsyncTask")
        patchMethods(::patchAsyncTask)
//        patchAsync()
        logger.info("Patching Handlers")
        patchMethods(::patchHandlers)
//        patchHandlers()
        val callbacks = getCallbacks(callbackDefs) // patch listener setters: insert on### calls right after they are set in setOn###Listener
        logger.info("Patching Callbacks")
        patchCallbacks(callbacks)
        createMainMethod(callbacks, apkInfo.manifest, apkInfo.entryPoints, fragments)
        logger.info("Constructing a call graph")
        PackManager.v().getPack("cg").apply()
        logger.info("Callgraph has ${sceneV.callGraph.size()} edges")
//        sceneV.getOrMakeFastHierarchy()
        callgraph = sceneV.callGraph
    }

    /**
     * Find Adapter object constructor sites and insert getView and bindView calls
     * we should associate any test and callbacks of items in any AdapterView with this view
     * from <init> stmt
     * */
    @Deprecated("not used")
    private fun patchAdapter(method: SootMethod) {
        val adapterConstructorStmt = method.retrieveActiveBody().units.snapshotIterator().asSequence()
//            .filterIsInstance<InvokeStmt>() //XXX: looking for constructor stmt <init> which should be an invoke stmt
            .filterIsInstance<InvokeStmt>()
            .filter { it.invokeExpr is SpecialInvokeExpr }
            .filter { isCustomAdapterMethod((it.invokeExpr as SpecialInvokeExpr).methodRef) }
            .toList()
        try {
            for (constructor in adapterConstructorStmt) {
                logger.debug("${method.signature} -> $constructor")
                val adapterClass = constructor.invokeExpr.methodRef.declaringClass // as it's from ref it is expected to be be a concrete class
                for (adapterMethodSign in adapterMethodSubsignatures) {
                    if (adapterClass.declaresMethod(adapterMethodSign)) {
                        val adapterMethod = adapterClass.getMethodUnsafe(adapterMethodSign)
                        val methodRef = adapterMethod.makeRef()
                        val params = methodRef.parameterTypes.map {
                            //FIXME: insert proper params
                            when (it) {
                                is IntegerType -> IntConstant.v(0)
                                is RefType -> {
                                    NullConstant.v()
//                                    if (it == RefType.v("androd.view.View")) NullConstant.v() else NullConstant.v()
                                }
                                else -> NullConstant.v()
                            }
                        }
                        val base = (constructor.invokeExpr as SpecialInvokeExpr).base as Local
                        val callExpr = Jimple.v().newVirtualInvokeExpr(base, methodRef, params)
                        val newCallStmt = Jimple.v().newInvokeStmt(callExpr)
                        newCallStmt.addTag(ArtificialTag(true))
                        method.retrieveActiveBody().units.insertAfter(newCallStmt, constructor)

                    }
                }
            }
        } catch (t: Throwable) {
            logger.warn("Error while patching adapter in $method")
        }
    }

    /**
     * Find Adapter setters and insert getView and bindView calls
     * we should associate any test and callbacks of items in any AdapterView with this view
     * from setAdapter calls
     * */
    private fun patchSetAdapter(method: SootMethod) {
        val setAdapterStmt = method.retrieveActiveBody().units.snapshotIterator().asSequence()
            .filterIsInstance<InvokeStmt>() //XXX: looking for setter stmt <init> which should be an invoke stmt
            .filter { it.invokeExpr is InstanceInvokeExpr }
            .filter {
                (it.invokeExpr as InstanceInvokeExpr).methodRef.name in setAdapterMethodNames ||
                    (it.invokeExpr as InstanceInvokeExpr).methodRef.subSignature.toString() in setListAdapterSubsignature
            }
            .toList()
        try {
            val localGenerator = LocalGenerator(method.activeBody)
            for (setAdapter in setAdapterStmt) {
                val invokeExpr = setAdapter.invokeExpr
                require(invokeExpr is InstanceInvokeExpr)
                val view = invokeExpr.base //
                val adapter = invokeExpr.getArg(0) as? Local ?: continue
                val adapterClass = (adapter.type as RefType).sootClass
                // inject getView
                val getViewRef = sceneV.makeMethodRef(
                    adapterClass, "getView", listOf(IntType.v(), RefType.v("android.view.View"), RefType.v("android.view.ViewGroup")),
                    RefType.v("android.view.View"), false
                )
                val getViewMethod = getViewRef.tryResolve()
                if (getViewMethod != null) {
                    //&& !getViewMethod.isAndroidMethod()) {
                    // Insert getView method of an Adapter
                    val getViewParams = listOf(IntConstant.v(0), NullConstant.v(), view) //XXX: should we provide a real value instead of NullConstant
                    val getViewcallExpr = createNewInvokeExpr(adapterClass, adapter, getViewRef, getViewParams)
                    val viewItem = localGenerator.generateLocal(RefType.v("android.view.View"))
                    val getViewCallStmt = Jimple.v().newAssignStmt(viewItem, getViewcallExpr)
                    getViewCallStmt.addTag(ArtificialTag(true))
                    method.retrieveActiveBody().units.insertAfter(getViewCallStmt, setAdapter)
                }
                // if there is no getView inject newView
                val newViewRef = sceneV.makeMethodRef(
                    adapterClass, "newView", listOf(
                        RefType.v("android.content.Context"), RefType.v("android.database.Cursor"),
                        RefType.v("android.view.ViewGroup")
                    ), RefType.v("android.view.View"), false
                )
                val newViewMethod = newViewRef.tryResolve()
                if (newViewMethod != null && !newViewMethod.isAndroidMethod()) {
                    // Insert newView method of an Adapter
                    val newViewParams = listOf(NullConstant.v(), NullConstant.v(), view) //XXX: should we provide a real value instead of NullConstant, eg from constructor
                    val newViewCallExpr = createNewInvokeExpr(adapterClass, adapter, newViewRef, newViewParams)
                    val viewItem = localGenerator.generateLocal(RefType.v("android.view.View"))
                    val newViewCallStmt = Jimple.v().newAssignStmt(viewItem, newViewCallExpr)
                    newViewCallStmt.addTag(ArtificialTag(true))
                    method.retrieveActiveBody().units.insertAfter(newViewCallStmt, setAdapter)
                    // if bindView is declared inject bindView after newView placing it in the param
                    val bindViewRef = sceneV.makeMethodRef(
                        adapterClass, "bindView", listOf(
                            RefType.v("android.view.View"), RefType.v("android.content.Context"),
                            RefType.v("android.database.Cursor")
                        ), VoidType.v(), false
                    )
                    val bindViewMethod = newViewRef.tryResolve()
                    if (bindViewMethod != null && !bindViewMethod.isAndroidMethod()) {
                        val bindViewParams = listOf(viewItem, NullConstant.v(), NullConstant.v())
                        val bindViewCallExpr = createNewInvokeExpr(adapterClass, adapter, bindViewRef, bindViewParams)
                        val bindViewCallStmt = Jimple.v().newInvokeStmt(bindViewCallExpr)
                        bindViewCallStmt.addTag(ArtificialTag(true))
                        method.retrieveActiveBody().units.insertAfter(bindViewCallStmt, newViewCallStmt)
                    }
                }
            }
        } catch (t: Throwable) {
            logger.warn("Error while patching adapter in $method")
        }
    }

    private fun createNewInvokeExpr(
        adapterClass: SootClass, adapter: Local, newViewRef: SootMethodRef?,
        newViewParams: List<Value>
    ) = if (!adapterClass.isInterface) Jimple.v().newVirtualInvokeExpr(adapter, newViewRef, newViewParams) else
        Jimple.v().newInterfaceInvokeExpr(adapter, newViewRef, newViewParams)

    /**
     * Find Adapter setters and insert getView and bindView calls
     * we should associate any test and callbacks of items in any AdapterView with this view
     * */
    private fun patchAsyncTask(method: SootMethod) {
        val executeAsyncStmt = method.retrieveActiveBody().units.snapshotIterator().asSequence()
            .filterIsInstance<InvokeStmt>() //XXX: looking for setter stmt <init> which should be an invoke stmt
            .filter { it.invokeExpr is InstanceInvokeExpr }
            .filter { (it.invokeExpr as InstanceInvokeExpr).methodRef.subSignature.toString() in asyncMethodSubsignatures }
            .toList()
        try {
            for (execute in executeAsyncStmt) {
                val invokeExpr = execute.invokeExpr
                require(invokeExpr is InstanceInvokeExpr)
                val asyncObject = invokeExpr.base //
                val asyncClass = (asyncObject.type as RefType).sootClass
                val param = invokeExpr.getArg(0)
                // insert in reversed order as we use insertAfter newCallStmt
                if (asyncClass.declaresMethod("void onPostExecute(java.lang.Object)")) {
                    val asyncMethod = asyncClass.getMethodUnsafe("void onPostExecute(java.lang.Object)")
                    val methodRef = asyncMethod.makeRef()
                    val callExpr = Jimple.v().newVirtualInvokeExpr(asyncObject as Local, methodRef, param)
                    val newCallStmt = Jimple.v().newInvokeStmt(callExpr)
                    newCallStmt.addTag(ArtificialTag(false))
                    method.retrieveActiveBody().units.insertAfter(newCallStmt, execute)
                }
                if (asyncClass.declaresMethod("java.lang.Object doInBackground(java.lang.Object[])")) {
                    val asyncMethod = asyncClass.getMethodUnsafe("java.lang.Object doInBackground(java.lang.Object[])")
                    val methodRef = asyncMethod.makeRef()
                    val callExpr = Jimple.v().newVirtualInvokeExpr(asyncObject as Local, methodRef, param)
                    val newCallStmt = Jimple.v().newInvokeStmt(callExpr)
                    newCallStmt.addTag(ArtificialTag(false))
                    method.retrieveActiveBody().units.insertAfter(newCallStmt, execute)
                }
                if (asyncClass.declaresMethod("void onPreExecute()")) {
                    val asyncMethod = asyncClass.getMethodUnsafe("void onPreExecute()")
                    val methodRef = asyncMethod.makeRef()
                    val callExpr = Jimple.v().newVirtualInvokeExpr(asyncObject as Local, methodRef)
                    val newCallStmt = Jimple.v().newInvokeStmt(callExpr)
                    newCallStmt.addTag(ArtificialTag(false))
                    method.retrieveActiveBody().units.insertAfter(newCallStmt, execute)
                }
            }
        } catch (t: Throwable) {
            logger.warn("Error while patching AsyncTask in $method")
        }
    }

    private fun isCustomAdapterMethod(methodRef: SootMethodRef) = !methodRef.declaringClass.isAndroidClass() && sceneV.implements(methodRef.declaringClass, adapterInterface)
//        sceneV.activeHierarchy.getSuperclassesOfIncluding(declaringClass).flatMap { it.interfaces }
//            .flatMap { sceneV.activeHierarchy.getSuperinterfacesOfIncluding(it) }.contains(adapterInterface)


    /**
     * TODO: ?use valueResolver as callback may come as a variable
     * */
    private fun patchCallbacks(callbacks: MultiMap<SootClass, SootMethod>) {
        val appClasses = sceneV.applicationClasses
//        val callbackClasses = callbacks.values().map { it.declaringClass.name }
        for (appClass in appClasses) {
            for (method in appClass.methods) {
                if (method.isConcrete) {
                    val listenerSetters = method.retrieveActiveBody().units.snapshotIterator().asSequence()
                        .filterIsInstance<InvokeStmt>()
                        .filter { it.invokeExpr.method.subSignature in androidListeners }
                    for (listenerStmt in listenerSetters) {
                        try {
                            val listenerExpr = listenerStmt.invokeExpr
                            require(listenerExpr is InstanceInvokeExpr)
                            var callbackMethodName = getCallbackMethodForListener(listenerExpr.method)
                            val listener = listenerExpr.getArg(0)
                            if (listener is NullConstant) //listener is detached
                                continue
                            val listenerClass = (listener.type as RefType).sootClass
                            val view = listenerExpr.base
                            if (!listenerClass.declaresMethodByName(callbackMethodName))
                                if (!listenerClass.declaresMethodByName(callbackMethodName + "d")) {
                                    logger.info("No appropriate callback was found for $listenerStmt")
                                    continue
                                } else {
                                    callbackMethodName += "d"
                                }

                            val listenerObj = listener as Local
                            val methodRef = if (callbackMethodName == "onClick") {
                                listenerClass.getMethod("void onClick(android.view.View)").makeRef()
                            } else {
                                listenerClass.getMethodByName(callbackMethodName).makeRef()
                            }
                            val callbackArgs: List<Value> = methodRef.parameterTypes.map {
                                when (it) {
                                    is PrimType -> IntConstant.v(0)
                                    is RefType -> {
                                        if (sceneV.fastHierarchy.canStoreType(view.type, it)) view else NullConstant.v()
                                    }
                                    else -> NullConstant.v()
                                }
                            } //istOf(view) //NullConstant.v()
                            val callbackExpr = if (listenerClass.isInterface) //XXX: fix abstract method call
                                Jimple.v().newInterfaceInvokeExpr(listenerObj, methodRef, callbackArgs) else
                                Jimple.v().newVirtualInvokeExpr(listenerObj, methodRef, callbackArgs)
                            val newCallbackStmt = Jimple.v().newInvokeStmt(callbackExpr)
                            newCallbackStmt.addTag(ArtificialTag(true))
                            method.retrieveActiveBody().units.insertAfter(newCallbackStmt, listenerStmt)
                            logger.debug("->$listenerObj @ $listenerStmt")
                        } catch (t: Throwable) {
                            logger.warn("Error in patching $listenerStmt", t)
                        }
                    }
                }
            }
//            }
        }
    }

    private fun getCallbackMethodForListener(method: SootMethod): String {
        val name = method.name
        return name.replace(Regex("^set"), "").replace(Regex("Listener$"), "").decapitalize()
    }

    private fun getCallbacks(callbackDefs: MultiMap<SootClass, AndroidCallbackDefinition>): MultiMap<SootClass, SootMethod> {
        val callbackMethodSigns = HashMultiMap<SootClass, SootMethod>()
        for (callbackClass in callbackDefs.keySet()) {
            val callbacks = callbackDefs.get(callbackClass)
            callbacks?.let {
                for (cd in callbacks) callbackMethodSigns.put(callbackClass, cd.targetMethod)
            }
        }
        return callbackMethodSigns
    }

    private fun makeEntryPointCreator(
        callbackMethods: MultiMap<SootClass, SootMethod>, manifest: ProcessManifest,
        entryPoints: Collection<SootClass>, fragments: MultiMap<SootClass, SootClass>
    ): AbstractAndroidEntryPointCreator {
        entryPointCreator = FrontmatterEntryPointCreator(manifest, entryPoints, callbackMethods, fragments)
        // We need to include all callback methods as they can be entry points
        // Otherwise we would miss a lot of internal methods which are reachable from that callbacks only
        // we still miss some methods that are called implicitly, like Thread.run tasks
        // and a lot of methods triggered by Android OS
        // Get all callbacks for all components
        // TODO: substitute AndroidCallbackDefinition with SootMethod
        return entryPointCreator

    }

    /**
     * collect all fragments defined in the code - over-approximation
     *
     * */
    private fun collectFragments(): HashMultiMap<SootClass, SootClass> {
        val fragments = sceneV.applicationClasses.asSequence()
            .filter { it.isFragment() }
            .filter { it.isConcrete }
            .toSet()
        val fragmentsMap = HashMultiMap<SootClass, SootClass>()
        val dummyActivityObj = sceneV.getSootClassUnsafe(DUMMY_ACTIVITY_NAME) // have to create DUMMY_ACTIVITY_NAME, flowdroid relies on activity-fragments mapping
        fragmentsMap.putAll(dummyActivityObj, fragments)
        return fragmentsMap
    }

    /**
     * Creates the main method based on the current callback information, injects it
     * into the Soot scene.
     */
    private fun createMainMethod(
        possibleCallbacks: MultiMap<SootClass, SootMethod>, manifest: ProcessManifest,
        entryPoints: Collection<SootClass>, fragments: MultiMap<SootClass, SootClass>
    ) {
        // Always update the entry point creator to reflect the newest set
        // of callback methods
        // TODO refactor this part
        val entryPointCreator = makeEntryPointCreator(possibleCallbacks, manifest, entryPoints, fragments)
        val dummyMainMethod = entryPointCreator.createDummyMain()
        sceneV.entryPoints = listOf<SootMethod>(dummyMainMethod)
        if (!dummyMainMethod.declaringClass.isInScene) sceneV.addClass(dummyMainMethod.declaringClass)
        // addClass() declares the given class as a library class. This is fixed as follows.
        dummyMainMethod.declaringClass.setApplicationClass()
    }

    private fun patchMethods(patchingFunc: (method: SootMethod) -> Unit) {
        val appClasses = sceneV.classes
            .filterNot { it.isInSystemPackage() || it.isAndroidClass() }
        val deadMethodClasses = HashSet<SootClass>()
        for (appClass in appClasses) {
            for (method in appClass.methods.toList()) {
                if (method.isConcrete)
                    try {
                        patchingFunc(method)
                    } catch (e: Throwable) {
                        logger.error("Error while patching method ${method.signature} with $patchingFunc", e)
                        deadMethodClasses.add(appClass)
                    }
            }
        }
        deadMethodClasses.forEach { it.setLibraryClass() } // don't process classes which failed
    }

    private fun patchHandlers() {
        val appClasses = sceneV.applicationClasses
        for (appClass in appClasses) {
            for (method in appClass.methods.toList()) {
                if (method.isConcrete)
                    try {
                        patchHandlers(method)
                    } catch (e: Throwable) {
                        logger.error("Error while patching method ${method.signature}", e)
                    }
            }
        }
    }

    private fun patchAsync() {
        val appClasses = sceneV.applicationClasses
        for (appClass in appClasses) {
            for (method in appClass.methods.toList()) {
                if (method.isConcrete)
                    try {
                        patchAsyncTask(method)
                    } catch (e: Throwable) {
                        logger.error("Error while patching method ${method.signature}", e)
                    }
            }
        }
    }

    /**
     * NOT USED
     * add methods from superclasses if they are not overriden
     * */
    private fun patchSubclasses() {
        val appClasses = sceneV.applicationClasses
        for (appClass in appClasses) {
            val superClass = appClass.superclassUnsafe ?: continue
            val superclasses = sceneV.activeHierarchy.getSuperclassesOfIncluding(appClass).reversed()
            val methodsToAdd = mutableListOf<SootMethod>()
            for (sc in superclasses) {
                if (sc.isAndroidClass() || sc.isInSystemPackage()) {
                    continue
                }
                val parent = sc.superclass
                if (parent.isAndroidClass() || parent.isInSystemPackage())
                    continue
                val scMethods = sc.methods.map { it.subSignature }
                for (m in parent.methods) {
                    if (!m.isConcrete)
                        continue
                    if (m.subSignature !in scMethods) {
                        val newMethod = SootMethod(m.name, m.parameterTypes, m.returnType, m.modifiers, m.exceptions)
                        val body = Jimple.v().newBody(newMethod)
                        body.importBodyContentsFrom(m.retrieveActiveBody())
                        val unitsIterator = body.units.snapshotIterator()

                        for (u in unitsIterator) {
                            require(u is Stmt)
                            if (u is IdentityStmt && u.rightOp.type is RefType && (u.rightOp.type as RefType).sootClass == parent) {
//                                u.rightOp.type = sc.type
                                val newIdentityStmt = Jimple.v().newIdentityStmt(u.leftOp, Jimple.v().newThisRef(sc.type))
                                body.units.insertBefore(newIdentityStmt, u)
                                body.units.remove(u)
                                val thisLocal = body.thisLocal
                                thisLocal.type = sc.type
                            }
//                            if (u.containsInvokeExpr()){
//                                if (u.invokeExpr is VirtualInvokeExpr){
//                                    if (u is AssignStmt){
//                                        val newExpr = Jimple.v().newVirtualInvokeExpr()
//                                        val newAssignStmt = Jimple.v().newAssignStmt(u.leftOp,newExpr)
//                                    }
//                                }
//                            }
                        }
                        newMethod.activeBody = body
                        methodsToAdd.add(newMethod)
//                        sc.addMethod(newMethod)
                    }
                }
                for (m in methodsToAdd) {
                    if (!m.isDeclared())
                        sc.addMethod(m)
                }
            }
        }
    }

    /**
     * patch android.os.Handler subclasses: Android system invokes overridden handleMessage()
     * when receives a Message for a thread it's managing
     *
     * collect all Handler instantiation statements and put handleMessage() call afterwards
     * */
    private fun patchHandlers(method: SootMethod) {
        val activeBody = method.retrieveActiveBody()
        val initHandlerStmts = activeBody.units.snapshotIterator().asSequence()
            .filterIsInstance<Stmt>()
            .filter { it.containsInvokeExpr() }
            .filter { it.invokeExpr.method.isConstructor }
            .filter { ((it.invokeExpr as SpecialInvokeExpr).base.type as RefType).sootClass.isHandlerSubclass() }
            .toSet()
        if (initHandlerStmts.isEmpty())
            return

        for (initStmt in initHandlerStmts) {
            val base = (initStmt.invokeExpr as SpecialInvokeExpr).base
            val handleMethod = (base.type as RefType).sootClass.getMethodUnsafe("void handleMessage(android.os.Message)")
            handleMethod?.let {
                val handleExpr = Jimple.v().newSpecialInvokeExpr(base as Local, it.makeRef(), NullConstant.v())
                val handleStmt = Jimple.v().newInvokeStmt(handleExpr)
                handleStmt.addTag(ArtificialTag(false))
                activeBody.units.insertAfter(handleStmt, initStmt)
            }
        }
    }

    private fun patchFindViewMethod(method: SootMethod) {
        val activeBody = method.retrieveActiveBody()
        // collect findViewById call sites
        //   <android.app.Activity: android.view.View findViewById(int)>
        //   <android.app.View: android.view.View findViewById(int)>
        val findViewStmts = activeBody.units.snapshotIterator().asSequence()
            .filterIsInstance<Stmt>()
            .filter { it.containsInvokeExpr() }
            .filter { it.invokeExpr.method.name == FIND_VIEW_METHOD_NAME || it.invokeExpr.method.name == "getChildAt" }
            .filter { it is AssignStmt }
            .toList()

        if (findViewStmts.isEmpty()) {
            return
        }
        val unitGraph = ExceptionalUnitGraph(activeBody)
        val sld = SimpleLocalDefs(unitGraph)
        val simpleLocalUses = SimpleLocalUses(unitGraph, sld)
        // XXX: we break data flow
        for (findViewStmt in findViewStmts) {
            val usesOf = simpleLocalUses.getUsesOf(findViewStmt)
            val castStmts = usesOf.map(UnitValueBoxPair::getUnit).filterIsInstance<AssignStmt>().filter { it.rightOp is JCastExpr }
            if (castStmts.isEmpty()) {
                // no casting use generic view variable
                val viewVar = (findViewStmt as AssignStmt).leftOp
                val varRefType = RefType.v("android.view.View")
                insertNewViewStmtAfter(findViewStmt, viewVar, varRefType, activeBody)
                //TODO: insert default constructor
            } else {
                // cast statements are ordered according to appearance in the code
                castStmts.withIndex().forEach { (index, castStmt) ->
                    val viewVar = castStmt.leftOp
                    val varRefType = viewVar.type as RefType
                    val insertedStmt = if (index > 0) {
                        // as we always consider findViewById stmt as a pair findViewById:newExpr, we should insert a new one if there are more then one castStmt
                        val secondFindViewStmt = findViewStmt.clone() as Stmt
                        castStmt.addTag(ArtificialTag(false))
                        activeBody.units.insertBefore(secondFindViewStmt, castStmt)
                        insertNewViewStmtAfter(castStmt, viewVar, varRefType, activeBody)
                    } else insertNewViewStmtAfter(findViewStmt, viewVar, varRefType, activeBody) // insert right after findViewById
                    if (!varRefType.sootClass.isAndroidClass()) {
                        // insert constructor for custom views
                        val constructorParams = LinkedList<Value>()
                        constructorParams.add(NullConstant.v()) // don't create Context, set it to null; shall we try to find it in other statements instead???
                        constructorParams.add(NullConstant.v())
                        val viewConstructor = varRefType.sootClass.getMethodUnsafe(VIEW_CONSTRUCTOR_SIGNATURE)
                        if (viewConstructor != null) {
                            val viewConstructorExpr = Jimple.v().newSpecialInvokeExpr(viewVar as Local, viewConstructor.makeRef(), constructorParams)
                            val viewConstructorStmt = Jimple.v().newInvokeStmt(viewConstructorExpr)
                            viewConstructorStmt.addTag(ArtificialTag(false))
                            activeBody.units.insertAfter(viewConstructorStmt, insertedStmt)
                        }
                    }
                    activeBody.units.remove(castStmt) // XXX: cast statement is removed here
                }
            }
        }

//        for ((castStmt, _) in castView) {
//            val viewVar = castStmt.leftOp
//            val varRefType = viewVar.type as RefType
//            insertViewVar(viewVar, varRefType, castStmt, activeBody)
//            activeBody.units.remove(castStmt) // XXX: cast statement is removed here
//        }
        // for general methods there mey be no cast statement; let's create just a View object
    }

    private fun patchBroadcasts(method: SootMethod) {
        val activeBody = method.retrieveActiveBody()
        val registerBroadcastStmts = activeBody.units.snapshotIterator().asSequence()
            .filterIsInstance<Stmt>()
            .filter { it.containsInvokeExpr() }
            .filter { (it.invokeExpr as? InstanceInvokeExpr)?.methodRef?.subSignature?.toString() in registerBroadcastSubsignatures }
            .toSet()

        for (registerStmt in registerBroadcastStmts) {
            val registerExpr = registerStmt.invokeExpr
            require(registerExpr is InstanceInvokeExpr)
            val broadcast = registerExpr.getArg(0) as? Local ?: continue
            val broadcastClass = (broadcast.type as RefType).sootClass
            // inject getView
            val onReceiveRef = sceneV.makeMethodRef(
                broadcastClass, "onReceive", listOf(RefType.v("android.content.Context"), RefType.v("android.content.Intent")),
                VoidType.v(), false
            )
            val onReceiveMethod = onReceiveRef.tryResolve()
            if (onReceiveMethod != null) {
                val registerObj = registerExpr.base
                val onReceiveParams = listOf(registerObj, NullConstant.v())
                val onReceiveExpr = Jimple.v().newVirtualInvokeExpr(broadcast, onReceiveRef, onReceiveParams)
                val onReceiveStmt = Jimple.v().newInvokeStmt(onReceiveExpr)
                onReceiveStmt.addTag(ArtificialTag(true))
                method.retrieveActiveBody().units.insertAfter(onReceiveStmt, registerStmt)
            }
        }
    }


    private fun insertNewViewStmtBefore(allocSite: Stmt, view: Value, varType: RefType, body: Body): Stmt {
        val newExpr = Jimple.v().newNewExpr(varType)
        val viewNewStmt = Jimple.v().newAssignStmt(view, newExpr)
        viewNewStmt.addTag(ArtificialTag(false))
        body.units.insertBefore(viewNewStmt, allocSite)
        return viewNewStmt
    }

    private fun insertNewViewStmtAfter(allocSite: Stmt, view: Value, varType: RefType, body: Body): Stmt {
        val newExpr = Jimple.v().newNewExpr(varType)
        val viewNewStmt = Jimple.v().newAssignStmt(view, newExpr)
        viewNewStmt.addTag(ArtificialTag(false))
        body.units.insertAfter(viewNewStmt, allocSite)
        return viewNewStmt
    }

    //not use
    private fun createViewConstructor(activeBody: Body, viewVar: Local, viewNewStmt: Stmt, findViewStmt: Stmt) {
        // do we need to add constructor?
        // may be easier with an artificial constructor
        val varRefType = viewVar.type as RefType
        val constructorParams = LinkedList<Value>()
        constructorParams.add(NullConstant.v()) // don't create Context, set it to null; shall we try to find it in other statements instead???
        val viewConstructor = varRefType.sootClass.getMethod(VIEW_CONSTRUCTOR_SIGNATURE2)
        val viewConstructorExpr = Jimple.v().newSpecialInvokeExpr(viewVar, viewConstructor.makeRef(), constructorParams)
        val viewConstructorStmt = Jimple.v().newInvokeStmt(viewConstructorExpr)
        activeBody.units.insertAfter(viewConstructorStmt, viewNewStmt)
        //add setId statement
        val viewId = findViewStmt.invokeExpr.getArg(0)// View.findViewById(int)
        // set Id from findViewById
        val setIdParams = LinkedList<Value>()
        setIdParams.add(viewId)
        val setIdMethod = varRefType.sootClass.getMethod(VIEW_SET_ID_SIGNATURE)
        val setViewIdExpr = Jimple.v().newSpecialInvokeExpr(viewVar, setIdMethod.makeRef(), setIdParams)
        val setViewIdStmt = Jimple.v().newInvokeStmt(setViewIdExpr)
        setViewIdStmt.addTag(ArtificialTag(false))
        activeBody.units.insertAfter(setViewIdStmt, viewConstructorStmt)
    }

    /**
     * From Flowdroid
     *
     * @param callgraphAlgo
     */
    private fun configureCallgraph(callgraphAlgo: CallgraphAlgorithm) {
        // Configure the callgraph algorithm
        when (callgraphAlgo) {
            CallgraphAlgorithm.AutomaticSelection, CallgraphAlgorithm.SPARK -> Options.v().setPhaseOption(
                "cg.spark", "on"
            )
            CallgraphAlgorithm.GEOM -> {
                Options.v().setPhaseOption("cg.spark", "on")
                AbstractInfoflow.setGeomPtaSpecificOptions()
                Options.v().setPhaseOption("cg.spark", "simplify-offline:false")
                Options.v().setPhaseOption("cg.spark", "geom-runs:2")
            }
            CallgraphAlgorithm.CHA -> Options.v().setPhaseOption("cg.cha", "on")
            CallgraphAlgorithm.RTA -> {
                Options.v().setPhaseOption("cg.spark", "on")
                Options.v().setPhaseOption("cg.spark", "rta:true")
                Options.v().setPhaseOption("cg.spark", "on-fly-cg:false")
            }
            CallgraphAlgorithm.VTA -> {
                Options.v().setPhaseOption("cg.spark", "on")
                Options.v().setPhaseOption("cg.spark", "vta:true")
            }
            else -> throw IllegalArgumentException("Invalid callgraph algorithm")
        }
        // Options.v().setPhaseOption("cg", "types-for-invoke:true"); for reflection
    }

}



