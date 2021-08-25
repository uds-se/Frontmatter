package saarland.cispa.frontmatter.resolvers

import mu.KLogging
import saarland.cispa.frontmatter.ActivityClass
import saarland.cispa.frontmatter.ActivityOrFragmentClass
import saarland.cispa.frontmatter.AppModel
import saarland.cispa.frontmatter.Resolver
import saarland.cispa.frontmatter.Utils.isAndroidClass
import saarland.cispa.frontmatter.Utils.isReal
import saarland.cispa.frontmatter.Utils.isSystemMethod
import saarland.cispa.frontmatter.Utils.isAndroidMethod
import saarland.cispa.frontmatter.XmlLayoutAnalysis
import saarland.cispa.frontmatter.data.MenuType
import saarland.cispa.frontmatter.data.StmtSite
import saarland.cispa.frontmatter.menuInflatorSignatures
import saarland.cispa.frontmatter.onCreateMenuSubsignatures
import soot.Scene
import soot.SootMethod
import soot.jimple.Stmt

/**
 * Resolve menus
 * TODO: add dynamic changes resolution, e.g. setTitle()
 * */


class MenuResolver(sceneV: Scene, private val appModel: AppModel,
                   val valueResolver: ValueResolver,
                   private val layoutAnalysis: XmlLayoutAnalysis) : Resolver(sceneV) {
    companion object : KLogging()

    val activities = appModel.activities
    private val resourceResolver = ResourceResolver(valueResolver)

    fun resolve(): AppModel {
        for (activity in activities) {
            if (activity.isAndroidClass()) // ignore default activities
                continue
            if (!activity.isReal()) // ignore default activities
                continue
            logger.info("=> Resolving menu for $activity")
            resolveMenusFrom(activity)
            logger.info("=> Resolving findViews for $activity")
        }
        //TODO: fragments
        return appModel
    }

    fun resolveMenusFrom(activity: ActivityOrFragmentClass) {
        val scope = appModel.contextToScope[activity]
        for (onCreateMenuSubsignature in onCreateMenuSubsignatures) {
            val reachableMethods = getOnCreateMenuReachableMethods(activity, onCreateMenuSubsignature) // search in onCreate###Menu
                .filter { it.isConcrete }
                .filterNot { it.isSystemMethod() }
                .filterNot { it.isAndroidMethod() }
            val menuInflateSites = reachableMethods
                .flatMap { findMenuInflate(it).asSequence() }
                .toList()
            if (menuInflateSites.isEmpty()) continue
            val menuType = getMenuType(onCreateMenuSubsignature)
            val listener = resolveMenuListener(activity, menuType)
            for (menuInflateSite in menuInflateSites) {
                val menuLayoutIds = resourceResolver.resolveResourceId(menuInflateSite, scope, 0)
                val menus = menuLayoutIds.mapNotNull { layoutAnalysis.getMenu(it.value, activity) }.onEach {
                    it.type = menuType
                    it.listener = listener
                }.toSet()
                appModel.activityMenus.putAll(activity, menus)
            }
        }
    }

    private fun getMenuType(onCreateMenuSubsignature: String): MenuType {
        return when (onCreateMenuSubsignature) {
            "boolean onCreateOptionsMenu(android.view.Menu)" -> MenuType.OPTIONS
            "void onCreateContextMenu(android.view.ContextMenu,android.view.View,android.view.ContextMenu.ContextMenuInfo)" -> MenuType.CONTEXT
            "boolean onCreatePanelMenu(int,android.view.Menu)" -> MenuType.PANEL
            else -> MenuType.OTHER
        }
    }

    private fun resolveMenuListener(activity: ActivityOrFragmentClass, menuType: MenuType): SootMethod? {
        val menuListenerMethodSubsignature = when (menuType) {
            MenuType.OPTIONS -> "boolean onOptionsItemSelected(android.view.MenuItem)"
            MenuType.CONTEXT -> "boolean onContextItemSelected(android.view.MenuItem)"
            MenuType.PANEL,
            MenuType.POPUP,
            MenuType.OTHER,
            MenuType.UNDEFINED -> return null
        }
        return getTargetMethodBySubsignature(activity, menuListenerMethodSubsignature) ?: return null
    }

    private fun findMenuInflate(method: SootMethod): List<StmtSite> {
        require(method.isConcrete)
        val activeBody = method.retrieveActiveBody()
        return activeBody.units.asSequence()
            .filterIsInstance<Stmt>()
            .filter { it.containsInvokeExpr() }
            .filter { it.invokeExpr.method.signature in menuInflatorSignatures }
            .map { StmtSite(it, method) }
            .toList()
    }


    private fun getOnCreateMenuReachableMethods(activity: ActivityClass, onCreateMenuSubsignature: String): Sequence<SootMethod> {
        val entryPoint = getTargetMethodBySubsignature(activity, onCreateMenuSubsignature)?.let { listOf(it) } ?: return emptySequence()
        return getReachableMethodsWithinContext(entryPoint, activity)
    }

}
