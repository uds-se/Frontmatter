package saarland.cispa.frontmatter.results

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonSerializationContext
import com.google.gson.JsonSerializer
import saarland.cispa.frontmatter.textAttributes
import soot.jimple.infoflow.android.resources.controls.AndroidLayoutControl
import java.lang.reflect.Type

class AndroidLayoutControlSerializer : JsonSerializer<AndroidLayoutControl> {

    override fun serialize(src: AndroidLayoutControl, typeOfSrc: Type, context: JsonSerializationContext): JsonElement {
        val obj = JsonObject()
        val viewClass = src.viewClass
        obj.addProperty("name", viewClass.name)
        obj.addProperty("id", src.id)
        val attrs = JsonObject()
        obj.add("attrs", attrs)
        src.additionalAttributes?.filterKeys { textAttributes.contains(it) }?.forEach { x, y -> attrs.add(x, context.serialize(y, y.javaClass)) }
        return obj
    }
}
