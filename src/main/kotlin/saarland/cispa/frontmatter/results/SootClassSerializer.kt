package saarland.cispa.frontmatter.results

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import com.google.gson.JsonSerializationContext
import com.google.gson.JsonSerializer
import soot.SootClass

import java.lang.reflect.Type

class SootClassSerializer : JsonSerializer<SootClass> {

    override fun serialize(src: SootClass, typeOfSrc: Type, context: JsonSerializationContext): JsonElement {
        return JsonPrimitive(src.name)
//        val jsonObject = JsonObject()
//        jsonObject.addProperty("name", src.name)
//        return jsonObject
    }

}
