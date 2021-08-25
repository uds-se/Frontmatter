package saarland.cispa.frontmatter.results

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonSerializationContext
import com.google.gson.JsonSerializer
import saarland.cispa.frontmatter.Attr
import saarland.cispa.frontmatter.data.Menu
import saarland.cispa.frontmatter.data.MenuItem
import saarland.cispa.frontmatter.data.SubMenu
import java.lang.reflect.Type

class MenuSerializer : JsonSerializer<Menu> {

    override fun serialize(src: Menu, typeOfSrc: Type, context: JsonSerializationContext): JsonElement {

        val jsonMenu = JsonObject()
        val jsonItems = serializeItem(src.items, context, src.subMenu)
        jsonMenu.add("items", jsonItems)
        if (src.listener != null)
            jsonMenu.addProperty("listener", src.listener?.signature ?: "")
        jsonMenu.addProperty("type", src.type.name)
        return jsonMenu
    }

    private fun serializeItem(items: List<MenuItem>, context: JsonSerializationContext, subMenu: SubMenu?): JsonArray {
        val jsonItems = JsonArray()
        val attSerializer = AttrSerializer()
        val rootItem = subMenu?.rootItem
        for (item in items) {
            val jsonItem = JsonObject()
            jsonItem.addProperty("guid", item.guid)
            jsonItem.addProperty("id", item.id)
            jsonItem.add("title", attSerializer.serializeNoName(item.title))
            if (item.groupId != 0) // remove default group
                jsonItem.addProperty("group", item.groupId)
            if (item.listeners.isNotEmpty()) {
                val jsonListeners = JsonArray()
                item.listeners.forEach {
                    jsonListeners.add(it.signature)
                }
                jsonItem.add("listeners", jsonListeners)
            }
            if (item.attributes.isNotEmpty()) {
                val jsonAttributes = JsonObject()
                item.attributes.forEach {
                    jsonAttributes.add(it.name, attSerializer.serializeNoName(it))
                }
                jsonItem.add("attributes", jsonAttributes)
            }
            if (item == rootItem) { //subMenu originates here
                val subMenuItems = serializeItem(subMenu.items, context, null)
                jsonItem.add("subMenu", subMenuItems)
            }
            jsonItems.add(jsonItem)
        }
        return jsonItems
    }
}
