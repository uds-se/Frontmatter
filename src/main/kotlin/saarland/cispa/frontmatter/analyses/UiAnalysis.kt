package saarland.cispa.frontmatter.analyses

import mu.KLogging
import saarland.cispa.frontmatter.ActivityLayoutResolver
import saarland.cispa.frontmatter.ApkInfo
import saarland.cispa.frontmatter.AppModel
import saarland.cispa.frontmatter.FrontmatterSettings
import saarland.cispa.frontmatter.resolvers.FragmentLayoutResolver
import saarland.cispa.frontmatter.resolvers.FragmentResolver
import saarland.cispa.frontmatter.resolvers.ToastsResolver
import saarland.cispa.frontmatter.resolvers.TransitionResolver
import soot.Scene

internal class UiAnalysis(sceneV: Scene, apkInfo: ApkInfo) : Analysis(sceneV, apkInfo) {
    companion object : KLogging()

    internal fun runUiAnalysis(meta: FrontmatterMetadata, settings: FrontmatterSettings): AppModel {
        val appModel = AppModel(apkInfo.declaredActivities, meta)
        appModel.setXmlLabels(apkInfo.activityLabels)
        // start resolution
        val resourceParser = apkInfo.resourceParser
        val layoutMapper = ActivityLayoutResolver(sceneV, appModel, valueResolver, resourceParser, layoutAnalysis)
        layoutMapper.resolve()
        val fragmentResolver = FragmentResolver(sceneV, appModel, valueResolver)
        fragmentResolver.resolve()
        val fragmentLayoutResolver = FragmentLayoutResolver(sceneV, icfg, valueResolver, appModel, resourceParser, layoutAnalysis)
        fragmentLayoutResolver.resolve()
        if (settings.analyseToasts) {
            val toastsResolver = ToastsResolver(sceneV, appModel, icfg, valueResolver, resourceParser)
            toastsResolver.resolve()
        }
        if (settings.withTransitions) {
            val transitionResolver = TransitionResolver(sceneV, appModel, icfg, valueResolver, resourceParser, apkInfo.intentFilters)
            transitionResolver.resolve()
        }
        return appModel
    }


}
