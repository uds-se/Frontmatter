package saarland.cispa.frontmatter.resolvers


import mu.KLogging
import saarland.cispa.frontmatter.ResourceParser
import saarland.cispa.frontmatter.Utils.getIntConstant
import saarland.cispa.frontmatter.Utils.getStringConstant
import saarland.cispa.frontmatter.Utils.isAndroidClass
import saarland.cispa.frontmatter.data.StmtSite
import saarland.cispa.frontmatter.prefStringSignatures
import saarland.cispa.frontmatter.resourcesActivityGetStringSubsignatures
import saarland.cispa.frontmatter.resourcesGetStringSignatures
import saarland.cispa.frontmatter.stringBuilderToString
import saarland.cispa.frontmatter.stringFormatSignatures
import soot.Local
import soot.RefType
import soot.jimple.AssignStmt
import soot.jimple.InvokeExpr
import soot.jimple.NewExpr
import soot.jimple.NullConstant
import soot.jimple.StaticFieldRef


class StringResolver(private val valueResolver: ValueResolver, private val resourceParser: ResourceParser) {
    val icfg = valueResolver.icfg
    val sceneV = valueResolver.sceneV
    private val resourceResolver = ResourceResolver(valueResolver)

    companion object : KLogging()

    fun resolveStringArg(stmtSite: StmtSite, argId: Int, scope: Set<String>? = null): Set<String> {
        val stmt = stmtSite.stmt
        require(stmt.containsInvokeExpr())
        val allocationSites = valueResolver.resolveArg(stmtSite, argId, scope)
        return allocationSites.filter { it.stmt is AssignStmt }.flatMap { resolveAssignedString(it, scope, allocationSites.toMutableSet().also { s -> s.remove(it) }) }.toSet()
    }

    /**
     * Resolve String s = ?
     * ? is either:
     *   const String
     *   Resources.getString
     *   <java.lang.StringBuilder: java.lang.String toString()>
     *   .format(type)
     *   java.lang.StringBuilder
     * any other .toString() is not supported now
     *
     * TODO String.format()
     * */
    fun resolveAssignedString(stmtSite: StmtSite, scope: Set<String>? = null, visitedNodes: MutableSet<StmtSite> = mutableSetOf()): List<String> {
        if (stmtSite in visitedNodes)
            return emptyList()
        visitedNodes.add(stmtSite)
        val stmt = stmtSite.stmt
        require(stmt is AssignStmt)
        if (stmt.rightOp is NewExpr) return emptyList()
        val value = stmt.rightOp// XXX: use first, drop other strings found
        //try to resolve the content by type
        // XXX: ? simplify at the expence of precision to avoid recursion and long processing
        getStringConstant(value)?.let { return listOf(it) }
        getIntConstant(value)?.let {
            val stringFromRes = resourceParser.strings[it]
            if (stringFromRes != null) return listOf(stringFromRes)
        }
        if (stmt.rightOp is NullConstant)
            return emptyList()
        if (!stmt.containsInvokeExpr()) {
            logger.warn("Unexpected value for string $value")
            return emptyList()
        }
        when {
            stmt.invokeExpr.method.signature in stringFormatSignatures -> { // XXX: ? simplify at the expence of precision to avoid recursion and long processing
                return stmt.invokeExpr.args
                    .filter { it.type == RefType.v("java.lang.String") }
                    .flatMap { valueResolver.resolveVar(it, stmtSite, scope) }
                    .flatMap { resolveAssignedString(it) }
            }
            stmt.invokeExpr.method.signature in prefStringSignatures -> return resolveSharedPref(stmtSite, scope)
            stmt.invokeExpr.method.signature == stringBuilderToString -> return resolveStringBuilderToString(stmtSite, scope, visitedNodes)
//            stmt.invokeExpr.method.name == "format" -> { //try to resolve the content by type
//                return listOf(resolveByFormat(stmt))
//            }
            stmt.invokeExpr.method.signature in resourcesGetStringSignatures ||
                stmt.invokeExpr.method.subSignature in resourcesActivityGetStringSubsignatures -> {
                val resourceId = resourceResolver.resolveResourceId(stmtSite, scope = scope)
                if (resourceId.size > 1) logger.warn("Multiple resource ids found")
                if (resourceId.isEmpty()) logger.warn("Failed to resolve resourceId @ $stmtSite")
                return resourceId.mapNotNull { resourceParser.strings[it.value] }
            }
            else -> {
                return emptyList()
            }
        }
    }

    private fun resolveSharedPref(stmtSite: StmtSite, scope: Set<String>?): List<String> {
        return resolveStringArg(stmtSite, 0, scope).toList()
    }

    /**
     * resolve from <java.lang.StringBuilder: java.lang.String toString()>
     * collect arguments of StringBuilder.append(), StringBuilder instatiation and constructor statements are ignored
     * */
    private fun resolveStringBuilderToString(stmtSite: StmtSite, scope: Set<String>?, visitedNodes: MutableSet<StmtSite>): List<String> {
        require(stmtSite.stmt.containsInvokeExpr())
        val stringBuilderAllocations = valueResolver.resolveInvokerBase(stmtSite, scope)
        // try to order append statements
        val appendStmts = stringBuilderAllocations
            .filterNot { it in visitedNodes }
            .filterNot { it == stmtSite }
            //.filter { it.stmt.containsInvokeExpr() }
            .map { resolveAssignedString(it, visitedNodes = visitedNodes).joinToString() }
        return appendStmts
//            .filter { it.stmt.invokeExpr.method.signature == "<java.lang.StringBuilder: java.lang.StringBuilder append(java.lang.String)>" } // XXX:
//            .sortedWith(Comparator { a, b ->
//                when (a.method) {
//                    b.method -> {
//                        val ug = ExceptionalUnitGraph(a.method.activeBody)
//                        val pt = PseudoTopologicalOrderer<Unit>().newList(ug, false)
//                        compareValues(pt.indexOf(a.stmt), pt.indexOf(b.stmt))
//                    }
//                    else -> 0
//                }
//            })
//        val res = mutableListOf<String>()
//        for (appendStmt in appendStmts) {
//            try {
//                val v = valueResolver.resolveArg(appendStmt, 0).flatMap { resolveAssignedString(it) }//.joinToString("")
//                res.addAll(v)
//            } catch (e: StackOverflowError) { //as we may end up with recursion here
//                logger.warn("recursion in String resolution detected")
//            }
//        }
//        return res
    }

    private fun resolveUriValue(stmtSite: StmtSite, scope: Set<String>): Collection<String> {
        val stmt = stmtSite.stmt
        if (stmt !is AssignStmt) return emptyList()
        val value = stmt.rightOp
        if (value is StaticFieldRef)
            return resolveUriStaticField(value, scope)
        if (value is NullConstant)
            return emptyList()
        if (value !is InvokeExpr) {
            logger.warn("Wrong Boomerang resolution or URL: stmt $stmtSite")
            return emptyList()
        }
        when (value.method.signature) {
            "<android.net.Uri: android.net.Uri fromParts(java.lang.String,java.lang.String,java.lang.String)>",
            "<android.net.Uri: android.net.Uri parse(java.lang.String)>" -> {
                val stringAllocations = valueResolver.resolveArg(stmtSite, 0, scope)
                return stringAllocations.flatMap { resolveAssignedString(it, scope = scope) }
            }
            "<android.net.Uri: android.net.Uri withAppendedPath(android.net.Uri,java.lang.String)>" -> {
                val uriAllocations = valueResolver.resolveArg(stmtSite, 0, scope)
                return uriAllocations.flatMap { resolveUriValue(it, scope) }
            }
        }
        return emptyList()
    }

    fun resolveUri(stmtSite: StmtSite, scope: Set<String>): Collection<String> {
        require(stmtSite.stmt.containsInvokeExpr())
        val invokeExpr = stmtSite.stmt.invokeExpr
        val uriParam = invokeExpr.args.filter { it.type is RefType }.single { it.type == RefType.v("android.net.Uri") }
        val uriAllocations = valueResolver.resolveVar(uriParam, stmtSite, null)
        return uriAllocations.flatMap { resolveUriValue(it, scope) }
    }

    private fun resolveUriStaticField(fieldRef: StaticFieldRef, scope: Set<String>): Collection<String> {
        val declaringClass = fieldRef.field.declaringClass
        if (declaringClass.isAndroidClass())
            return listOf(fieldRef.field.signature)
        if (declaringClass.declaresMethod("void <clinit>()")) {
            val clinit = declaringClass.getMethod("void <clinit>()")
            val fieldAllocation = clinit.activeBody.units
                .filterIsInstance<AssignStmt>()
                //      .lastOrNull { (it.leftOp as StaticFieldRef).field == fieldRef.field }
                .lastOrNull { it.leftOp.toString() == fieldRef.toString() } ?: return emptyList()
            val fieldAllocationSite = StmtSite(fieldAllocation, clinit)
            if (fieldAllocation.rightOp is Local)
                return valueResolver.resolveVar(fieldAllocation.rightOp, fieldAllocationSite, null).flatMap { resolveUriValue(it, scope) }
            if (fieldAllocation.rightOp is InvokeExpr)
                return resolveUriValue(fieldAllocationSite, scope)
//            val uriAllocation = valueResolver.resolveVar(uriParam, fieldAllocation)
        }
        return emptyList()
    }
}

