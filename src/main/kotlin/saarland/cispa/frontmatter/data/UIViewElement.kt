package saarland.cispa.frontmatter.data

import saarland.cispa.frontmatter.Attr
import saarland.cispa.frontmatter.FragmentClass
import saarland.cispa.frontmatter.Utils.generateId
import saarland.cispa.frontmatter.textSeparator
import soot.SootClass
import soot.SootMethod

sealed class UIElement(val id: Int) {
    val guid = generateId()

    abstract fun copy(): UIElement
    abstract fun copy(newId: Int): UIElement
}

open class UIViewElement(id: Int = -1, val viewClass: SootClass) : UIElement(id) {
    val textAttributes = mutableSetOf<Attr>()
    val otherAttributes = mutableMapOf<String, Attr>()
    val text: String
        get() {
            return textAttributes.filter { it.value.isNotBlank() }.joinToString(textSeparator)
        }
    var xmlCallback: String? = null //XXX:callback
    val listeners = mutableSetOf<SootMethod>()
    var idVariable: String = ""
    open fun hasChildren() = false
    open fun addChild(child: UIViewElement) = false
    open fun addChildren(nodes: Collection<UIViewElement>) = false
    open fun getChild(id: Int): UIViewElement? = null
    open fun getAllChildrenFlatten() = emptySet<UIViewElement>()

    override fun copy(): UIViewElement {
        return copy(id)
    }

    override fun copy(newId: Int): UIViewElement {
        val newEl = UIViewElement(newId, viewClass)
        newEl.textAttributes.addAll(this.textAttributes)
        newEl.otherAttributes.putAll(this.otherAttributes)
        newEl.xmlCallback = this.xmlCallback
        newEl.idVariable = this.idVariable
        return newEl
    }

    fun addOtherAttr(name: String, attr: Attr) {
        otherAttributes[name] = attr
    }
}

open class UIViewGroupElement(id: Int, viewClass: SootClass) : UIViewElement(id, viewClass) {
    val children = mutableListOf<UIViewElement>()
    override fun addChild(child: UIViewElement): Boolean {
        if (this == child)
            error("Reqursive child")
        return children.add(child)
    }

    override fun addChildren(nodes: Collection<UIViewElement>): Boolean {
        if (this in nodes)
            error("Reqursive children")
        return children.addAll(nodes)
    }

    override fun hasChildren() = children.isNotEmpty()
    override fun getAllChildrenFlatten(): Set<UIViewElement> = children.flatMap { it.getAllChildrenFlatten() }.toSet().union(children).plus(this)
    override fun getChild(id: Int): UIViewElement? {
        val workList = mutableListOf<UIViewElement>()
        workList.add(this)
        while (workList.isNotEmpty()) {
            val item = workList.removeAt(0)
            if (item.id == id) return item
            if (item is UIViewGroupElement)
                workList.addAll(item.children)
        }
        return null
    }

    override fun copy(): UIViewElement {
        return copy(id)
    }

    override fun copy(newId: Int): UIViewElement {
        val newEl = UIViewGroupElement(newId, viewClass)
        newEl.textAttributes.addAll(this.textAttributes)
        newEl.otherAttributes.putAll(this.otherAttributes)
        newEl.xmlCallback = this.xmlCallback
        newEl.idVariable = this.idVariable
        this.children.forEach { newEl.addChild(it.copy()) }
        return newEl
    }
}

class MergeContainer(id: Int, val children: List<UIViewElement>) : UIElement(id) {
    override fun copy(): MergeContainer {
        return copy(id)
    }

    override fun copy(newId: Int): MergeContainer {
        return MergeContainer(newId, this.children.map { it.copy() })
    }
}

//class UIButton(id: Int, type: String, viewClass: SootClass) : UIViewElement(id, viewClass) {}

private const val TYPE_MASK_CLASS = 0x0000000f
private const val TYPE_MASK_VARIATION = 0x00000ff0

class UIText(id: Int, viewClass: SootClass, inputType: Int = 0) : UIViewElement(id, viewClass) {
    val inputTypeValues = mutableSetOf<Int>()

    init {
        if (inputType > 0) {
            inputTypeValues.add(inputType)
        }
    }

    val inputTypes: List<String>
        get() = inputTypeValues.map { getClassType(it) }

    private fun getClassType(type: Int): String {
        return when (type and TYPE_MASK_CLASS) {
            0x00000001 -> getTextVariation(type and TYPE_MASK_VARIATION)
            0x00000002 -> getNumberVariation(type and TYPE_MASK_VARIATION)
            0x00000003 -> getPhoneVariation(type and TYPE_MASK_VARIATION)
            0x00000004 -> getDateVariation(type and TYPE_MASK_VARIATION)
            else -> "" // 0x00000000 ~ NULL
        }
    }

    private fun getDateVariation(type: Int): String {
        return when (type) {
            0x00000010 -> "date"
            0x00000020 -> "time"
            else -> "date_time"
        }
    }

    private fun getPhoneVariation(type: Int): String {
        return "phone"
    }

    private fun getNumberVariation(type: Int): String {
        return when (type) {
            0x00000010 -> "numeric_password"
            else -> "number"
        }
    }

    private fun getTextVariation(type: Int): String {
        return when (type) {
            0x00000010 -> "uri"
            0x00000020, 0x000000d0 -> "email"
            0x00000030 -> "email_subject"
            0x00000040 -> "short_message"
            0x00000050 -> "long_message"
            0x00000060 -> "person_name"
            0x00000070 -> "address"
            0x00000080, 0x000000e0 -> "password"
            0x00000090 -> "visible_password"
            0x000000a0 -> "web_edit_text"
            0x000000b0 -> "text_filter"
            0x000000c0 -> "text_phonetic"
            else -> "text"
        }
    }

    override fun copy(): UIText {
        return copy(id)
    }

    override fun copy(newId: Int): UIText {
        val newEl = UIText(newId, viewClass)
        newEl.textAttributes.addAll(this.textAttributes)
        newEl.otherAttributes.putAll(this.otherAttributes)
        newEl.xmlCallback = this.xmlCallback
        newEl.idVariable = this.idVariable
        newEl.inputTypeValues.addAll(this.inputTypeValues)
        return newEl
    }
}

enum class FragmentType { XML, DYNAMIC }

open class FragmentContainer(id: Int, fragmentClass: FragmentClass, val type: FragmentType, val tag: String = "") : UIViewGroupElement(id, fragmentClass) {

    override fun copy(): FragmentContainer {
        return copy(id)
    }

    override fun copy(newId: Int): FragmentContainer {
        val newEl = FragmentContainer(newId, viewClass, type, tag)
        newEl.idVariable = this.idVariable
        return newEl
    }
}

open class Menu(val items: MutableList<MenuItem> = mutableListOf(), val subMenu: SubMenu? = null, var type: MenuType = MenuType.UNDEFINED, var listener: SootMethod? = null) {
    fun copy(): Menu {
        return Menu(items.toMutableList(), subMenu)
    }

    fun getAllItems(): List<MenuItem> {
        return if (subMenu != null)
            items + subMenu.items
        else items
    }
}

enum class MenuType { OPTIONS, CONTEXT, POPUP, PANEL, OTHER, UNDEFINED }

class SubMenu(val rootItem: MenuItem, val items: MutableList<MenuItem> = mutableListOf()) {
    fun copy(): SubMenu {
        return SubMenu(rootItem, items.toMutableList())
    }
}

class MenuItem(id: Int, val title: Attr, val groupId: Int, val xmlCallback: String?,
               val attributes: List<Attr>, val listeners: MutableList<SootMethod> = mutableListOf()) : UIElement(id) {
    override fun copy(): MenuItem {
        return MenuItem(id, title, groupId, xmlCallback, attributes.toList(), listeners)
    }

    override fun copy(newId: Int): UIElement {
        return MenuItem(newId, title, groupId, xmlCallback, attributes.toList(), listeners)
    }
}

class Dialog(val title: Set<String>, val message: Set<String>, val buttons: List<DialogButton>, val icon: Set<String>)

class DialogButton(val label: Set<String>, val type: ButtonType, val listener: Set<SootMethod>) : UIElement(0) {
    override fun copy(): UIElement {
        return DialogButton(label, type, listener)
    }

    override fun copy(newId: Int): UIElement {
        return DialogButton(label, type, listener)
    }
}

enum class ButtonType { POSITIVE, NEGATIVE, NEUTRAL }
