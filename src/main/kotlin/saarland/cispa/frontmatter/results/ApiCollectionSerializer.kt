package saarland.cispa.frontmatter.results

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonSerializationContext
import com.google.gson.JsonSerializer
import java.lang.reflect.Type

class ApiCollectionSerializer : JsonSerializer<ApiCollection>, ApiSerializer {

    override fun serialize(src: ApiCollection, typeOfSrc: Type, context: JsonSerializationContext): JsonElement {
        val obj = JsonObject()
        val jmeta = Gson().toJsonTree(src.meta)
        obj.add("meta", jmeta)
        obj.add("permissions", JsonArray().also { src.permissions.forEach { item -> it.add(item) } })
        obj.add("all_api", JsonArray().also {
            src.allApis.forEach { item ->
                if (item.meta.isNotBlank()) it.add(apiCallWithMeta(item)) else it.add(item.method.signature)
            }
        })
        obj.add("ui_api", JsonArray().also {
            src.uiApis.forEach { item ->
                if (item.meta.isNotBlank()) it.add(apiCallWithMeta(item)) else it.add(item.method.signature)
            }
        })

        return obj
    }

}
