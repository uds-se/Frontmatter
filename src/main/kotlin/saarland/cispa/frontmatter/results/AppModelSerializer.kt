package saarland.cispa.frontmatter.results

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonSerializationContext
import com.google.gson.JsonSerializer
import saarland.cispa.frontmatter.AppModel
import saarland.cispa.frontmatter.data.Dialog
import saarland.cispa.frontmatter.data.Menu
import saarland.cispa.frontmatter.data.UIViewElement
import java.lang.reflect.Type

class AppModelSerializer : JsonSerializer<AppModel> {

    override fun serialize(src: AppModel, typeOfSrc: Type, context: JsonSerializationContext): JsonElement {
        val obj = JsonObject()
        val jmeta = Gson().toJsonTree(src.meta)
        obj.add("meta", jmeta)
//        obj.add("meta", JsonObject().also { src.meta.data.forEach { (key, value) -> it.addProperty(key, value.toString()) } })
        if (src.activities.isEmpty()) // empty model
            return obj
        val uiViewElementSerializer = UIViewElementSerializer()
        val menuSerializer = MenuSerializer()
        val dialogSerializer = DialogSerializer()
        val jsonActivities = JsonArray()
        src.activities.forEach { activity ->
            val jsonActivity = JsonObject()
            jsonActivity.addProperty("name", activity.name)
            if (src.activityLabels[activity].isNotEmpty()) {
                val labels = JsonArray().also { src.activityLabels[activity].map { label -> it.add(label) } }
                jsonActivity.add("titles", labels)
            }
            if (src.toasts[activity].isNotEmpty()) {
                val toasts = JsonArray().also { src.toasts[activity].map { toast -> it.add(toast) } }
                jsonActivity.add("toasts", toasts)
            }

            val jsonLayouts = JsonArray()
            val layouts = src.activityLayouts[activity]
            layouts.forEach { layout ->
                val rootElement = layout.uiViewElement
                val jsonLayout = uiViewElementSerializer.serialize(rootElement, UIViewElement::class.java, context)
                jsonLayouts.add(jsonLayout)
            }
            jsonActivity.add("layouts", jsonLayouts)

            val orphanedFragmentLayouts = src.orphanedFragmentLayouts[activity]
            if (orphanedFragmentLayouts.isNotEmpty()) {
                val jsonFragmentLayouts = JsonArray()
                orphanedFragmentLayouts.forEach { rootElement ->
                    val jsonLayout = uiViewElementSerializer.serialize(rootElement, UIViewElement::class.java, context)
                    jsonFragmentLayouts.add(jsonLayout)
                }
                jsonActivity.add("orphanedFragments", jsonFragmentLayouts)
            }
            val menus = src.activityMenus[activity]
            if (menus.isNotEmpty()) {
                val jsonMenus = JsonArray()
                menus.forEach { menu ->
                    val jsonMenu = menuSerializer.serialize(menu, Menu::class.java, context)
                    jsonMenus.add(jsonMenu)
                }
                jsonActivity.add("menu", jsonMenus)
            }

            //TODO: save menus and dialogs from fragments
//            val fragments = src.fragmentMapping[activity]
//            val fragmentMenus = fragments.map { it.value to src.activityMenus[it.value] }
//            if (menus.isNotEmpty()) {
//                val jsonMenus = JsonArray()
//                menus.forEach { menu ->
//                    val jsonMenu = menuSerializer.serialize(menu, Menu::class.java, context)
//                    jsonMenus.add(jsonMenu)
//                }
//                jsonActivity.add("menu", jsonMenus)
//            }

            val dialogs = src.activityDialogs[activity]
            if (dialogs.isNotEmpty()) {
                val jsonDialogs = JsonArray()
                dialogs.forEach { dialog ->
                    val jsonDialog = dialogSerializer.serialize(dialog, Dialog::class.java, context)
                    jsonDialogs.add(jsonDialog)
                }
                jsonActivity.add("dialogs", jsonDialogs)
            }
            jsonActivities.add(jsonActivity)
        }
        obj.add("activities", jsonActivities)

        val jsonTransitions = JsonArray()
        src.transitions.forEach {
            val jsonTransition = JsonObject()
            jsonTransition.addProperty("scr", it.src.name)
            jsonTransition.addProperty("dest", it.dest.name)
            val jsonTriggers = JsonArray()
            it.trigger.forEach { (trigger, _) ->
                val jsonTrigger = trigger.guid
                jsonTriggers.add(jsonTrigger)
            }
            jsonTransition.add("trigger", jsonTriggers)
            jsonTransitions.add(jsonTransition)
        }
        obj.add("transitions", jsonTransitions)
        return obj
    }
}
