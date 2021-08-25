package saarland.cispa.frontmatter

import mu.KLogging
import saarland.cispa.frontmatter.resolvers.ValueResolver
import saarland.cispa.frontmatter.Utils.isActivity
import saarland.cispa.frontmatter.Utils.isAndroidClass
import saarland.cispa.frontmatter.Utils.isAndroidMethod
import saarland.cispa.frontmatter.Utils.isDummy
import saarland.cispa.frontmatter.Utils.isReal
import saarland.cispa.frontmatter.Utils.isSystemMethod
import saarland.cispa.frontmatter.data.StmtSite
import saarland.cispa.frontmatter.resolvers.StringResolver
import soot.Scene
import soot.SootMethod
import soot.jimple.Stmt

/**
 * Extract titles for activities
 * setFragmentTitle
 * setTitle(java.lang.CharSequence)
 * setTitle(int) titleId
 * */
class TitleResolver(sceneV: Scene, val appModel: AppModel, private val valueResolver: ValueResolver, private val resources: ResourceParser) : Resolver(sceneV) {
    companion object : KLogging()

    private val stringResolver = StringResolver(valueResolver, resources)

    // XXX: refactor, not used
    internal fun resolve() {
        for (activity in appModel.activities) {
            if (activity.isAndroidClass()) // ignore default activities
                continue
            if (!activity.isReal()) // ignore default activities
                continue
            logger.info("Resolve title for $activity")
            val reachableMethods = getReachableMethodsOfActivity(activity)
                .filter { it.isConcrete }
                .filterNot { it.isSystemMethod() }
                .filterNot { it.isAndroidMethod() }
                .filterNot { it.isDummy() }
            resolveTitleFrom(activity, reachableMethods)
        }
        /*
        for (fragment in appModel.fragments) {
            if (fragment.isAndroidClass()) // ignore default activities
                continue
            if (!fragment.isReal()) // ignore default activities
                continue
            FindViewResolver.logger.info("Resolve title in $fragment")
            val reachableMethods = getReachableMethods(fragment)
                .filter { it.isConcrete }
                .filterNot { it.isSystemMethod() }
                .filterNot { it.isAndroidMethod() }
                .filterNot { it.isDummy() }
            resolveTitleFrom(fragment, reachableMethods)
        }
        */
    }

    fun resolveTitleFrom(activity: ActivityOrFragmentClass, reachableMethods: Sequence<SootMethod>) {
        val scope = appModel.contextToScope[activity]
        val setTitleSites = reachableMethods
            .flatMap { findSetTitleSites(it) }
            .toList()
        for (setTitleSite in setTitleSites) {
            val titles = stringResolver.resolveStringArg(setTitleSite, 0, scope)
            appModel.activityLabels.putAll(activity, titles)
        }
    }

    private fun findSetTitleSites(method: SootMethod): Sequence<StmtSite> {
        require(method.isConcrete)
        val activeBody = method.retrieveActiveBody()
        return activeBody.units.asSequence()
            .filterIsInstance<Stmt>()
            .filter { it.containsInvokeExpr() }
            .filter { it.invokeExpr.method.subSignature in setActivityTitleSubsignatures && it.invokeExpr.method.declaringClass.isActivity() }
            .map { StmtSite(it, method) }
    }

}
