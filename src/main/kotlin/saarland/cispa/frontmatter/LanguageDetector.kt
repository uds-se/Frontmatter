package saarland.cispa.frontmatter

import com.cybozu.labs.langdetect.DetectorFactory
import com.cybozu.labs.langdetect.LangDetectException
import mu.KLogger
import mu.KotlinLogging
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

class LanguageDetector {
    private var initialized: Boolean = false
    private val logger: KLogger = KotlinLogging.logger {}

    init {
        try {
            val langProfilesPath = File(this.javaClass.protectionDomain.codeSource.location.toURI()).toPath()
                .resolveSibling("langdetect").resolve("langProfiles")
            if (Files.exists(langProfilesPath)) {
                DetectorFactory.loadProfile(langProfilesPath.toString())
                initialized = true
            } else {
                logger.error("langProfiles not found")
            }
        } catch (e: LangDetectException) {
            logger.error("langDetect failed to initialize", e)
        }
    }

    //XXX: promising detector but jar file blows up because of models, now using simpler technique
//    val detector: LanguageDetector = LanguageDetectorBuilder.fromLanguages(Language.ENGLISH, Language.FRENCH, Language.THAI, Language.VIETNAMESE).build()
//    val detectedLanguage: Language = detector.detectLanguageOf(text = "languages are awesome")

    fun detect(resourceParser: ResourceParser): String {
        val text = resourceParser.strings.values.joinToString(separator = " ")
        return detect(text)
    }

    fun detect(text: String): String {
        if (!initialized) return "none"
        val detector = DetectorFactory.create()
        detector.append(text)
        return detector.detect()
    }

    fun isEnglish(resourceParser: ResourceParser): Boolean {
        val language = detect(resourceParser)
        return if ("en" != language) {
            logger.warn("Non english language detected: $language")
            false
        } else
            true
    }
}
