package saarland.cispa.frontmatter.analyses

import mu.KLogging
import saarland.cispa.frontmatter.ApkInfo
import saarland.cispa.frontmatter.resolvers.ApiResolver
import saarland.cispa.frontmatter.results.ApiCollection
import soot.Scene

internal class ApiCollector(sceneV: Scene, apkInfo: ApkInfo) : Analysis(sceneV, apkInfo) {
    companion object : KLogging()

    internal fun analyse(meta: FrontmatterMetadata, permissions: Set<String>, resolveArguments: Boolean): ApiCollection {
        val apiResolver = ApiResolver(sceneV, icfg, valueResolver, apkInfo)
        val allApis = apiResolver.collectAllApi(resolveArguments)
        val uiApis = apiResolver.collectApiFromUICAllbacks(callbackAnalysis.possibleCallbacks, resolveArguments)
        return ApiCollection(meta, permissions, allApis, uiApis)
    }
}
