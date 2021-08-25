package saarland.cispa.frontmatter.commands

import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.path
import mu.KLogging
import saarland.cispa.frontmatter.ApkInfo
import saarland.cispa.frontmatter.Command
import saarland.cispa.frontmatter.FrontmatterSettings
import saarland.cispa.frontmatter.Utils
import saarland.cispa.frontmatter.analyses.ApiAnalysis
import saarland.cispa.frontmatter.analyses.CollectMetadata
import saarland.cispa.frontmatter.results.ApiModel
import saarland.cispa.frontmatter.results.ResultsHandler
import java.nio.file.Path

internal class ApiAnalysisCommand : Command(help = "Use this command to run the api extraction, more help with -h") {
    companion object : KLogging()

    private val uiFile: Path by option("-u", "--ui", help = "UI model json file")
        .path(mustExist = true, canBeFile = true, canBeDir = false, mustBeReadable = true)
        .required()
    private val filterFile: Path? by option("-f", "--filter", help = "File with a list of ui guids to analyse")
        .path(mustExist = true, canBeFile = true, canBeDir = false, mustBeReadable = true)
    private val apiFile: Path by option("-a", "--api", help = "Output api file")
        .path(mustExist = false, canBeFile = true, canBeDir = false)
        .required()

    override fun run() {
        logger.info("Started api extraction of ${targetApk.fileName}")
        val settings = FrontmatterSettings(androidPath, boomerangTimeout = boomerangTimeout, detectLang = false)
        settings.filterFile = filterFile

        val manifest = parseManifest()
        val sceneV = Utils.initializeSoot(settings.androidPath, targetApk, manifest.packageName)
        val apkInfo = ApkInfo(sceneV, targetApk, manifest)
        val meta = CollectMetadata(sceneV, apkInfo, manifest, settings).meta

        val uiElements = ResultsHandler.loadFlatUI(uiFile, settings.filterFile)

        val apiModel =
            if (meta.type.isAnalysable && uiElements.isNotEmpty())
                ApiAnalysis(sceneV, apkInfo).analyse(uiElements, meta, manifest.permissions)
            else
                ApiModel(uiElements, meta, manifest.permissions)
        ResultsHandler.saveApi(apiModel, apiFile.toAbsolutePath())
    }
}
