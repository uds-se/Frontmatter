package saarland.cispa.frontmatter.resolvers

import mu.KLogging
import saarland.cispa.frontmatter.ActivityClass
import saarland.cispa.frontmatter.ResourceParser
import saarland.cispa.frontmatter.Utils.getStringConstant
import saarland.cispa.frontmatter.data.StmtSite
import soot.NullType
import soot.RefType
import soot.Scene
import soot.SootClass
import soot.jimple.AssignStmt
import soot.jimple.ClassConstant
import soot.jimple.InvokeStmt
import soot.jimple.StaticFieldRef
import soot.jimple.StringConstant

/**
 * Context parameter may point out to the caller activity TODO: check
 *
 * Intent constructors:
 *
 */

class IntentResolver(private val sceneV: Scene, private val valueResolver: ValueResolver,
                     private val intentFilters: Map<String, Set<String>>,
                     resources: ResourceParser) {
    companion object : KLogging()

    private val classResolver = ClassResolver(sceneV, valueResolver)
    private val stringResolver = StringResolver(valueResolver, resources)

    /**
     * Value resolver returns both possible Intent
     * Intent allocation should be either a constructor or a setter
     * XXX: Intent can be obtained from other sources (e.g. returned by an invocation) - this method cannot process these cases!!!
     *
     * @param intentAllocationStmtSite
     * @return
     */
    fun getTargetsFromIntent(intentAllocationStmtSite: StmtSite): ActivityClass? {
        if (intentAllocationStmtSite.stmt !is AssignStmt) {
            logger.warn("Unexpected invokeExpr: $intentAllocationStmtSite")
            return null
        }
        if (intentAllocationStmtSite.stmt.rightOp.type is NullType) {
            return null
        }
        val className = when (val value = intentAllocationStmtSite.stmt.rightOp) {
            is ClassConstant -> value.toSootType().toQuotedString()
            is StringConstant,
            is StaticFieldRef -> getStringConstant(value)
            else -> null
        }
        return if (className != null && className.isNotBlank())
            sceneV.getSootClassUnsafe(className) ?: run {
                logger.warn("Got URL: $className instead of class @ $intentAllocationStmtSite")
                null
            } else {
            logger.warn("Cannot resolve intent @ $intentAllocationStmtSite")
            null
        }
//        val invokeExpr = intentAllocationStmtSite.stmt.invokeExpr
//        return when (invokeExpr.method.signature) {
//            in intentConstructors -> {
//                getTargetFromIntentConstructor(intentAllocationStmtSite)
//            }
//            in intentClassSetters -> {
//                getClassFromIntentSetter(intentAllocationStmtSite)
//            }
//            else -> {
//                logger.warn("Unknown Intent allocation statement $intentAllocationStmtSite")
//                return emptySet()
//            }
//        }
    }

    /**
     * Context parameter may point out to the caller activity TODO: check
     * |
     * Intent constructors:
     * Intent()
     * Intent(Intent o)
     * Intent(String action)
     * Intent(String action, Uri uri)
     * Intent(Context packageContext, Class cls)
     * Intent(String action, Uri uri, Context packageContext, Class cls)
     *
     * @param constructorStmtSite constructor statement to investigate
     * @return
     */
    private fun getTargetFromIntentConstructor(constructorStmtSite: StmtSite): Collection<SootClass> {
        val constructor = constructorStmtSite.stmt.invokeExpr
        if (constructor.argCount == 0) {
            logger.warn("Empty Intent constructor @ $constructorStmtSite, check setters")
            return emptySet()
        }
        // TODO: refactor
        val r = constructor.method.subSignature
        for (arg in constructor.args) {
            // Intent(Context packageContext, Class cls)
            if (arg is ClassConstant) {
                val target = classResolver.resolveClassConstant(arg)
                return setOf(target)
            }
            when (arg.type) {
                // Intent(Intent o)
                RefType.v("android.content.Intent") -> {
                    logger.warn("Intent copy constructor found: $arg, not supported")
                    return emptySet()
                }
                // Intent(Context packageContext, Class cls)
                RefType.v("java.lang.Class") -> {
                    logger.debug("Class var in Intent: $arg")
                    return classResolver.resolveClass(constructorStmtSite, constructor.getArg(1))
                }
                RefType.v("android.net.Uri") -> {
                    // XXX: no need to process Uri destinations
                    logger.warn("Url in Intent: $constructorStmtSite")
                    /*
                    val destUrl = valueResolver.resolveArg(constructorStmt, 1)
                    for (definitionStmt in destUrl) {
                        logger.warn("Intent Url found: $definitionStmt")
                    }*/
                }
                RefType.v("java.lang.String") -> {
                    // we found action, activity may define intent-filter with custom action
                    return resolveAction(constructorStmtSite) //FIXME: wrong, we may miss classes
                }
            }
        }
        logger.warn("Unknown Intent constructor statement $constructor")
        return emptySet()
    }

    private fun resolveAction(constructorStmtSite: StmtSite): List<SootClass> {
        val actionStrings = stringResolver.resolveStringArg(constructorStmtSite, 0)
        return actionStrings.onEach { logger.debug("ActionString found: $it @ ${constructorStmtSite.method}") }
            .flatMap { intentFilters.getOrDefault(it, emptySet()) }
            .map { sceneV.getSootClass(it) }
    }

    /**
     * android.content.Intent setClass(android.content.Context,java.lang.Class)
     * android.content.Intent setClassName(android.content.Context,java.lang.String)
     * android.content.Intent setClassName(java.lang.String,java.lang.String)
     * android.content.Intent setComponent(String,String)
     * */
    private fun getClassFromIntentSetter(stmtSite: StmtSite): Collection<SootClass> {
        require(stmtSite.stmt.containsInvokeExpr())
        val invokeExpr = stmtSite.stmt.invokeExpr
        when (invokeExpr.method.subSignature) {
            "android.content.Intent setClass(android.content.Context,java.lang.Class)" -> {
                val classVar = invokeExpr.getArg(1)
                return if (classVar is ClassConstant)
                    setOf(sceneV.getSootClass(classVar.toSootType().toQuotedString()))
                else {
                    classResolver.resolveClass(stmtSite, invokeExpr.getArg(1))
                }
            }
            "android.content.Intent setClassName(android.content.Context,java.lang.String)",
            "android.content.Intent setClassName(java.lang.String,java.lang.String)" -> {
                val className = invokeExpr.getArg(1)
                return if (className is StringConstant)
                    setOf(sceneV.getSootClass(className.value))
                else {
                    return stringResolver.resolveStringArg(stmtSite, 1).map { sceneV.getSootClass(it) }
                }
            }
            "android.content.Intent setComponent(android.content.ComponentName)" -> {
                return resolveComponentName(stmtSite)
            }
            "android.content.Intent setAction(java.lang.String)" -> {
                return resolveAction(stmtSite)
            }
        }
        logger.warn("Unknown Intent setter method @ $stmtSite")
        return emptySet()
    }


    /**
     * ComponentName(String pkg, String cls)
     * ComponentName(Context pkg, String cls)
     * ComponentName(Context pkg, Class<?> cls)
     */
    //FIXME: refactor
    private fun resolveComponentName(stmtSite: StmtSite): Collection<SootClass> {
        val varAllocSite = valueResolver.resolveArg(stmtSite, 0, null).filter { it.stmt is InvokeStmt } // it should be '<init>' instances
        return varAllocSite.flatMap { resolveComponentNameConstructor(it) }
    }

    private fun resolveComponentNameConstructor(constructorStmtSite: StmtSite): Collection<SootClass> {
        val constructor = constructorStmtSite.stmt.invokeExpr
        val arg = constructor.getArg(1)
        if (arg is ClassConstant) {
            val target = classResolver.resolveClassConstant(arg)
            return setOf(target)
        }
        when (arg.type) {
            RefType.v("java.lang.Class") -> {
                return classResolver.resolveClass(constructorStmtSite, constructor.getArg(1))
            }
            RefType.v("java.lang.String") -> {
                val cls = valueResolver.resolveArg(constructorStmtSite, 1, null).filter { it.stmt is AssignStmt }
                return cls.flatMap { classResolver.resolveClassName(it) }
            }
        }
        throw IllegalStateException("Unknown ComponentName constructor $constructorStmtSite")
    }


}
