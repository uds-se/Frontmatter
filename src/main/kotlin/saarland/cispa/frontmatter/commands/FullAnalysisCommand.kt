package saarland.cispa.frontmatter.commands

import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.path
import mu.KLogging
import saarland.cispa.frontmatter.ApkInfo
import saarland.cispa.frontmatter.AppModel
import saarland.cispa.frontmatter.Command
import saarland.cispa.frontmatter.FrontmatterSettings
import saarland.cispa.frontmatter.Utils
import saarland.cispa.frontmatter.analyses.ApiAnalysis
import saarland.cispa.frontmatter.analyses.CollectMetadata
import saarland.cispa.frontmatter.analyses.UiAnalysis
import saarland.cispa.frontmatter.results.ApiModel
import saarland.cispa.frontmatter.results.ResultsHandler
import java.nio.file.Path

internal class FullAnalysisCommand : Command(help = "Use this command to run full (ui with api) analysis, more help with -h") {
    companion object : KLogging()

    protected val uiFile: Path by option("-u", "--ui", help = "Output ui file (or folder)")
        .path(mustExist = false, canBeFile = true, canBeDir = false)
        .required()
    protected val withTransitions: Boolean by option("-t", "--transitions", help = "Resolve screen transitions")
        .flag("--no-transitions", default = false)
    protected val detectLang: Boolean by option("--detect-lang", help = "Detect language of app resources, this will skip the analysis of non-english apks")
        .flag(default = false)
    protected val detectToasts: Boolean by option("--toasts", help = "Detect toast messages")
        .flag(default = false)

    private val apiFile: Path by option("-a", "--api", help = "Output ui file (or folder)")
        .path(mustExist = false, canBeFile = true, canBeDir = false).required()

    override fun run() {
        val settings = FrontmatterSettings(androidPath.toAbsolutePath(), withTransitions, boomerangTimeout, detectLang, detectToasts)
        fullAnalysis(settings)
    }

    private fun fullAnalysis(settings: FrontmatterSettings) {
        logger.info("Started full analysis of ${targetApk.fileName}")
        Utils.setBoomerangTimeout(settings.boomerangTimeout)

        val manifest = parseManifest()
        val sceneV = Utils.initializeSoot(settings.androidPath, targetApk, manifest.packageName)
        val apkInfo = ApkInfo(sceneV, targetApk, manifest)
        val meta = CollectMetadata(sceneV, apkInfo, manifest, settings).meta

        val appModel = if (meta.type.isAnalysable) {
            val analysis = UiAnalysis(sceneV, apkInfo)
            analysis.runUiAnalysis(meta, settings)
        } else {
            AppModel(emptyList(), meta)
        }
        ResultsHandler.saveUIModel(appModel, uiFile.toAbsolutePath())
        val uiElements = appModel.getFlatUI()

        val apiModel = if (meta.type.isAnalysable) {
            val analysis = ApiAnalysis(sceneV, apkInfo)
            analysis.analyse(uiElements, meta, manifest.permissions)
        } else ApiModel(uiElements, meta, manifest.permissions)
        ResultsHandler.saveApi(apiModel, apiFile.toAbsolutePath())

    }
}
