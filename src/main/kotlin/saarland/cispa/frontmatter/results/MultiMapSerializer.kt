package saarland.cispa.frontmatter.results

import com.google.gson.*
import soot.SootClass
import soot.util.MultiMap

import java.lang.reflect.Type

class MultiMapSerializer : JsonSerializer<MultiMap<SootClass, SootClass>> {

    private val ignoredActivities = setOf("android.content.Context")

    override fun serialize(src: MultiMap<SootClass, SootClass>, typeOfSrc: Type, context: JsonSerializationContext): JsonElement {
        val list = JsonArray()
        for (source in src.keySet()) {
            val targets = src.get(source)
            for (target in targets) {
                if (ignoredActivities.contains(source.name) || ignoredActivities.contains(target.name)) {
                    continue
                }
                val jsonObject = JsonObject()
                jsonObject.addProperty("src", source.name)
                jsonObject.addProperty("dest", target.name)
                list.add(jsonObject)
            }
        }
        return list
    }

}
