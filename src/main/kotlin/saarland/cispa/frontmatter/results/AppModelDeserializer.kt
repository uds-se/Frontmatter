package saarland.cispa.frontmatter.results

import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import saarland.cispa.frontmatter.ActivityClass
import saarland.cispa.frontmatter.AppModel
import saarland.cispa.frontmatter.analyses.FrontmatterMetadata
import saarland.cispa.frontmatter.resolvers.BroadcastReceiverApi
import soot.SootMethod
import java.lang.reflect.Type

/**
 * These classes are used to deserialize an appModel from json into a list of simplified ui elements for api analysis.
 * It doesn't persist ui hierarchy
 */
sealed class SimpleUIElement {
    abstract val guid: String
    abstract val listeners: Set<String>
    abstract val apis: MutableCollection<ApiCall>
}

class SimpleLayoutUIElement(
    override val guid: String, val id: Int, override val listeners: Set<String>,
    override val apis: MutableCollection<ApiCall> = mutableSetOf()
) : SimpleUIElement()
typealias MenuItem = SimpleLayoutUIElement

class DialogButton(override val guid: String, override val listeners: Set<String>, override val apis: MutableCollection<ApiCall> = mutableSetOf()) : SimpleUIElement()

class ApiCollection(
    val meta: FrontmatterMetadata,
    val permissions: Collection<String>,
    val allApis: Collection<ApiCall>,
    val uiApis: Collection<ApiCall>,
)

class ApiModel(
    val layoutUiElements: List<SimpleUIElement>,
    val meta: FrontmatterMetadata,
    val permissions: Collection<String>,
    val broadcastApi: Collection<BroadcastReceiverApi> = emptySet(),
    val activityLCApi: Map<String, List<ApiCall>> = emptyMap(),
    val serviceLCApi: Map<String, List<ApiCall>> = emptyMap()
)

class FlatUIDeserializer : JsonDeserializer<List<SimpleUIElement>> {

    override fun deserialize(json: JsonElement, typeOfT: Type, context: JsonDeserializationContext): List<SimpleUIElement> {
        val jobject = json.asJsonObject //TODO: process error
        val activityDeserializer = FlatActivityDeserializer()
        return jobject.get("activities")?.asJsonArray?.flatMap { activityDeserializer.deserialize(it, List::class.java, context) } ?: emptyList()
    }

}

/**
 * destroy hierarchy, squeeze uiElements out of layouts
 */
class FlatActivityDeserializer : JsonDeserializer<List<SimpleUIElement>> {

    override fun deserialize(json: JsonElement, typeOfT: Type, context: JsonDeserializationContext): MutableList<SimpleUIElement> {
        val jobject = json.asJsonObject
        val layoutDeserialiser = FlatLayoutDeserializer()
        val menuDeserialiser = MenuDeserializer()
        val dialogDeserialiser = DialogDeserializer()
        val uiList = mutableListOf<SimpleUIElement>()
        jobject.get("layouts")?.asJsonArray?.flatMapTo(uiList) { layoutDeserialiser.deserialize(it, List::class.java, context) }
        jobject.get("orphanedFragments")?.asJsonArray?.flatMapTo(uiList) { layoutDeserialiser.deserialize(it, List::class.java, context) }
        jobject.get("menu")?.asJsonArray?.flatMapTo(uiList) { menuDeserialiser.deserialize(it, List::class.java, context) }
        jobject.get("dialogs")?.asJsonArray?.flatMapTo(uiList) { dialogDeserialiser.deserialize(it, List::class.java, context) }
        return uiList
    }

}

class DialogDeserializer : JsonDeserializer<List<DialogButton>> {
    override fun deserialize(jdialog: JsonElement, typeOfT: Type, context: JsonDeserializationContext): List<DialogButton> {
        val jbuttons = jdialog.asJsonObject.get("buttons").asJsonArray
        return jbuttons.map { btn ->
            val guid = btn.asJsonObject.get("guid").asString
            val listeners = btn.asJsonObject.get("listener")?.asString?.let { setOf(it) } ?: emptySet()
            DialogButton(guid, listeners)
        }
    }

}

class MenuDeserializer : JsonDeserializer<List<MenuItem>> {
    override fun deserialize(jobject: JsonElement, typeOfT: Type, context: JsonDeserializationContext): List<MenuItem> {
        val jmenu = jobject.asJsonObject
        val listeners = jmenu.get("listener")?.asString?.let { setOf(it) } ?: emptySet()
        return jmenu.get("items").asJsonArray.map {
            val jitem = it.asJsonObject
            val guid = jitem.get("guid").asString
            val id = jitem.get("id").asInt
            MenuItem(guid, id, listeners)
        }
    }
}

class FlatLayoutDeserializer : JsonDeserializer<List<SimpleLayoutUIElement>> {

    override fun deserialize(json: JsonElement, typeOfT: Type, context: JsonDeserializationContext): List<SimpleLayoutUIElement> {
        val jobject = json.asJsonObject
        return if (jobject.has("fragmentClass")) {
            deserializeFragment(jobject, context)
        } else {
            deserializeView(jobject, context)
        }
    }

    private fun deserializeView(jobject: JsonObject, context: JsonDeserializationContext): List<SimpleLayoutUIElement> {
        val id = jobject.get("id").asInt
        val guid = jobject.get("guid").asString
        val listeners = jobject.get("listeners")?.asJsonArray?.map { it.asString }?.toSet() ?: emptySet()
        val uiElement = SimpleLayoutUIElement(guid, id, listeners)
        val uiList = mutableListOf(uiElement)
        val flatLayoutDeserializer = FlatLayoutDeserializer()
        jobject.get("children")?.asJsonArray?.flatMapTo(uiList) { flatLayoutDeserializer.deserialize(it, List::class.java, context) }
        return uiList
    }

    private fun deserializeFragment(jobject: JsonObject, context: JsonDeserializationContext): List<SimpleLayoutUIElement> =
        jobject.get("layouts")?.asJsonArray?.flatMap { deserialize(it, List::class.java, context) } ?: emptyList()
}

class AppModelDeserializer : JsonDeserializer<AppModel> {

    override fun deserialize(json: JsonElement, typeOfT: Type, context: JsonDeserializationContext): AppModel? {
        val jobject = json.asJsonObject
        val jmeta = jobject.get("meta")
        val activityList = listOf<ActivityClass>()
//        val appModel = AppModel(activityList, meta)
        TODO("Not implemented")
    }

}

data class ApiCall(val method: SootMethod, val meta: String)
