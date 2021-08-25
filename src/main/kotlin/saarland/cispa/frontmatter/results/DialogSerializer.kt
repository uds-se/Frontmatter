package saarland.cispa.frontmatter.results

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonSerializationContext
import com.google.gson.JsonSerializer
import saarland.cispa.frontmatter.Attr
import saarland.cispa.frontmatter.data.Dialog
import saarland.cispa.frontmatter.data.DialogButton
import java.lang.reflect.Type

class DialogSerializer : JsonSerializer<Dialog> {

    override fun serialize(src: Dialog, typeOfSrc: Type, context: JsonSerializationContext): JsonElement {

        val jsonDialog = JsonObject()
        jsonDialog.add("titles", JsonArray().also { src.title.forEach { title -> it.add(title) } })
        jsonDialog.add("messages", JsonArray().also { src.message.forEach { message -> it.add(message) } })
        if (src.icon.isNotEmpty())
            jsonDialog.add("icons", JsonArray().also { src.icon.forEach { icon -> it.add(icon) } })
        val jsonButtons = JsonArray()
        for (button in src.buttons) {
            val jsonButton = JsonObject()
            jsonButton.addProperty("guid", button.guid)
            jsonButton.addProperty("label", button.label.firstOrNull() ?: "")
            jsonButton.addProperty("type", button.type.name)
            jsonButton.addProperty("listener", button.listener.firstOrNull()?.signature ?: "")
            jsonButtons.add(jsonButton)
        }
        jsonDialog.add("buttons", jsonButtons)
        return jsonDialog
    }

}
