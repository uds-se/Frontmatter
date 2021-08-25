package saarland.cispa.frontmatter.results

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonSerializationContext
import com.google.gson.JsonSerializer
import saarland.cispa.frontmatter.Attr
import saarland.cispa.frontmatter.data.FragmentContainer
import saarland.cispa.frontmatter.data.UIViewElement
import saarland.cispa.frontmatter.data.UIViewGroupElement
import saarland.cispa.frontmatter.textAttributes
import soot.jimple.infoflow.android.resources.controls.AndroidLayoutControl
import java.lang.reflect.Type

class FragmentContainerSerializer : JsonSerializer<UIViewElement> {

    override fun serialize(src: UIViewElement, typeOfSrc: Type, context: JsonSerializationContext): JsonElement {
        val obj = JsonObject()
        val viewClass = src.viewClass
        obj.addProperty("fragmentClass", viewClass.name)
        obj.addProperty("id", src.id)
        if (src.hasChildren()) {
            val uiElementSerializer = UIViewElementSerializer()
            require(src is UIViewGroupElement)
            val children = JsonArray()
            for (child in src.children) {
                val p = uiElementSerializer.serialize(child, typeOfSrc, context)
                children.add(p)
            }
            obj.add("layouts", children)
        }

        return obj
    }
}
