package saarland.cispa.frontmatter.resolvers

import saarland.cispa.frontmatter.ApkInfo
import saarland.cispa.frontmatter.Resolver
import saarland.cispa.frontmatter.Utils.implements
import saarland.cispa.frontmatter.Utils.isAndroidClass
import saarland.cispa.frontmatter.Utils.isAndroidMethod
import saarland.cispa.frontmatter.Utils.isSensitiveSystemMethod
import saarland.cispa.frontmatter.activityLifecycleSubsignatures
import saarland.cispa.frontmatter.broadcastLifecycleSubsignatures
import saarland.cispa.frontmatter.cg.ArtificialTag
import saarland.cispa.frontmatter.cg.InterproceduralCFGWrapper
import saarland.cispa.frontmatter.contentResolverSignatures
import saarland.cispa.frontmatter.data.StmtSite
import saarland.cispa.frontmatter.dummyMainClass
import saarland.cispa.frontmatter.dummyMainMethod
import saarland.cispa.frontmatter.registerBroadcastSubsignatures
import saarland.cispa.frontmatter.results.ApiCall
import saarland.cispa.frontmatter.serviceLifecycleSubsignatures
import saarland.cispa.frontmatter.startServiceSubsignatures
import saarland.cispa.frontmatter.uiCallbacks
import soot.MethodOrMethodContext
import soot.NullType
import soot.RefType
import soot.Scene
import soot.SootClass
import soot.SootMethod
import soot.jimple.AssignStmt
import soot.jimple.InvokeStmt
import soot.jimple.NewExpr
import soot.jimple.Stmt
import soot.jimple.infoflow.android.callbacks.AndroidCallbackDefinition
import soot.jimple.infoflow.android.entryPointCreators.AndroidEntryPointConstants
import soot.jimple.toolkits.callgraph.Filter
import soot.jimple.toolkits.callgraph.ReachableMethods
import soot.util.MultiMap

data class BroadcastReceiverApi(val name: String, val intents: Set<String>, val apis: List<ApiCall>)

open class ApiResolver(
    sceneV: Scene, val icfg: InterproceduralCFGWrapper, val valueResolver: ValueResolver, val apkInfo: ApkInfo
) : Resolver(sceneV) {

    internal val resourceParser = apkInfo.resourceParser
    private var dynamicBroadcasts: MutableMap<String, Set<String>> = mutableMapOf()
    private val stringResolver = StringResolver(valueResolver, resourceParser)
    private val scBroadcastReceiver = sceneV.getSootClassUnsafe(AndroidEntryPointConstants.BROADCASTRECEIVERCLASS)
    private val intentResolver = IntentResolver(sceneV, valueResolver, emptyMap(), resourceParser) // pass empty intentFilters as it's not relevant for services

    fun resolve() {
        throw NotImplementedError("UIApiResolution should be used to resolve API")
    }

    /***
     * collect all APIs without param resolution
     */
    fun collectAllApi(resolveArguments: Boolean): Collection<ApiCall> {
        val dummyMain = sceneV.getSootClass(dummyMainClass).getMethodByName(dummyMainMethod)
        val apiStmtSites = getReachableApi(listOf(dummyMain), true)
        return if (resolveArguments) {
            apiStmtSites.map { addApiMeta(it) }.toList()
        } else
            apiStmtSites.map { ApiCall(it.getInvokeExpr().method, "") }.toList()
    }

    fun collectApiFromUICAllbacks(callbacks: MultiMap<SootClass, AndroidCallbackDefinition>, resolveArguments: Boolean): Collection<ApiCall> {
        val viewClass = sceneV.getSootClass("android.view.View")
        val uiCallbackList = callbacks // or use callbackType
            .map { it.o2 }
            .filter { it.parentMethod.declaringClass.name in uiCallbacks }
            .map { it.targetMethod }
        val apiStmtSites = getReachableApi(uiCallbackList, true)
        return if (resolveArguments) {
            apiStmtSites.map { addApiMeta(it) }.toList()
        } else
            apiStmtSites.map { ApiCall(it.getInvokeExpr().method, "") }.toList()
    }


    fun resolveActivityLCApi(activities: Collection<SootClass>): Map<String, List<ApiCall>> {
        return activities.associate { it.name to resolveLifecycle(it, activityLifecycleSubsignatures) }
    }

    fun resolveServiceLCApi(services: Collection<SootClass>): Map<String, List<ApiCall>> {
        return services.associate { it.name to resolveLifecycle(it, serviceLifecycleSubsignatures) }
    }

    fun resolveBroadcastApi(): Collection<BroadcastReceiverApi> {
        // resolve api calls from receivers
        // we need all receivers, not only declared in manifest
        val broadcastReceivers = sceneV.applicationClasses.filter { sceneV.implements(it, scBroadcastReceiver) }
        resolveDynamicBroadcasts()
        return broadcastReceivers.map {
            val intentFilters = getIntentFilter(it)
            val apis = resolveLifecycle(it, broadcastLifecycleSubsignatures)
            BroadcastReceiverApi(it.name, intentFilters, apis)
        }
    }

    private fun resolveDynamicBroadcasts() {
        val registerSites = collectRegisterSites()
        registerSites.forEach { it ->
            val broadcasts = resolveBroadcast(it)
            val filters = resolveIntentFilter(it)
            broadcasts.forEach { broadcast ->
                dynamicBroadcasts[broadcast] = filters
            }
        }
    }

    private fun resolveBroadcast(registerSite: StmtSite): Set<String> {
        val broadcast = registerSite.getInvokeExpr().getArg(0)
        val broadcastType = (broadcast.type as? RefType) ?: return emptySet()
        return if (broadcastType.className == "android.content.BroadcastReceiver" || !broadcastType.sootClass.isConcrete) {
            val broadcastStmts = valueResolver.resolveArg(registerSite, 0, null) // TODO: specify scope using context activity/fragment
            broadcastStmts.filter { it.isAssignStmt() }
                .mapNotNull {
                    require(it.stmt is AssignStmt)
                    val rightValue = it.stmt.rightOp
                    when {
                        rightValue is NewExpr -> rightValue.baseType.className
                        rightValue.type is RefType -> {
                            val leftValue = it.stmt.leftOp as RefType
                            leftValue.className
                        }
                        rightValue.type is NullType -> {
                            null
                        }
                        else -> {
                            null
                        }
                    }
                }.toSet()
        } else setOf(broadcastType.className)

    }

    private fun resolveIntentFilter(site: StmtSite): Set<String> {
        val intentFilterStmts = valueResolver.resolveArg(site, 1, null) // TODO: specify scope using context activity/fragment
        return intentFilterStmts.flatMap { stringResolver.resolveAssignedString(it) }.toSet()
    }

    private fun getIntentFilter(broadcastReceiver: SootClass): Set<String> {
        return apkInfo.broadcastIntentFilters[broadcastReceiver.name] ?: dynamicBroadcasts[broadcastReceiver.name] ?: emptySet()
    }

    private fun collectRegisterSites(): Sequence<StmtSite> {
        val allMethods = sceneV.applicationClasses.flatMap { it.methods }
            .asSequence()
            .filter { it.isConcrete }
        return allMethods.flatMap { findRegisterBroadcastSites(it) }
    }

    private fun findRegisterBroadcastSites(method: SootMethod): Sequence<StmtSite> {
        require(method.isConcrete)
        return method.retrieveActiveBody().units.asSequence()
            .filterIsInstance<Stmt>()
            .filter { it.containsInvokeExpr() }
            .filter { it.invokeExpr.method.subSignature in registerBroadcastSubsignatures }
            .map { StmtSite(it, method) }
    }

    private fun resolveLifecycle(component: SootClass, lifecycleSubsignatures: Set<String>): List<ApiCall> {
        val lifecycleMethods = lifecycleSubsignatures.mapNotNull { getTargetMethodBySubsignature(component, it) }
        return lifecycleMethods.flatMap { resolveApiForCallback(it) }
    }

    internal fun resolveApiForCallback(callback: SootMethod): List<ApiCall> {
        return getReachableApi(listOf(callback), false).map { addApiMeta(it) }.toList()
    }

    internal fun addApiMeta(stmtSite: StmtSite): ApiCall {
        val apiMeta = when {
            stmtSite.getInvokeExpr().method.signature in contentResolverSignatures -> {
                resolveContentResolver(stmtSite)
            }
            stmtSite.getInvokeExpr().method.subSignature in startServiceSubsignatures -> {
                resolveServiceIntent(stmtSite)
            }
            else -> ""
        }
        return ApiCall(stmtSite.getInvokeExpr().method, apiMeta)
    }

    /**
     * return set of services
     */
    private fun resolveServiceIntent(stmtSite: StmtSite): String {
        val scope = getReachableMethods(setOf(stmtSite.method), false).map { it.method().signature }.toSet()
        val intentAllocationSites = valueResolver.resolveArg(stmtSite, 0, scope) //resolver returns not the Intent, but string
        val targets = intentAllocationSites.mapNotNull { intentResolver.getTargetsFromIntent(it) }.toSet()
        return targets.mapNotNull { it.name }.joinToString("|")
    }

    private fun resolveContentResolver(stmtSite: StmtSite): String {
        val scope = getReachableMethods(setOf(stmtSite.method), false).map { it.method().signature }.toSet() //FIXME: can be incorrect
        val uriStmt = stringResolver.resolveUri(stmtSite, scope)
        return uriStmt.joinToString("|")
    }

    private fun scanAndroidApi(method: SootMethod): Sequence<StmtSite> {
        return method.activeBody.units.asSequence()
            .filterIsInstance<Stmt>()
            .mapNotNull {
                when {
                    it is InvokeStmt -> StmtSite(it, method)
                    it is AssignStmt && (it.containsInvokeExpr()) -> StmtSite(it, method)
                    else -> null
                }
            }
            .filter { it.getInvokeExpr().method.isAndroidMethod() || it.getInvokeExpr().method.isSensitiveSystemMethod() }
    }

    internal fun getReachableMethods(entryPoints: Collection<SootMethod>, includeArtificialEdges: Boolean): Sequence<MethodOrMethodContext> {
        val filter = Filter { edge ->
            val sourceClass = edge.src().declaringClass
            val isIgnored = (edge.srcUnit()?.getTag("artificialTag") as? ArtificialTag)?.isIgnored ?: false
            !sourceClass.isAndroidClass() && (includeArtificialEdges || !isIgnored)
        }
        val reachableMethods = ReachableMethods(callgraph, entryPoints.iterator(), filter)
        reachableMethods.update()
        return reachableMethods.listener().asSequence()
    }

    internal fun getReachableApi(seeds: List<SootMethod>, includeArtificialEdges: Boolean): Sequence<StmtSite> {
        return getReachableMethods(seeds.filter { !it.isAndroidMethod() }, includeArtificialEdges)
            .map { it.method() }
            .filter { it.isConcrete }
            .flatMap { scanAndroidApi(it) }
    }

}

