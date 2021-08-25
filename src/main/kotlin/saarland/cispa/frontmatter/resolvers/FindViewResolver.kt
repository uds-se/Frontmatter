package saarland.cispa.frontmatter.resolvers

import mu.KLogging
import saarland.cispa.frontmatter.ActivityOrFragmentClass
import saarland.cispa.frontmatter.AppModel
import saarland.cispa.frontmatter.Utils.isAndroidMethod
import saarland.cispa.frontmatter.Utils.isSystemMethod
import saarland.cispa.frontmatter.Resolver
import saarland.cispa.frontmatter.ResourceParser
import saarland.cispa.frontmatter.Utils.isActivity
import saarland.cispa.frontmatter.Utils.isAndroidClass
import saarland.cispa.frontmatter.Utils.isDummy
import saarland.cispa.frontmatter.Utils.isReal
import saarland.cispa.frontmatter.XmlLayoutAnalysis
import saarland.cispa.frontmatter.data.StmtSite
import saarland.cispa.frontmatter.findViewByIdSubsignatures
import soot.Scene
import soot.SootMethod
import soot.jimple.AssignStmt
import soot.jimple.Stmt

class FindViewResolver(sceneV: Scene, private val appModel: AppModel, private val valueResolver: ValueResolver, private val resourceParser: ResourceParser,
                       private val layoutAnalysis: XmlLayoutAnalysis) : Resolver(sceneV) {
    companion object : KLogging()

    val activities = appModel.activities
    val fragments = appModel.fragments

    /**
     * resolve findViewById which we didn't find on a previous step
     * currently not used
     */
    private fun resolveViews() {
        for (activity in activities) {
            if (activity.isAndroidClass()) // ignore default activities
                continue
            if (!activity.isReal()) // ignore default activities
                continue
            logger.info("Resolve Views for $activity")
            val reachableMethods = getReachableMethodsOfActivity(activity)
                .filter { it.isConcrete }
                .filterNot { it.isSystemMethod() }
                .filterNot { it.isAndroidMethod() }
                .filterNot { it.isDummy() }
            resolveViewsFrom(activity, reachableMethods)
        }
        for (fragment in fragments) {
            if (fragment.isAndroidClass()) // ignore default activities
                continue
            if (!fragment.isReal()) // ignore default activities
                continue
            logger.info("Resolve Views for $fragment")
            val reachableMethods = getReachableMethodsOfActivity(fragment)
                .filter { it.isConcrete }
                .filterNot { it.isSystemMethod() }
                .filterNot { it.isAndroidMethod() }
                .filterNot { it.isDummy() }
            resolveViewsFrom(fragment, reachableMethods)
        }
    }

    fun resolveViewsFrom(activity: ActivityOrFragmentClass, reachableMethods: Sequence<SootMethod>) {
        val viewResolver = ViewResolver(activity, appModel, sceneV, valueResolver, resourceParser, layoutAnalysis)
        val findViewByIdSites = reachableMethods
            .flatMap { findFindViewByIdSites(it) }
            .toList()
        for (findViewByIdSite in findViewByIdSites) {
            viewResolver.resolveActivityFindViewById(findViewByIdSite).forEach { viewResolver.bindViewAttrs(it, it.location!!, findViewByIdSite.method) }
        }
    }

    private fun findFindViewByIdSites(method: SootMethod): Sequence<StmtSite> {
        require(method.isConcrete)
        val activeBody = method.retrieveActiveBody()
        return activeBody.units.asSequence()
            .filterIsInstance<Stmt>()
            .filter { it.containsInvokeExpr() }
            .filter { it.invokeExpr.method.subSignature in findViewByIdSubsignatures && it.invokeExpr.method.declaringClass.isActivity() }
            .filter { it is AssignStmt }
            .map { StmtSite(it, method) }
    }
}
