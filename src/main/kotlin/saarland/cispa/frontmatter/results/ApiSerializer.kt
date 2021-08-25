package saarland.cispa.frontmatter.results

import com.google.gson.JsonArray
import com.google.gson.JsonObject

interface ApiSerializer {

    fun apiMapToJson(api: Map<String, List<ApiCall>>): JsonArray {
        val jApiArray = JsonArray()
        for ((name, apis) in api) {
            val apiObject = JsonObject().apply {
                addProperty("name", name)
                add("api", JsonArray().also {
                    apis.forEach { item ->
                        if (item.meta.isNotBlank()) it.add(apiCallWithMeta(item)) else it.add(item.method.signature)
                    }
                })
            }
            jApiArray.add(apiObject)
        }
        return jApiArray
    }

    fun apiCallWithMeta(api: ApiCall): JsonObject {
        return JsonObject().also {
            it.addProperty("method", api.method.signature)
            it.addProperty("uri", api.meta)
        }
    }
}
