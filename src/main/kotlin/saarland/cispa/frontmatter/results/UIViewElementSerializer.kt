package saarland.cispa.frontmatter.results

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonSerializationContext
import com.google.gson.JsonSerializer
import saarland.cispa.frontmatter.Attr
import saarland.cispa.frontmatter.data.FragmentContainer
import saarland.cispa.frontmatter.data.UIText
import saarland.cispa.frontmatter.data.UIViewElement
import saarland.cispa.frontmatter.data.UIViewGroupElement
import java.lang.reflect.Type

class UIViewElementSerializer : JsonSerializer<UIViewElement> {

    override fun serialize(src: UIViewElement, typeOfSrc: Type, context: JsonSerializationContext): JsonElement {
        if (src is FragmentContainer) {
            val fragmentSerializer = FragmentContainerSerializer()
            return fragmentSerializer.serialize(src, FragmentContainer::class.java, context)
        }
        val obj = JsonObject()
        val attrSerializer = AttrSerializer()
        val viewClass = src.viewClass
        obj.addProperty("viewClass", viewClass.name)
        obj.addProperty("id", src.id)
        obj.addProperty("guid", src.guid)
        if (src.idVariable.isNotBlank())
            obj.addProperty("idVariable", src.idVariable)

        if (src.textAttributes.isNotEmpty()) {
            val textAttributes = JsonArray()
            src.textAttributes.onEach {
                val attr = attrSerializer.serialize(it, Attr::class.java, context)
                textAttributes.add(attr)
            }
            obj.add("textAttributes", textAttributes)
        }
        if (src is UIText && src.inputTypeValues.isNotEmpty()) {
            obj.add("inputTypes", JsonArray().also { src.inputTypes.forEach { inputType -> it.add(inputType) } })
        }
        if (src.otherAttributes.isNotEmpty()) {
            val otherAttributes = JsonObject()
            src.otherAttributes.onEach { (k, v) ->
                val attr = attrSerializer.serializeNoName(v)
                otherAttributes.add(k, attr)
            }
            obj.add("otherAttributes", otherAttributes)
        }
        if (src.listeners.isNotEmpty()) {
            val jsonListeners = JsonArray()
            src.listeners.forEach { jsonListeners.add(it.signature) }
            obj.add("listeners", jsonListeners)
        }
        if (src is UIViewGroupElement) {
            val children = JsonArray()
            for (child in src.children) {
                val p = serialize(child, typeOfSrc, context)
                children.add(p)
            }
            obj.add("children", children)
        }

        return obj
    }
}
