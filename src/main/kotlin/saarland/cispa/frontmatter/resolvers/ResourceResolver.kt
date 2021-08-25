package saarland.cispa.frontmatter.resolvers


import mu.KLogging
import saarland.cispa.frontmatter.Resolver
import saarland.cispa.frontmatter.Utils.getIntConstant
import saarland.cispa.frontmatter.Utils.getTargetMethodByName
import saarland.cispa.frontmatter.Utils.isAndroidClass
import saarland.cispa.frontmatter.data.StmtSite
import saarland.cispa.frontmatter.setImageSubsignatures
import soot.RefType
import soot.SootMethod
import soot.jimple.AssignStmt
import soot.jimple.InvokeStmt
import soot.jimple.NewExpr
import soot.jimple.NullConstant
import soot.jimple.StaticFieldRef


/**
 *
 * TODO: add Uri.Builder Builder.build()
 */

//data class ViewId(val value: Int, val origin: SootClass?)

data class ResourceId(val value: Int, val location: StmtSite?)
typealias ViewId = ResourceId

class ResourceResolver(private val valueResolver: ValueResolver) : Resolver(valueResolver.sceneV) {
    val icfg = valueResolver.icfg
    private val adapterInterface = sceneV.getSootClass("android.widget.Adapter")

    companion object : KLogging()

    fun resolveResourceId(stmtSite: StmtSite, scope: Set<String>?, argId: Int = 0): List<ResourceId> {
        require(stmtSite.stmt.containsInvokeExpr()) { "Missing InvokeExpr: $stmtSite" }
        val resourceIdAllocations = valueResolver.resolveArg(stmtSite, argId, scope)
        return resourceIdAllocations.mapNotNull {
            if (it.stmt is InvokeStmt) {
                logger.warn("Failed to resolve resource: Unexpected InvokeStmt @ ${it.stmt}")
            }
            val value = getIntConstant((it.stmt as AssignStmt).rightOp)
            if (value != null)
                ResourceId(value, it)
            else {
                logger.warn("Ambiguous resource id @ ${it.stmt}")
                null
            }
        }
    }

    fun resolveImage(stmtSite: StmtSite, scope: MutableSet<String>): List<ResourceId> {
        //receive invokeExpr with setImageSubsignatures
        require(stmtSite.containsInvokeExpr())
        if (stmtSite.getInvokeExprSubsignature() !in setImageSubsignatures) return emptyList()
        return resolveResourceId(stmtSite, scope, 0)
    }

    private fun getCallbackMethodForListener(name: String): String {
        if (name in setOf("setPositiveButton", "setNegativeButton", "setNeutralButton")) return "onClick"
        return name.replace(Regex("^set"), "").replace(Regex("Listener$"), "").decapitalize()
    }

    fun resolveListener(stmtSite: StmtSite, scope: Set<String>?, argId: Int = 0): Set<SootMethod> {
        require(stmtSite.containsInvokeExpr())
        val callbackName = getCallbackMethodForListener(stmtSite.getInvokeExpr().methodRef.name)
        // simplify listener resolution using types
        val listenerAllocations = valueResolver.resolveArg(stmtSite, argId, scope)
        return listenerAllocations.mapNotNull { listenerAllocation ->
            require(listenerAllocation.stmt is AssignStmt)
            when (listenerAllocation.stmt.rightOp) {
                is NullConstant -> null
                is NewExpr -> getTargetMethodByName((listenerAllocation.stmt.rightOp as NewExpr).baseType.sootClass, callbackName)
                is StaticFieldRef -> getListenerFromStaticField(listenerAllocation.stmt.rightOp as StaticFieldRef, callbackName)
                else -> error("Unexpected listener statement: $listenerAllocation for setListener: $stmtSite")
            }
        }.toSet()
    }

    /**
     * scan <clinit> method for static field assignments
     * for now use simple resolution based on type
     */
    private fun getListenerFromStaticField(fieldRef: StaticFieldRef, callbackName: String): SootMethod? {
        val fieldClass = fieldRef.fieldRef.declaringClass()
        val clInit = fieldClass.getMethodByName("<clinit>")
        val fieldAssignment = clInit.activeBody.units.asSequence()
            .filterIsInstance<AssignStmt>()
            .firstOrNull { fieldRef.equivTo(it.leftOp) }
        if (fieldAssignment != null) {
            val listenerClass = (fieldAssignment.rightOp.type as? RefType)?.sootClass
            if (listenerClass != null && !listenerClass.isInterface && !listenerClass.isAndroidClass()) // XXX: may produce wrong result in case of superclass
                return getTargetMethodByName(listenerClass, callbackName)
        }
//        val scope = getReachableMethods(listOf(clInit))
//            .map { it.method() }
//            .filter { it.isConcrete }
//            .filterNot { it.isSystemMethod() }
//            .filterNot { it.isAndroidMethod() }
//            .map { it.signature }
//            .toSet()
//        val fd = valueResolver.resolveVar(fieldAssignment.leftOp, StmtSite(clInit.activeBody.units.getSuccOf(fieldAssignment) as Stmt, clInit), scope)
//        var ff = fieldAssignment.leftOp.equals(fieldRef)
        return null


    }

}

