package saarland.cispa.frontmatter.results

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import mu.KotlinLogging
import saarland.cispa.frontmatter.AppModel
import saarland.cispa.frontmatter.analyses.FrontmatterMetadata
import saarland.cispa.frontmatter.data.UIViewElement
import soot.SootClass
import soot.Value
import soot.jimple.IntConstant
import soot.jimple.infoflow.android.resources.controls.AndroidLayoutControl
import soot.util.MultiMap
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.FileReader
import java.io.FileWriter
import java.io.IOException
import java.lang.reflect.Type
import java.nio.file.Files
import java.nio.file.Path


object ResultsHandler {
    private val logger = KotlinLogging.logger {}

    fun saveActivitiesContent(indexedLayouts: MultiMap<Int, UIViewElement>, layoutMapping: MultiMap<SootClass, Int>, destFilePath: Path) {
        val gson = GsonBuilder().registerTypeAdapter(UIViewElement::class.java, UIViewElementSerializer()).setPrettyPrinting().create()
        val res = HashMap<String, List<UIViewElement>>()
        for (activity in layoutMapping.keySet()) {
            val activityName = activity.name
            val layouts = layoutMapping.get(activity)
            val uiControls = layouts
                .filterIsInstance<IntConstant>()
                .flatMap { indexedLayouts.get(it.value) }
            res[activityName] = uiControls
        }
        val mapType = object : TypeToken<Map<String, List<AndroidLayoutControl>>>() {}.type
        try {
            BufferedWriter(FileWriter(destFilePath.toFile())).use { writer -> gson.toJson(res, mapType, writer) }
        } catch (e: IOException) {
            logger.error("Failed to write serialised results into {}", destFilePath, e)
            throw IllegalStateException(e)
        }

    }

    fun saveLayoutMapping(layoutMapping: MultiMap<SootClass, Value>, destFilePath: Path) {
        val gson = GsonBuilder().setPrettyPrinting().create()
        val res = HashMap<String, List<String>>()
        for (activity in layoutMapping.keySet()) {
            val activityName = activity.name
            val layouts = layoutMapping.get(activity)
            layouts
                .filter { x -> x !is IntConstant }
                .forEach { x -> logger.warn("Activity {} has unresolved layout {}", activity, x) }
            val layoutIds = layouts.filterIsInstance<IntConstant>()
                .map { it.toString() }
            res[activityName] = layoutIds
        }
        saveToFile(destFilePath, gson, res)
    }

    fun saveUIModel(appModel: AppModel, destFilePath: Path) {
        val gson = GsonBuilder()
            .registerTypeAdapter(AppModel::class.java, AppModelSerializer())
            .disableHtmlEscaping()
            .setPrettyPrinting().create()
        saveToFile(destFilePath, gson, appModel)
    }

    fun loadFlatUI(filePath: Path, filterPath: Path? = null): List<SimpleUIElement> {
        val flatUIType: Type = object : TypeToken<List<SimpleUIElement>>() {}.type
        val gson = GsonBuilder()
            .registerTypeAdapter(flatUIType, FlatUIDeserializer())
            .disableHtmlEscaping()
            .setPrettyPrinting().create()
        val flatUI: List<SimpleUIElement> = try {
            BufferedReader(FileReader(filePath.toJsonFile())).use { reader ->
                gson.fromJson(reader, flatUIType)
            }
        } catch (e: IOException) {
            logger.error("Failed to load serialised results from $filePath", e)
            throw IllegalStateException(e)
        }
        return if (filterPath != null) {
            val filterUIguid = loadFilter(filterPath)
            flatUI.filterIsInstance<SimpleLayoutUIElement>()
                .filter { it.guid in filterUIguid }
        } else flatUI
    }

    private fun loadFilter(filterPath: Path): Set<String> {
        return Files.readAllLines(filterPath).toSet()
    }

    fun saveApi(apiModel: ApiModel, destFilePath: Path) {
        val gson = GsonBuilder()
            .registerTypeAdapter(ApiModel::class.java, ApiModelSerializer())
            .disableHtmlEscaping()
            .setPrettyPrinting().create()
        saveToFile(destFilePath, gson, apiModel)
    }

    fun saveMeta(meta: FrontmatterMetadata, destPath: Path) {
        val gson = GsonBuilder()
            .setPrettyPrinting().create()
        saveToFile(destPath, gson, meta)
    }

    fun saveApiCollection(apiCollection: ApiCollection, destFilePath: Path) {
        val gson = GsonBuilder()
            .registerTypeAdapter(ApiCollection::class.java, ApiCollectionSerializer())
            .disableHtmlEscaping()
            .setPrettyPrinting().create()
        saveToFile(destFilePath, gson, apiCollection)
    }

    private fun saveToFile(destFilePath: Path, gson: Gson, apiCollection: Any) {
        try {
            BufferedWriter(FileWriter(destFilePath.toJsonFile())).use { writer ->
                return gson.toJson(apiCollection, writer)
            }
        } catch (e: IOException) {
            logger.error("Failed to write serialised results into $destFilePath", e)
            throw IllegalStateException(e)
        }
    }

}

private fun Path.toJsonFile(): File {
    return if (this.fileName.toString().endsWith(".json"))
        File(this.toString())
    else {
        File("$this.json")
    }
}
