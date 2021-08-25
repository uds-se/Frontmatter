package saarland.cispa.frontmatter.results

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonSerializationContext
import com.google.gson.JsonSerializer
import saarland.cispa.frontmatter.resolvers.BroadcastReceiverApi
import java.lang.reflect.Type

class ApiModelSerializer : JsonSerializer<ApiModel>, ApiSerializer {

    override fun serialize(src: ApiModel, typeOfSrc: Type, context: JsonSerializationContext): JsonElement {
        val obj = JsonObject()
        val jmeta = Gson().toJsonTree(src.meta)
        obj.add("meta", jmeta)
        if (!src.meta.type.isAnalysable)
            return obj
        obj.add("permissions", JsonArray().also { src.permissions.forEach { item -> it.add(item) } })
        val views = JsonArray()
        for (uiElement in src.layoutUiElements) {
            makeUIElement(uiElement)?.let { views.add(it) }
        }
        obj.add("views", views)
        val broadcastApi = broadcastToJson(src.broadcastApi)
        obj.add("broadcasts", broadcastApi)
        val activityLC = apiMapToJson(src.activityLCApi)
        obj.add("activityLC", activityLC)
        val serviceApi = apiMapToJson(src.serviceLCApi)
        obj.add("serviceLC", serviceApi)
        return obj
    }

    private fun broadcastToJson(broadcasts: Collection<BroadcastReceiverApi>): JsonArray {
        val jApiArray = JsonArray()
        for (broadcast in broadcasts) {
            val apiObject = JsonObject().apply {
                addProperty("name", broadcast.name)
                add("intent", JsonArray().also {
                    broadcast.intents.forEach { intent -> it.add(intent) }
                })
                add("api", JsonArray().also {
                    broadcast.apis.forEach { item ->
                        if (item.meta.isNotBlank()) it.add(apiCallWithMeta(item)) else it.add(item.method.signature)
                    }
                })
            }
            jApiArray.add(apiObject)
        }
        return jApiArray
    }


    private fun makeUIElement(uiElement: SimpleUIElement): JsonElement? {
        if (uiElement.listeners.isEmpty())
            return null
        val obj = JsonObject()
        obj.addProperty("guid", uiElement.guid)
        val listeners = JsonArray()
        uiElement.listeners.forEach { listeners.add(it) }
        obj.add("listeners", listeners)
        val apis = JsonArray().also {
            uiElement.apis.forEach { api ->
                if (api.meta.isNotBlank()) it.add(apiCallWithMeta(api)) else it.add(api.method.signature)
            }
        }
        obj.add("api", apis)
        return obj
    }

}
