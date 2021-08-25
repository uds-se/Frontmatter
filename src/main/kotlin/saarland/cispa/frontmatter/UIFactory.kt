package saarland.cispa.frontmatter


import mu.KLogging
import pxb.android.axml.AxmlVisitor
import saarland.cispa.frontmatter.Utils.getAttributeValue
import saarland.cispa.frontmatter.Utils.implements
import saarland.cispa.frontmatter.data.FragmentContainer
import saarland.cispa.frontmatter.data.FragmentType
import saarland.cispa.frontmatter.data.MenuItem
import saarland.cispa.frontmatter.data.UIText
import saarland.cispa.frontmatter.data.UIViewElement
import saarland.cispa.frontmatter.data.UIViewGroupElement
import soot.RefType
import soot.Scene
import soot.SootClass
import soot.jimple.infoflow.android.axml.AXmlAttribute
import soot.jimple.infoflow.android.axml.AXmlNode
import soot.jimple.infoflow.android.resources.ARSCFileParser

data class Attr(val name: String, val value: String, val variable: String)

const val MISSING_UI_ID = -1

open class UIFactory(val sceneV: Scene, private val resParser: ResourceParser) {
    private val uiEditText = RefType.v("android.widget.EditText")
    private val uiTextView = RefType.v("android.widget.TextView")
    private val uiViewGroup = RefType.v("android.view.ViewGroup")

    companion object : KLogging()

    fun create(layoutClass: SootClass, node: AXmlNode): UIViewElement {
        val attrs = collectAttributes(node)
        return createUIElement(layoutClass, attrs)
    }

    fun createFragment(fragmentClass: SootClass, rootNode: AXmlNode, parentId: Int?): FragmentContainer {
        val attrId: AXmlAttribute<*>? = rootNode.getAttribute("id")
        var id = if (attrId?.type == AxmlVisitor.TYPE_REFERENCE || attrId?.type == AxmlVisitor.TYPE_INT_HEX) attrId.value as Int else -1
        val attrTag: AXmlAttribute<*>? = rootNode.getAttribute("tag")
        val tag = if (attrTag?.type == AxmlVisitor.TYPE_STRING) attrTag.value as String else ""
        if (id == -1 && tag == "" && parentId != null) { // fragment borrows an id from its container
            id = parentId
        }
        return FragmentContainer(id, fragmentClass, FragmentType.XML, tag)
    }

    private fun collectAttributes(node: AXmlNode): Map<String, List<Attr>> {
        return node.attributes.values.asSequence()
            .filter { it.name != null }
            .filter { it.name.isNotBlank() }
            .filter { isAndroidNamespace(it.namespace) }
            .groupBy({
                when (it.name) {
                    in textAttributes -> "text"
                    "id" -> "id"
                    "onClick" -> "onClick"
                    else -> "other"
                }
            }, {
                when (it.name) {
                    "id" -> {
                        resolveId(it)
                    }
                    "onClick" -> {
                        resolveOnClick(it)
                    }
                    in textAttributes -> resolveTextAttr(it)
                    else -> resolveAttr(it)
                }
            })
    }


    private fun resolveId(attr: AXmlAttribute<*>): Attr {
        return when (attr.type) {
            AxmlVisitor.TYPE_REFERENCE -> {
                val res = resParser.parser.findResource(attr.value as Int) as ARSCFileParser.ReferenceResource
                Attr("id", res.referenceID.toString(), res.resourceName)
            }
            AxmlVisitor.TYPE_INT_HEX -> {
                val res = resParser.parser.findResource(attr.value as Int)
                if (res == null) {
                    Attr("id", attr.value.toString(), "not_found")
                } else
                    Attr("id", res.resourceID.toString(), res.resourceName)
            }
            AxmlVisitor.TYPE_STRING -> {
                val attrName = (attr.value as String).substringAfterLast('/')
                val res = resParser.parser.findResourceByName("id", attrName)
                if (res == null) {
                    logger.warn("Unknown id found ${attr.value}")
                    Attr("id", MISSING_UI_ID.toString(), "dummy")
                } else
                    Attr("id", res.resourceID.toString(), res.resourceName)
            }
            else -> {
                throw IllegalStateException("Unknown attribute type")
            }
        }
    }

    private fun resolveOnClick(attr: AXmlAttribute<*>): Attr {
        require(attr.type == AxmlVisitor.TYPE_STRING)
        return Attr("onClick", attr.value.toString(), "")
    }


    private fun resolveTextAttr(attr: AXmlAttribute<*>): Attr {
        // TODO: @style/???
        return when (attr.type) {
            AxmlVisitor.TYPE_STRING -> Attr(attr.name, attr.value.toString(), "")
            AxmlVisitor.TYPE_INT_HEX -> {
                val res = resParser.parser.findResource(attr.value as Int) as? ARSCFileParser.StringResource
                if (res == null) {
                    logger.warn("Cannot find a resource with id ${attr.value} for ${attr.name}")
                    Attr(attr.name, attr.value.toString(), "")
                } else
                    Attr(attr.name, res.value, res.resourceName)
            }
            else -> {
                logger.warn("Unexpected text attr ${attr.value}")
                Attr(attr.name, attr.value.toString(), "dummy")
            }
        }
    }

    private fun resolveAttr(attr: AXmlAttribute<*>): Attr {
        return when (attr.type) {
            AxmlVisitor.TYPE_INT_HEX -> {
                if (isId(attr.value as Int)) {
                    when (val res = resParser.parser.findResource(attr.value as Int)) {
                        null -> Attr(attr.name, attr.value.toString(), "not_found")
                        is ARSCFileParser.BooleanResource -> // workaround: axml parser sometimes wrongly considers references as booleans
                            Attr(attr.name, res.resourceID.toString(), res.resourceName)
                        else -> Attr(attr.name, res.toString(), res.resourceName) // all other types
                    }
                } else {
                    Attr(attr.name, attr.value.toString(), "")
                }
            }
            else -> {
                Attr(attr.name, attr.value.toString(), "")
            }
        }
    }

    fun isId(id: Int): Boolean {
        val resId = resParser.parser.parseResourceId(id)
        return resId.packageId > 0 // heuristic to filter out non id integers
    }

    fun createMenuItem(rootNode: AXmlNode): MenuItem {
        val id = rootNode.getAttributeValue("id") ?: 0
        val titleAttr = rootNode.getAttribute("title") ?: rootNode.getAttribute("icon")
        val title = if (titleAttr != null) resolveAttr(titleAttr) else Attr("title", "", "undefined")
        val parentElement = rootNode.parent
        val groupId = if (parentElement.tag == "group") parentElement.getAttributeValue("id") ?: 0 else 0
        val onClick = rootNode.getAttribute("onClick")?.let { resolveOnClick(it) }?.value
        val attributes = rootNode.attributes.values
            .asSequence()
            .filter { it.name != null && it.name.isNotBlank() }
            .filterNot { it.name in setOf("id", "title", "onClick") }
            .map { resolveAttr(it) }
            .toList()
        return MenuItem(id, title, groupId, onClick, attributes)
    }

    /**
     * Creates an empty layout control that corresponds to the given class
     * @param layoutClass
     * The layout class in Android that implements the control
     * @return The newly created layout control
     */
    private fun createUIElement(layoutClass: SootClass, attrs: Map<String, List<Attr>>): UIViewElement {
        val idAttr = attrs["id"]?.firstOrNull()
        val id = idAttr?.value?.toInt() ?: MISSING_UI_ID
        val idVariable = idAttr?.variable ?: ""
        val onClick = attrs["onClick"]?.firstOrNull()?.value
        val uiElement = when { // from particular to general
            sceneV.implements(layoutClass.type, uiEditText) -> {
                val inputType = (attrs["other"]?.firstOrNull { it.name == "inputType" }?.value?.toInt() ?: 0)
                UIText(id, layoutClass, inputType)
            }
            sceneV.implements(layoutClass.type, uiTextView) -> UIText(id, layoutClass)
            sceneV.implements(layoutClass.type, uiViewGroup) -> UIViewGroupElement(id, layoutClass)
            layoutClass.isPhantom -> UIViewGroupElement(id, layoutClass)
            else -> UIViewElement(id, layoutClass)
        }
        uiElement.xmlCallback = onClick
        uiElement.idVariable = idVariable
        attrs["text"]?.let { uiElement.textAttributes.addAll(it) }
        attrs["other"]?.onEach { uiElement.otherAttributes[it.name] = it }
        return uiElement
    }

    /**
     * Skip checking of the namespace
     * system
     * xmlns:android="http://schemas.android.com/apk/res/android" - for android attributes
     * xmlns:app="http://schemas.android.com/apk/res-auto" - for android support libs and other attrs in an app
     *
     * @param _ns The namespace to check
     * @return True if the namespace belongs to Android, otherwise false
     */
    private fun isAndroidNamespace(_ns: String?): Boolean {
        if (_ns == null || _ns.isEmpty()) { //accept no namespace
            return true
        }
        val ns = _ns.trim { it <= ' ' }.trim('*')
        if (ns.startsWith("http://schemas.android.com/apk/")) {
            return true
        }
        logger.warn("Unexpected namespace: {}", _ns)
        return false
    }


}
