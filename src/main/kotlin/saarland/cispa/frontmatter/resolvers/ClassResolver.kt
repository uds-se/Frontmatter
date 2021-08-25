package saarland.cispa.frontmatter.resolvers

import mu.KLogging
import saarland.cispa.frontmatter.data.StmtSite
import soot.RefType
import soot.Scene
import soot.SootClass
import soot.Value
import soot.jimple.AssignStmt
import soot.jimple.ClassConstant
import soot.jimple.NewExpr
import soot.jimple.NullConstant
import soot.jimple.StringConstant

class ClassResolver(val sceneV: Scene, val valueResolver: ValueResolver) {
    companion object : KLogging()

    /**
     * consider class name can be either const string or class.getName()
     */
    internal fun resolveClassName(classNameAllocSite: StmtSite): Collection<SootClass> {
        require(classNameAllocSite.stmt is AssignStmt)
        val stmt = classNameAllocSite.stmt
        val rightOp = stmt.rightOp
        if (rightOp is NullConstant)
            return emptySet()
        if (rightOp is StringConstant)
            return setOf(sceneV.getSootClass(rightOp.value))
        logger.warn("Cannot resolve class name string @$classNameAllocSite")
        return emptySet()
    }

    internal fun resolveClassConstant(arg: ClassConstant): SootClass {
        return sceneV.getSootClass(arg.toSootType().toQuotedString())
    }

    /**
     * Object instantiation: can be at least newExpr or a constructor
     *
     * redundant after pass-through
     * */
    @Deprecated("redundant after pass-through")
    private fun resolveObject(stmt: StmtSite, variable: Value): Collection<SootClass> {
        val possibleObjects = valueResolver.resolveVar(variable, stmt, null)
        return possibleObjects.map { it.stmt }
            .filterIsInstance<AssignStmt>()
            .filter { it.rightOp is NewExpr }
            .map { it.rightOp.type }
            .filterIsInstance<RefType>()
            .map { it.sootClass }
    }

    /**
     * variable of type Class
     * */
    private fun resolveClassVar(stmtSite: StmtSite): Collection<SootClass> {
        require(stmtSite.stmt is AssignStmt) { "Should be assign statement" }
        val stmt: AssignStmt = stmtSite.stmt
        val rightOp = stmt.rightOp
        if (rightOp is NullConstant)
            return emptySet()
        if (rightOp is ClassConstant)
            return setOf(resolveClassConstant(rightOp))
        if (rightOp is StringConstant)
            return setOf(sceneV.getSootClass(rightOp.value))
        if (rightOp is NewExpr && rightOp.type is RefType) // redundant?
            return setOf((rightOp.type as RefType).sootClass)
        logger.warn("Cannot resolve class name string @ $stmt")
        return emptySet()
    }

    internal fun resolveClass(stmtSite: StmtSite, variable: Value): Collection<SootClass> {
        val possibleClasses = valueResolver.resolveVar(variable, stmtSite, null).filter { it.stmt is AssignStmt }
        if (possibleClasses.isEmpty()) {
            logger.warn("Intent target class not resolved @ $possibleClasses")
            return emptySet()
        }
        logger.debug("Class variable resolution: $possibleClasses")
        return possibleClasses.flatMap { resolveClassVar(it) }
    }
}
