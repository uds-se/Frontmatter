package saarland.cispa.frontmatter.resolvers

import mu.KLogging
import saarland.cispa.frontmatter.ActivityClass
import saarland.cispa.frontmatter.AppModel
import saarland.cispa.frontmatter.Resolver
import saarland.cispa.frontmatter.ResourceParser
import saarland.cispa.frontmatter.Utils.isAndroidClass
import saarland.cispa.frontmatter.Utils.isAndroidMethod
import saarland.cispa.frontmatter.Utils.isDummy
import saarland.cispa.frontmatter.Utils.isReal
import saarland.cispa.frontmatter.Utils.isSystemMethod
import saarland.cispa.frontmatter.cg.InterproceduralCFGWrapper
import saarland.cispa.frontmatter.data.StmtSite
import saarland.cispa.frontmatter.makeToastSignatures
import soot.Scene
import soot.SootMethod
import soot.jimple.Stmt

class ToastsResolver(sceneV: Scene, val appModel: AppModel, icfg: InterproceduralCFGWrapper, valueResolver: ValueResolver, resourceParser: ResourceParser) : Resolver(sceneV) {
    companion object : KLogging()

    private val stringResolver = StringResolver(valueResolver, resourceParser)

    fun resolve() {
        for (activity in appModel.activities) {
            if (activity.isAndroidClass() || !activity.isReal()) // ignore default activities
                continue
            logger.info("Resolve toasts in $activity")
            val reachableMethods = getReachableMethodsOfActivity(activity)
                .filter { it.isConcrete }
                .filterNot { it.isSystemMethod() }
                .filterNot { it.isAndroidMethod() }
                .filterNot { it.isDummy() }
            resolveToastsFrom(activity, reachableMethods)
        }
    }

    /**
     * currently only makeText() method is concidered
     * setText and setView are ignored
     * */
    private fun resolveToastsFrom(activity: ActivityClass, reachableMethods: Sequence<SootMethod>) {
        val scope = appModel.contextToScope[activity]
        val makeTextSites = reachableMethods
            .flatMap { findMakeTextSites(it) }
            .toList()
        for (makeTextSite in makeTextSites) {
            val toasts = stringResolver.resolveStringArg(makeTextSite, 1, scope)
            appModel.toasts.putAll(activity, toasts)
        }

    }

    private fun findMakeTextSites(method: SootMethod): Sequence<StmtSite> {
        require(method.isConcrete)
        val activeBody = method.retrieveActiveBody()
        return activeBody.units.asSequence()
            .filterIsInstance<Stmt>()
            .filter { it.containsInvokeExpr() }
            .filter { it.invokeExpr.method.signature in makeToastSignatures }
            .map { StmtSite(it, method) }
    }

}
