package saarland.cispa.frontmatter.analyses

import mu.KLogging
import saarland.cispa.frontmatter.ActivityClass
import saarland.cispa.frontmatter.ApkInfo
import saarland.cispa.frontmatter.FrontmatterSettings
import saarland.cispa.frontmatter.LanguageDetector
import saarland.cispa.frontmatter.Utils.getSuperclasses
import soot.Scene
import soot.SootClass
import soot.jimple.infoflow.android.manifest.ProcessManifest

data class FrontmatterMetadata(val pkg: String, val pkgVersionName: String, val pkgVersionCode: Int, val boomerangTimeout: Int, val version: String, val type: AppPlatform, val defaultLanguage: String)
class CollectMetadata(val sceneV: Scene, val apkInfo: ApkInfo, val manifest: ProcessManifest, val settings: FrontmatterSettings) {
    companion object : KLogging()

    val meta = run {
        val boomerangTimeout = settings.boomerangTimeout
        val version = javaClass.getPackage().implementationVersion ?: ""
        val platform = identifyPlatform(manifest, apkInfo)
        val language = if (platform.isAnalysable && settings.detectLang) LanguageDetector().detect(apkInfo.resourceParser) else "none"
        if (isNotEnglish(language) && settings.detectLang) {
            logger.info("Non-english app detected: $language")
        }
        val pkg = manifest.packageName
        val pkgVersionCode = manifest.versionCode
        val pkgVersionName = manifest.versionName ?: ""
        FrontmatterMetadata(pkg, pkgVersionName, pkgVersionCode, boomerangTimeout, version, platform, language)
    }

    private fun isNotEnglish(language: String) = language !in setOf("en", "none")

    private fun identifyPlatform(manifest: ProcessManifest, apkInfo: ApkInfo): AppPlatform = when {
        isNativeCode(manifest, apkInfo.declaredActivities) -> {
            logger.info("Is packaged")
            AppPlatform.NATIVE
        }
        isUnity(apkInfo.mainActivities) -> {
            logger.info("Is unity based game")
            AppPlatform.UNITY
        }
        isXamarin(apkInfo.mainActivities) -> {
            logger.info("Is built with xamarin")
            AppPlatform.XAMARIN
        }
        isMono(sceneV) -> {
            logger.info("Is built with mono")
            AppPlatform.MONO
        }
        isCordova(apkInfo.mainActivities) -> {
            logger.info("Is built with apache cordova")
            AppPlatform.CORDOVA
        }
        else -> {
            logger.info("Is regular app")
            AppPlatform.NORMAL
        }
    }

    private fun isXamarin(mainActivities: Set<ActivityClass>): Boolean {
        val pattern = "^md5[0-9a-f]{32}".toRegex()
        return mainActivities.any { pattern.containsMatchIn(it.name) }
    }

    private fun isUnity(mainActivities: Collection<SootClass>): Boolean {
        return mainActivities.any { it.name.startsWith("com.unity3d") }
    }

    private fun isNativeCode(manifest: ProcessManifest, declaredActivities: Set<ActivityClass>): Boolean {
        return declaredActivities.isEmpty() || (declaredActivities.size < manifest.allActivities.size / 2 && manifest.allActivities.size > 3)
    }

    private fun isMono(sceneV: Scene): Boolean {
        return sceneV.applicationClasses.any { it.name.startsWith("mono.android") }
    }

    private fun isCordova(mainActivities: Collection<SootClass>): Boolean {
        return mainActivities.any { activity -> getSuperclasses(activity).any { it.name == "org.apache.cordova.CordovaActivity" } }
    }
}
