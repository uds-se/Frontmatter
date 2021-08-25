package saarland.cispa.frontmatter

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.defaultLazy
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.path
import soot.jimple.infoflow.android.manifest.ProcessManifest
import java.io.IOException
import java.nio.file.Path
import java.nio.file.Paths

abstract class Command(help: String = "", epilog: String = "", name: String? = null) : CliktCommand(help, epilog, name, printHelpOnEmptyArgs = true) {

    protected val targetApk: Path by option("-i", "--input", help = "Input apk file")
        .path(mustExist = true, canBeFile = true, canBeDir = true, mustBeReadable = true).required()

    protected val androidPath: Path by option("--android", help = "Android SDK path to platforms, takes \$ANDROID_HOME/platforms by default")
        .path(mustExist = true, canBeFile = false, canBeDir = true)
        .defaultLazy {
            System.getenv("ANDROID_HOME")?.let {
                val androidJarsPath = Paths.get(System.getenv("ANDROID_HOME")).resolve("platforms")
                androidJarsPath ?: throw IOException("\$ANDROID_HOME/platforms does not exist.")
            } ?: throw IOException("\$ANDROID_HOME not set.")
        }
    protected val boomerangTimeout: Int by option("--boomerang-timeout", help = "Timeout in sec for a single boomerang resolution of points-to set")
        .int()
        .default(30)

    fun parseManifest(): ProcessManifest = ProcessManifest(targetApk.toAbsolutePath().toFile())
}


