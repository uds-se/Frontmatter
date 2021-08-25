package saarland.cispa.frontmatter.commands

import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.path
import mu.KLogging
import saarland.cispa.frontmatter.ApkInfo
import saarland.cispa.frontmatter.Command
import saarland.cispa.frontmatter.FrontmatterSettings
import saarland.cispa.frontmatter.Utils
import saarland.cispa.frontmatter.analyses.ApiCollector
import saarland.cispa.frontmatter.analyses.CollectMetadata
import saarland.cispa.frontmatter.results.ResultsHandler
import java.nio.file.Path

internal class CollectApiCommand : Command(help = "Use this command to run the api extraction, more help with -h") {
    companion object : KLogging()

    private val apiFile: Path by option("-a", "--api", help = "Output api file")
        .path(mustExist = false, canBeFile = true, canBeDir = false)
        .required()

    private val withArguments: Boolean by option("-w", "--with-args", help = "Resolve ContentResolver arguments and Services")
        .flag("--without-args", default = true)

    override fun run() {
        logger.info("Started api extraction of ${targetApk.fileName}")
        val settings = FrontmatterSettings(androidPath, boomerangTimeout = boomerangTimeout, detectLang = false)
        val manifest = parseManifest()
        val sceneV = Utils.initializeSoot(settings.androidPath, targetApk, manifest.packageName)
        val apkInfo = ApkInfo(sceneV, targetApk, manifest)
        val meta = CollectMetadata(sceneV, apkInfo, manifest, settings).meta
        val apiCollection = ApiCollector(sceneV, apkInfo).analyse(meta, manifest.permissions, withArguments)
        ResultsHandler.saveApiCollection(apiCollection, apiFile.toAbsolutePath())
    }
}
