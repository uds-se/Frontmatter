package saarland.cispa.frontmatter

import mu.KotlinLogging
import saarland.cispa.frontmatter.Utils.getAttributeValue
import saarland.cispa.frontmatter.Utils.isActivity
import soot.Scene
import soot.SootClass
import soot.jimple.infoflow.android.axml.AXmlNode
import soot.jimple.infoflow.android.manifest.ProcessManifest
import java.nio.file.Path

class ApkInfo(val sceneV: Scene, val targetAPK: Path, val manifest: ProcessManifest) {
    private val logger = KotlinLogging.logger {}

    val packageName: String = manifest.packageName
    val resourceParser: ResourceParser = ResourceParser(targetAPK, packageName)
    val entryPoints: Set<SootClass> = manifest.entryPointClasses.map(sceneV::getSootClassUnsafe).toSet()
    val activityLabels: Map<SootClass, String> = collectActivityLabels(manifest.activities)
    val declaredActivities: Set<SootClass> = collectActivities(manifest.activities)
    val services: Set<SootClass> = collectServices()
    val intentFilters: Map<String, Set<String>> = run {
        val collectIntentFilters = collectIntentFilters(manifest.activities)
        collectIntentFilters.groupBy({ it.second }, { it.first }).mapValues { it.value.toSet() }
    }
    val broadcastIntentFilters: Map<String, Set<String>> = run {
        val collectIntentFilters = collectIntentFilters(manifest.receivers)
        collectIntentFilters.groupBy({ it.first }, { it.second }).mapValues { it.value.toSet() }
    }
    val mainActivities: Set<SootClass> = collectActivities(manifest.launchableActivities)

    private fun collectServices(): Set<SootClass> {
        return manifest.services.asSequence()
            .map { sceneV.getSootClassUnsafe(getServiceName(it)) }
            .filterNot { it.isPhantom }
            .toSet()
    }

    private fun collectIntentFilters(xmlActivities: Collection<AXmlNode>): List<Pair<String, String>> {
        return xmlActivities.flatMap { xmlActivity ->
            val activityName: String = xmlActivity.getAttributeValue("name") ?: error("Missing <name> attr")
            val fullActivityName = expandClassName(activityName)
            getIntentActions(xmlActivity.getChildrenWithTag("intent-filter"), fullActivityName).map { fullActivityName to it }
        }
//        val result = mutableMapOf<String, MutableSet<String>>()
//        for (xmlActivity in xmlActivities) {
//            val activityName: String = xmlActivity.getAttributeValue("name") ?: error("Missing <name> attr")
//            val fullActivityName = expandClassName(activityName)
//            val intents = xmlActivity.getChildrenWithTag("intent-filter")
//            for (intent in intents) {
//                intent.getChildrenWithTag("action").map { it.getAttributeValue<String>("name") ?: error("Missing <name> attr") }
//                    .forEach { result.getOrPut(it) { mutableSetOf() }.add(fullActivityName) }
//            }
//        }
//        return result
    }

    private fun getIntentActions(intents: MutableList<AXmlNode>, fullActivityName: String): List<String> {
        return intents.flatMap { it.getChildrenWithTag("action") }.map { it.getAttributeValue<String>("name") ?: error("Missing <name> attribute in $it") }
    }

    private fun collectActivityLabels(xmlActivities: Collection<AXmlNode>): Map<SootClass, String> { //TODO: add activity alias
        return xmlActivities.asSequence()
            .map { sceneV.getSootClassUnsafe(getActivityName(it)) to getActivityLabel(it) }
            .filterNot { it.second.isBlank() }
            .toMap()
    }

    private fun collectActivities(xmlActivities: Collection<AXmlNode>): Set<SootClass> { //TODO: add activity alias
        return xmlActivities.asSequence()
            .map { sceneV.getSootClassUnsafe(getActivityName(it)) }
            .filterNot { it.isPhantom }
            .filter { it.isActivity() }
            .toSet()
    }

    private fun getActivityLabel(xmlNode: AXmlNode): String {
        return when (val value = xmlNode.getAttributeValue<Any>("label") ?: "") {
            is String -> value
            is Int -> resourceParser.strings[value] ?: "".also { logger.warn("Unknown label ID for Activity $value") }
            else -> value.toString()
        }
    }

    private fun getServiceName(xmlNode: AXmlNode): String {
        return getActivityName(xmlNode)
    }

    private fun getActivityName(xmlNode: AXmlNode): String {
        val name: String? = xmlNode.getAttributeValue("name")
        require(name != null) { "this app is corrupted, as it doesn't have activity name attribute" }
        return expandClassName(name)
    }

    private fun expandClassName(className: String): String {
        return if (className.startsWith("."))
            packageName + className
        else if (!className.contains("."))
            "$packageName.$className"
        else
            className
    }

}
