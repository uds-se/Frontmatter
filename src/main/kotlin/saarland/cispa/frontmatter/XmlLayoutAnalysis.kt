package saarland.cispa.frontmatter

import pxb.android.axml.AxmlVisitor
import pxb.android.axml.NodeVisitor
import saarland.cispa.frontmatter.Utils.getAttributeValue
import saarland.cispa.frontmatter.Utils.getTargetMethodByNameArgs
import saarland.cispa.frontmatter.Utils.implements
import saarland.cispa.frontmatter.data.FragmentContainer
import saarland.cispa.frontmatter.data.Menu
import saarland.cispa.frontmatter.data.MenuItem
import saarland.cispa.frontmatter.data.MergeContainer
import saarland.cispa.frontmatter.data.SubMenu
import saarland.cispa.frontmatter.data.UIElement
import saarland.cispa.frontmatter.data.UIViewElement
import saarland.cispa.frontmatter.data.UIViewGroupElement
import soot.RefType
import soot.Scene
import soot.SootClass
import soot.jimple.infoflow.android.axml.AXmlAttribute
import soot.jimple.infoflow.android.axml.AXmlHandler
import soot.jimple.infoflow.android.axml.AXmlNode
import soot.jimple.infoflow.android.axml.parsers.AXML20Parser
import soot.jimple.infoflow.android.resources.ARSCFileParser
import soot.jimple.infoflow.android.resources.AbstractResourceParser
import soot.jimple.infoflow.android.resources.IResourceHandler
import soot.util.HashMultiMap
import soot.util.MultiMap
import java.io.IOException

/**
 * parser for analyzing the layout XML files inside an android application
 */
class XmlLayoutAnalysis(val sceneV: Scene, apkInfo: ApkInfo) : AbstractResourceParser() {

    private val skippedViewClasses = setOf("DateTimeView")
    private val menuAXmlRootNodes = mutableMapOf<Int, AXmlNode>()
    private val layoutAXmlRootNodes = mutableMapOf<Int, AXmlNode>()
    val flatLayouts: MultiMap<Int, UIViewElement> = HashMultiMap()
    private val layoutRootNodes = mutableMapOf<Int, UIElement>()

    private val menuLayouts = mutableMapOf<Int, Menu>()
    val callbackMethods: MultiMap<Int, String> = HashMultiMap()

    private val resParser = apkInfo.resourceParser
    val uiFactory = UIFactory(sceneV, resParser)
    private val packageName = apkInfo.packageName
    private val targetAPK = apkInfo.targetAPK
    private val layoutIdToName = mutableMapOf<Int, String>()

    private val scView = sceneV.getSootClassUnsafe("android.view.View")
    val layoutsWithId: MultiMap<Int, UIViewElement>
        get() {
            val res = HashMultiMap<Int, UIViewElement>()
            for (layoutId in flatLayouts.keySet()) {
                res.putAll(layoutId, flatLayouts.get(layoutId))
            }
            return res
        }


    init {
        logger.info("Perform layout analysis")
        readLayoutFiles()
        parseLayoutFiles()
        collectCallbacks()
        parseMenuFiles()
    }

    fun getMenu(id: Int, context: ActivityOrFragmentClass): Menu? {
        return menuLayouts.getOrElse(id) {
            logger.warn("Unknown menu id $id")
            return null
//            error("Unknown menu id $id")
        }.copy().also { initXmlCallbacks(it, context) }
    }

    fun getLayout(id: Int, context: ActivityOrFragmentClass): UIElement? {
        val layoutTemplate = layoutRootNodes.getOrElse(id) {
            logger.warn("Unknown layout id $id")
            return null
        } //error("Unknown layout id $id")
        val layout = layoutTemplate.copy()
        if (layout is MergeContainer)
            layout.children.forEach { initXmlCallbacks(it, context) }
        if (layout is UIViewElement)
            initXmlCallbacks(layout, context)
        return layout
    }

    /**
     * Parses all layout XML files in the given APK file and loads the IDs of the
     * user controls in it. This method directly executes the analyses without
     * registering any Soot phases.
     */
    private fun readLayoutFiles() {
        handleAndroidResourceFiles(targetAPK.toString(), null, IResourceHandler { fileName, fileNameFilter, stream ->
            // We only process valid layout XML files from layouts and menu folders!
            if (!fileName.startsWith("res/layout") && !fileName.startsWith("res/menu"))
                return@IResourceHandler
            if (!fileName.endsWith(".xml")) {
                logger.warn("Skipping file {} in layout folder...", fileName)
                return@IResourceHandler
            }

            // Get the fully-qualified class name
            var entryClass = fileName.substring(0, fileName.lastIndexOf("."))
            if (packageName.isNotEmpty())
                entryClass = "$packageName.$entryClass"

            // We are dealing with resource files
            if (fileNameFilter != null) {
                var found = false
                for (s in fileNameFilter)
                    if (s.equals(entryClass, ignoreCase = true)) {
                        found = true
                        break
                    }
                if (!found)
                    return@IResourceHandler
            }

            try {
                val handler = AXmlHandler(stream, AXML20Parser())
                when {
                    fileName.startsWith("res/menu") -> {
                        val menuId = resParser.menus[fileName] ?: error("Unknown id for $fileName")
                        menuAXmlRootNodes[menuId] = handler.document.rootNode
                    }
                    fileName.startsWith("res/layout") -> {
                        val layoutId = resParser.layouts[fileName]
                        if (layoutId != null) {
                            layoutIdToName[layoutId] = fileName
                            layoutAXmlRootNodes[layoutId] = handler.document.rootNode
                        } else logger.warn("Unknown id for $fileName")
                    }
                }
            } catch (e: IOException) {
                logger.error("Could not read binary XML file", e)
            }
        })
    }

    private fun parseLayoutFiles() {
        for ((layoutId, rootNode) in layoutAXmlRootNodes) {
            val layout = parseLayoutRootNode(layoutId, rootNode)
            if (layout != null)
                layoutRootNodes[layoutId] = layout
        }
    }

    private fun parseLayoutRootNode(layoutId: Int, rootNode: AXmlNode): UIElement? {
        return if (isMergeNode(rootNode)) {
            parseMergeNode(rootNode, layoutId, null)
        } else {
            parseLayoutNode(layoutId, rootNode, null)
        }
    }

    /**
     * Parses the layout file with the given root node
     *
     * @param layoutId The full path and file name of the file being parsed
     * @param node   The root node from where to start parsing
     */
    private fun parseLayoutNode(layoutId: Int, node: AXmlNode, parent: UIViewGroupElement?): UIElement? {
        if (node.tag == null || node.tag.isEmpty()) {
            logger.warn("Encountered a null or empty node name in file $layoutId, skipping node...")
            return null
        }
        when (val tagName = node.tag.trim { it <= ' ' }) {
            // special tag
            "dummy",
            "merge" -> error("Unexpected tag $tagName")
            "include" -> {
                return addIncludeElement(node, parent)// include tag always has a parent and is terminal
            }
            "fragment" -> {
                val fragment = parseFragment(node, parent?.id)
                return if (fragment != null && node.children.isNotEmpty()) {
                    // a workaround for a rare case: <fragment...><Button></fragment> - erronious pattern, but it's valid
                    val nodes = mutableListOf<UIViewElement>(fragment).also { it.addAll(parseChildren(layoutId, node, parent)) }
                    MergeContainer(0, nodes)
                } else
                    fragment
            }
            else -> { // normal tags
                val uiElement = parseUIElement(tagName, node)
                if (uiElement is UIViewGroupElement) {
                    val childLayouts = parseChildren(layoutId, node, uiElement).toList()
                    childLayouts.forEach { uiElement.addChild(it) }
                } else if (node.children.isNotEmpty()) {
                    logger.warn("Unexpected children inside $uiElement")
                }
                return uiElement
            }
        }
    }

    private fun parseChildren(layoutId: Int, node: AXmlNode, parent: UIViewGroupElement?): List<UIViewElement> {
        val res = mutableListOf<UIViewElement>()
        for (child in node.children) {
            when (val childLayout = parseLayoutNode(layoutId, child, parent)) {
                is MergeContainer -> { // unwrap merge
                    res.addAll(childLayout.children)
                }
                is UIViewElement -> {
                    res.add(childLayout)
                }
            }
        }
        return res
    }

    private fun parseUIElement(tagName: String, node: AXmlNode): UIViewElement? {
        val layoutClassName = if ("view" == tagName) node.getAttributeValue<String>("class") ?: null
            .also {
                logger.warn("Android 'view' node without class name detected: $node")
            } else null
        val layoutClass = getLayoutClass(layoutClassName ?: tagName)
        return if (layoutClass != null) {
            uiFactory.create(layoutClass, node)
        } else {
            if (tagName in skippedViewClasses)
                logger.warn("Unknown layout node type: $node")
            null
        }
    }

    private fun isRealClass(sc: SootClass?): Boolean {
        return if (sc == null) false else !(sc.isPhantom && sc.methodCount == 0 && sc.fieldCount == 0)
    }

    private fun getLayoutClass(_className: String): SootClass? {
        var className = _className
        // Cut off some junk returned by the parser
        className = className.trim(';')
        if (className.contains("(") || className.contains("<") || className.contains("/")) {
            logger.warn("Invalid class name $className")
            return null
        }
        var sc: SootClass? = sceneV.forceResolve(className, SootClass.BODIES)
        if ((sc == null || sc.isPhantom) && packageName.isNotEmpty())
            sc = sceneV.forceResolve("$packageName.$className", SootClass.BODIES)
        if (!isRealClass(sc))
            sc = sceneV.forceResolve("android.view.$className", SootClass.BODIES)
        if (!isRealClass(sc))
            sc = sceneV.forceResolve("android.widget.$className", SootClass.BODIES)
        if (!isRealClass(sc))
            sc = sceneV.forceResolve("android.webkit.$className", SootClass.BODIES)
        if (!isRealClass(sc)) {
            sc = if (skippedViewClasses.any(className::endsWith)) {
                // android.widget.DateTimeView class is not recognized for some android versions
                sceneV.forceResolve("android.widget.DateTimeView", SootClass.BODIES)
            } else {
                logger.warn("Could not find layout class {}", className)
                sceneV.forceResolve(className, SootClass.BODIES)
            }
        }
        return sc
    }

    private fun isMergeNode(node: AXmlNode): Boolean {
        return node.tag == "merge"
    }

    private fun parseMergeNode(rootNode: AXmlNode, layoutId: Int, parent: UIViewGroupElement?): MergeContainer? {
        val layouts = mutableListOf<UIViewElement>()
        rootNode.children.forEach {
            when (val layout = parseLayoutNode(layoutId, it, parent)) {
                is MergeContainer -> layouts.addAll(layout.children)
                is UIViewElement -> layouts.add(layout)
                else -> {
                    logger.error("Unexpected node: $it")
                    return null

                }
            }
        }
        return MergeContainer(layoutId, layouts)
    }

    /**
     * Parses the attributes required for a layout file inclusion
     *
     * @param includeNode   The AXml node containing the attributes
     */
    private fun addIncludeElement(includeNode: AXmlNode, parent: UIViewGroupElement?): UIElement? {
        val idAttr: AXmlAttribute<*>? = includeNode.getAttribute("id")
        val includedViewId = if (idAttr?.type == AxmlVisitor.TYPE_REFERENCE || idAttr?.type == AxmlVisitor.TYPE_INT_HEX) idAttr.value as Int else null
        val layoutAttr = includeNode.getAttribute("layout") ?: return null
        if (!((layoutAttr.type == NodeVisitor.TYPE_REFERENCE || layoutAttr.type == NodeVisitor.TYPE_INT_HEX) && layoutAttr.value is Int)) return null
        // We need to get the target XML file from the binary manifest
        val targetRes = resParser.parser.findResource(layoutAttr.value as Int) // it returns only the first match
        if (targetRes == null || targetRes !is ARSCFileParser.StringResource) {
            logger.warn("Invalid layout node: ${layoutAttr.value} in include tag in layout XML, was ${targetRes?.javaClass?.name}")
            return null
        }
        val includedFile = targetRes.value
        val includedLayoutId = resParser.layouts[includedFile] ?: error("Unknown included layout $includedFile")
        val includedRootNode = layoutAXmlRootNodes.getValue(includedLayoutId)
        val includedRootNodeParent = includedRootNode.parent
        if (parent != null) { //parent reassignment may be necessary for fragments
            includedRootNode.parent = includeNode.parent
        }
        val includedView = parseIncludedNode(includedLayoutId, includedViewId, includedRootNode, parent)
        includedRootNode.parent = includedRootNodeParent
        return includedView
    }

    private fun parseIncludedNode(
        includedLayoutId: Int, includedViewId: Int?, includedNode: AXmlNode,
        parent: UIViewGroupElement?
    ): UIElement? {
        return if (isMergeNode(includedNode)) {
            if (includedViewId != null) logger.warn("Unexpected view id reassignment for merge nodes")
            parseMergeNode(includedNode, includedLayoutId, parent)
        } else {
            val includedRootElement = parseLayoutNode(includedLayoutId, includedNode, parent)
            if (includedViewId != null) includedRootElement?.copy(includedViewId) else includedRootElement
        }
    }

    /**
     * Adds a fragment found in an XML file to the result set
     */
    private fun parseFragment(fragmentNode: AXmlNode, parentId: Int?): FragmentContainer? {
        val attr: AXmlAttribute<*>? = fragmentNode.getAttribute("class") ?: fragmentNode.getAttribute("name")
        if (attr == null) {
            logger.warn("Fragment without class name or id detected")
            return null
        }
        val fragmentClass = getLayoutClass(attr.value.toString()) ?: return null
        return uiFactory.createFragment(fragmentClass, fragmentNode, parentId)
    }

    private fun parseMenuFiles() {
        for ((menuId, rootNode) in menuAXmlRootNodes) {
            if (rootNode.tag != "menu") {
                logger.warn("Wrong menu tag: ${rootNode.tag} in $menuId")
                continue
            }
            menuLayouts[menuId] = parseMenuNode(rootNode)
        }
    }

    private fun parseMenuNode(rootNode: AXmlNode): Menu {
        require(rootNode.tag == "menu") { "Encountered unknown tag ${rootNode.tag} while parsing menu layout" }
        val menuItems = mutableListOf<MenuItem>()
        var subMenu: SubMenu? = null // there can be only one subMenu
        for (item in rootNode.children) {
            when (item.tag) {
                "item" -> {
                    parseMenuItem(item, menuItems)?.let {
                        subMenu = it
                    }
                }
                "group" -> {
                    for (groupItem in item.children) {
                        parseMenuItem(groupItem, menuItems)?.let {
                            subMenu = it
                        }
                    }
                }
            }
        }
        return Menu(menuItems, subMenu)
    }

    private fun parseMenuItem(item: AXmlNode, menuItems: MutableList<MenuItem>): SubMenu? {
        val element = createMenuItem(item)
        menuItems.add(element)
        val subMenuNode = item.children.firstOrNull { it.tag == "menu" }
        return if (subMenuNode != null) {
            val subMenu = parseMenuNode(subMenuNode)
            SubMenu(element, subMenu.items)
        } else null
    }

    private fun createMenuItem(rootNode: AXmlNode): MenuItem {
        return uiFactory.createMenuItem(rootNode)
    }


    private fun collectCallbacks() {
        for ((layoutId, uiElements) in flatLayouts) {
            uiElements.xmlCallback?.let {
                callbackMethods.put(layoutId, it)
            }
        }
    }

    private fun initXmlCallbacks(uiViewElement: UIViewElement, activity: ActivityOrFragmentClass) {
        uiViewElement.getAllChildrenFlatten().forEach { child ->
            val xmlCallback = child.xmlCallback
            if (xmlCallback != null) {
                getTargetMethodByNameArgs(activity, xmlCallback, listOf(RefType.v("android.view.View")))?.let {
                    child.listeners.add(it)
                }
            }
        }
    }

    private fun initXmlCallbacks(menu: Menu, activity: ActivityOrFragmentClass) {
        menu.getAllItems().forEach { item ->
            val xmlCallback = item.xmlCallback
            if (xmlCallback != null) {
                getTargetMethodByNameArgs(activity, xmlCallback, listOf(RefType.v("android.view.View")))?.let { item.listeners.add(it) }
            }
        }
    }
}


