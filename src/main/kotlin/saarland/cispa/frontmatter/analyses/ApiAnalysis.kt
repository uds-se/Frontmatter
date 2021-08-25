package saarland.cispa.frontmatter.analyses

import mu.KLogging
import saarland.cispa.frontmatter.ApkInfo
import saarland.cispa.frontmatter.resolvers.UIApiResolver
import saarland.cispa.frontmatter.results.ApiModel
import saarland.cispa.frontmatter.results.SimpleUIElement
import soot.Scene

internal class ApiAnalysis(sceneV: Scene, apkInfo: ApkInfo) : Analysis(sceneV, apkInfo) {
    companion object : KLogging()

    internal fun analyse(uiElements: List<SimpleUIElement>, meta: FrontmatterMetadata, permissions: Set<String>): ApiModel {
        val uiApiResolver = UIApiResolver(sceneV, icfg, valueResolver, apkInfo, layoutAnalysis.uiFactory)
        uiApiResolver.resolve(uiElements)
        val activityLifecycle = uiApiResolver.resolveActivityLCApi(apkInfo.declaredActivities)
        val serviceLifecycle = uiApiResolver.resolveServiceLCApi(apkInfo.services)
        val broadcastApi = uiApiResolver.resolveBroadcastApi()
        return ApiModel(uiElements, meta, permissions, broadcastApi, activityLifecycle, serviceLifecycle)
    }
}
