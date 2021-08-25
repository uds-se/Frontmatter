package saarland.cispa.frontmatter

import mu.KotlinLogging
import soot.jimple.infoflow.android.resources.ARSCFileParser
import java.io.IOException
import java.nio.file.Path


class ResourceParser(targetAPK: Path, private val packageName: String) {
    private val logger = KotlinLogging.logger {}
    val parser = ARSCFileParser()

    init {
        try {
            parser.parse(targetAPK.toString())
        } catch (e: IOException) {
            logger.error("Error while processing parser", e)
            throw IllegalStateException("Error while processing parser", e)
        }
    }

    val strings: Map<Int, String> = getStringResources()
    val drawable: Map<Int, String> = getDrawableResources()
    val layouts: Map<String, Int> = getLayoutResources()
    val menus: Map<String, Int> = getMenuResources()
//    val styles: Map<String, Int> = getStyleResources()

    /***
     * Get layout resources from varous supplied configurations with priority
     * Consider `res/layout` as the main source, ignore layouts with the same id from auxiliary folders
     * If there are many layouts with the same id from auxiliary folders, we take only the first one and ignore the rest
     */
    private fun getLayoutResources(): Map<String, Int> {
        // take all available layout resources
        val layoutResourses = getResourcePackages("layout")
        if (layoutResourses.isEmpty()) {
            logger.warn("Layouts not found")
            return emptyMap()
        }
        val layoutMap = mutableMapOf<String, MutableMap<String, Int>>()
        // read layout resources
        for ((pkgName, layoutRes) in layoutResourses) {
            val layouts = mutableMapOf<String, Int>()
            for (rc in layoutRes.configurations)
                for (res in rc.resources)
                    if (res is ARSCFileParser.StringResource) {
                        if (res.value.startsWith("res/layout/"))
                            layouts[res.value] = res.resourceID
                        else // don't rewrite res/layouts/ with layouts from auxiliary resources like res/layout-land/
                            layouts.putIfAbsent(res.value, res.resourceID)
                    }
            layoutMap[pkgName] = layouts
        }
        // merge layout resources
        return if (layoutMap.isEmpty()) mapOf() else
            mergePkgResources(layoutMap)
    }

    private fun getMenuResources(): Map<String, Int> {
        val menuResources = getResourcePackages("menu")
        if (menuResources.isEmpty()) {
            logger.warn("Menus not found")
            return emptyMap()
        }
        val menuMap = menuResources.associate {
            it.first to it.second.allResources.filterIsInstance<ARSCFileParser.StringResource>().associate { res -> res.value to res.resourceID }.toMutableMap()
        }.toMutableMap()
        return if (menuMap.isEmpty()) mapOf() else
            mergePkgResources(menuMap)
    }

    private fun getResourcePackages(resType: String): List<Pair<String, ARSCFileParser.ResType>> {
        val resources = parser.packages.mapNotNull {
            it.declaredTypes.firstOrNull { type -> type.typeName == resType }?.let { res -> it.packageName to res }
        }
        return resources
    }

    private fun <K, V> mergePkgResources(resourceMap: MutableMap<String, MutableMap<K, V>>): MutableMap<K, V> {
        val mainResourceName = if (packageName in resourceMap) packageName else resourceMap.keys.last()
        val result = resourceMap.getOrDefault(mainResourceName, mutableMapOf())
        resourceMap.remove(mainResourceName)
        for ((_, resource) in resourceMap) {
            for ((name, id) in resource)
                result.putIfAbsent(name, id)
        }
        return result
    }

    private fun getStringResources(): Map<Int, String> {
        return getResourcesByType("string")
    }

    private fun getResourcesByType(resType: String): Map<Int, String> {
        val resources = getResourcePackages(resType)
        if (resources.isEmpty()) {
            logger.warn("$resType not found")
            return emptyMap()
        }
        val resourceMap = resources.associate {
            it.first to it.second.allResources.filterIsInstance<ARSCFileParser.StringResource>().associate { res -> res.resourceID to res.value }.toMutableMap()
        }.toMutableMap()
        return if (resourceMap.isEmpty()) emptyMap() else
            mergePkgResources(resourceMap)
    }

    private fun getDrawableResources(): Map<Int, String> {
        return getResourcesByType("drawable")
    }

    /**
     * return map of style name to map of (text type;text value)
     */
    private fun getStyleResources(): Map<String, Map<String, String>> {
        val resources = getResourcePackages("style")
        if (resources.isEmpty()) {
            logger.warn("Styles not found")
            return emptyMap()
        }
        val styleToStringsMap = resources.associate {
            it.first to it.second.allResources.filterIsInstance<ARSCFileParser.ComplexResource>().associate { res -> res.resourceName to getTextFromStyle(res.value) }.toMutableMap()
        }.toMutableMap()
        return mergePkgResources(styleToStringsMap)

    }

    private fun getTextFromStyle(styleValues: Map<String, ARSCFileParser.AbstractResource>): Map<String, String> {
        // hint 16843088
        // text 16843087
        // label ?
        val textIds = mapOf("16843087" to "text", "16843088" to "hint", "16843984" to "collapseContentDescription", "16843045" to "textOff", "16843044" to "textOn")
        return styleValues.filterKeys { textIds.contains(it) }
            .mapKeys { (k, _) -> textIds.getOrDefault(k, "") }
            .filterValues { it is ARSCFileParser.StringResource }
            .mapValues { (_, v) -> (v as ARSCFileParser.StringResource).value }
            .toMap()
    }
}
