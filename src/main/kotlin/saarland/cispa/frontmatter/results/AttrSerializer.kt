package saarland.cispa.frontmatter.results

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import com.google.gson.JsonSerializationContext
import com.google.gson.JsonSerializer
import saarland.cispa.frontmatter.Attr
import saarland.cispa.frontmatter.data.UIViewElement
import saarland.cispa.frontmatter.data.UIViewGroupElement
import saarland.cispa.frontmatter.textAttributes
import soot.jimple.infoflow.android.resources.controls.AndroidLayoutControl
import java.lang.reflect.Type

class AttrSerializer : JsonSerializer<Attr> {

    override fun serialize(src: Attr, typeOfSrc: Type, context: JsonSerializationContext): JsonElement {
        return if (src.variable.isNotBlank()) {
            JsonObject().also {
                it.addProperty("name", src.name)
                it.addProperty("value", src.value)
                it.addProperty("variable", src.variable)
            }
        } else
            JsonPrimitive(src.value)
    }

    fun serializeNoName(src: Attr): JsonElement {
        return if (src.variable.isNotBlank()) {
            JsonObject().also {
                it.addProperty("value", src.value)
                it.addProperty("variable", src.variable)
            }
        } else
            JsonPrimitive(src.value)
    }
}
