package saarland.cispa.frontmatter.commands

import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.path
import mu.KLogging
import saarland.cispa.frontmatter.ApkInfo
import saarland.cispa.frontmatter.Command
import saarland.cispa.frontmatter.FrontmatterSettings
import saarland.cispa.frontmatter.Utils
import saarland.cispa.frontmatter.analyses.CollectMetadata
import saarland.cispa.frontmatter.results.ResultsHandler
import java.nio.file.Path


internal open class LangAnalysisCommand(help: String = "Use this command to run the analysis, more help with -h") : Command(help = help) {
    companion object : KLogging()

    protected val uiFile: Path by option("-u", "--ui", help = "Output ui file (or folder)")
        .path(mustExist = false, canBeFile = true, canBeDir = false)
        .required()

    override fun run() {
        val settings = FrontmatterSettings(androidPath.toAbsolutePath(), detectLang = true)
        langAnalysis(settings)
    }

    private fun langAnalysis(settings: FrontmatterSettings) {
        logger.info("Started analysis of ${targetApk.fileName}")
        Utils.setBoomerangTimeout(settings.boomerangTimeout)

        val manifest = parseManifest()
        val sceneV = Utils.initializeSoot(settings.androidPath, targetApk, manifest.packageName)
        val apkInfo = ApkInfo(sceneV, targetApk, manifest)
        val meta = CollectMetadata(sceneV, apkInfo, manifest, settings).meta
        ResultsHandler.saveMeta(meta, uiFile.toAbsolutePath())
    }
}
