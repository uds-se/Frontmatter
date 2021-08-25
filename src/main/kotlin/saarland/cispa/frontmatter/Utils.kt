package saarland.cispa.frontmatter

import boomerang.preanalysis.BoomerangPretransformer
import mu.KLogger
import mu.KotlinLogging
import org.apache.commons.lang3.RandomStringUtils
import soot.G
import soot.Main
import soot.PackManager
import soot.RefType
import soot.Scene
import soot.SootClass
import soot.SootMethod
import soot.Type
import soot.Value
import soot.VoidType
import soot.jimple.IntConstant
import soot.jimple.Jimple
import soot.jimple.StaticFieldRef
import soot.jimple.StringConstant
import soot.jimple.infoflow.android.axml.AXmlNode
import soot.jimple.infoflow.cfg.LibraryClassPatcher
import soot.options.Options
import soot.tagkit.IntegerConstantValueTag
import soot.tagkit.StringConstantValueTag
import java.io.File
import java.io.FileNotFoundException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.*

object Utils {
    private var boomerangTimeout: Int = 30
    val logger: KLogger = KotlinLogging.logger {}
//    private val adapterInterface = sceneV.getSootClass("android.widget.Adapter")

    private val commonLibs = listOf(
        "com.flurry.", "net.hockeyapp.android.", "org.codehaus.jackson.", "com.google.analytics.", "com.google.protobuf.",
        "com.google.android.exoplayer2.", "com.google.firebase.", "com.google.android.datatransport."
    )//, "com.google.zxing.")
    private val javaClasses = setOf("java.lang.Integer", "java.lang.String")
    fun SootClass.isAndroidClass(): Boolean = listOf("androidx.", "android.", "com.google.gms", "com.google.android.gms", "com.android.", "dalvik.").any { name.startsWith(it) }
    fun SootClass.isSensitiveSystemClass(): Boolean = listOf(
        "java.net.",
        "java.security.",
        "java.nio.",
        "javax.crypto.",
        "org.apache.http",
        "java.lang.reflect.",
        "java.util.jar",
    ).any { name.startsWith(it) } || listOf(
        "java.io.File",
        "java.io.BufferedReader",
        "java.util.Scanner",
        "java.lang.Process",
        "java.lang.Runtime",
        "java.io.FileReader",
        "java.io.LineNumberReader",
        "java.io.DataInputStream"
    ).any { name == it }

    fun SootClass.isFromLib(): Boolean = commonLibs.any { name.startsWith(it) }
    fun SootClass.isDummy(): Boolean = this.name.startsWith("dummyMainClass")
    fun SootClass.isReal(): Boolean = this.methodCount != 0

    fun SootClass.isInSystemPackage() = listOf("java.", "javax.", "sun.", "org.omg.", "org.w3c.dom.", "jdk.").any { name.startsWith(it) }

    fun SootClass.isHandlerSubclass(): Boolean {
        if (this.isInterface)
            return false
        if (this.name == "android.os.Handler")
            return false
        return getSuperclasses(this).any { it.name == HANDLER_CLASS_NAME }
    }

    fun SootClass.isActivity(): Boolean = (!this.isInterface) && getSuperclasses(this).any { it.name in listOf("android.app.Activity", "android.view.Window") }
    fun SootClass.isContext(): Boolean = (!this.isInterface) && getSuperclasses(this).any { it.name in listOf("android.content.Context") }
    fun SootClass.isListActivity(): Boolean = (!this.isInterface) && getSuperclasses(this).any { it.name == "android.app.ListActivity" }
    fun SootClass.isListView(): Boolean = (!this.isInterface) && getSuperclasses(this).any { it.name == "android.widget.ListView" }

    fun SootClass.isDialog(): Boolean = (!this.isInterface) && getSuperclasses(this).any { it.name == "android.app.Dialog" }
    fun SootClass.isFragment(): Boolean = (!this.isInterface) && getSuperclasses(this).any { it.name in fragmentClasses }
    fun SootClass.isListFragment(): Boolean = (!this.isInterface) && getSuperclasses(this).any { it.name in listFragmentClasses }
    fun SootClass.isAdapter(): Boolean = Scene.v().implements(this.type, RefType.v("android.widget.Adapter"))
    fun SootClass.isReceiver(): Boolean = Scene.v().implements(this.type, RefType.v("android.content.BroadcastReceiver"))

    internal fun getSuperclasses(sootClass: SootClass): Collection<SootClass> {
        if (sootClass.isInterface) return emptySet()
        val superclasses = mutableListOf(sootClass)
        var current = sootClass
        while (current.hasSuperclass()) {
            superclasses.add(current.superclass)
            current = current.superclass
        }
        return superclasses
    }

    fun SootMethod.isAndroidMethod(): Boolean = !this.isDummy() && declaringClass.isAndroidClass()
    fun SootMethod.isSensitiveSystemMethod(): Boolean = !this.isDummy() && declaringClass.isSensitiveSystemClass()
    fun SootMethod.isSystemMethod(): Boolean = !this.isDummy() && declaringClass.isInSystemPackage()
    fun SootMethod.isInternalJavaMethod(): Boolean = !this.isDummy() && declaringClass.name in javaClasses
    fun SootMethod.isFromLib(): Boolean = !this.isDummy() && declaringClass.isFromLib()
    fun SootMethod.isDummy(): Boolean = this.name.startsWith("dummyMainMethod_") && declaringClass.isDummy()
    fun SootMethod.isDummyMain(): Boolean = this.name == "dummyMainMethod"

    fun <T> AXmlNode.getAttributeValue(tag: String): T? = getAttribute(tag)?.value as? T
//    fun <T> AXmlNode.getAttributeValue(tag: String): T? = getAttribute(tag)?.value as? T

    fun getIntConstant(value: Value): Int? {
        return when (value) {
            is IntConstant -> value.value
            is StaticFieldRef ->
                if (value.field.hasTag("IntegerConstantValueTag"))
                    (value.field.getTag("IntegerConstantValueTag") as IntegerConstantValueTag).intValue
                else null
            else -> {
//                logger.warn("Not a constant: $value")
                null
            }
        }
    }

    fun getStringConstant(value: Value): String? {
        return when (value) {
            is StringConstant -> value.value
            is StaticFieldRef ->
                if (value.field.hasTag("StringConstantValueTag"))
                    (value.field.getTag("StringConstantValueTag") as StringConstantValueTag).stringValue
                else null //TODO: resolve static fields
            else -> {
//                logger.warn("Not a constant: $value")
                null
            }
        }
    }

    /**
     * used to check if one class is a subclass of another class (or implements an interface)
     * */
    fun Scene.implements(targetClass: SootClass, superClass: SootClass): Boolean = this.getOrMakeFastHierarchy().canStoreClass(targetClass, superClass)
    fun Scene.implements(targetType: Type, superType: Type): Boolean = this.getOrMakeFastHierarchy().canStoreType(targetType, superType)

    fun getBoomerangTimeout(): Int {
        return boomerangTimeout
    }

    fun setBoomerangTimeout(timeout: Int) {
        boomerangTimeout = timeout
    }

    /**
     * set Soot options, proper android platform classpath, load app classes
     * also exclude android.support classes from analysis
     * init boomerang
     *
     * @param androidJar
     * @param apkFileLocation
     */
    fun initializeSoot(androidJar: Path, apkFileLocation: Path, packageName: String): Scene {
        G.reset()
        Options.v().set_no_bodies_for_excluded(true)
        Options.v().set_allow_phantom_refs(true)
        Options.v().set_allow_phantom_elms(true)
        Options.v().set_whole_program(true)
        Options.v().set_process_dir(listOf(apkFileLocation.toString()))
        Options.v().set_android_jars(androidJar.toString())
        Options.v().set_src_prec(Options.src_prec_apk_class_jimple)
        Options.v().set_keep_line_number(false)
        Options.v().set_keep_offset(false)
        Options.v().set_throw_analysis(Options.throw_analysis_dalvik)
        Options.v().set_process_multiple_dex(true)
        Options.v().set_ignore_resolution_errors(false) //XXX: may set to true to let soot load corrupted apps, though this may corrupt frontmatter heuristic
        Options.v().set_output_format(Options.output_format_none)

//        val includeList = listOf("java.lang.*", "java.util.*", "java.io.*", "sun.misc.*", "java.net.*", "javax.servlet.*", "javax.crypto.*")
//        Options.v().set_include(includeList)
        // we must exclude "com.google.android.gms.internal.firebase-perf.*" and "com.leanplum" since it contains method with the byte code, which Soot cannot process correctly
        val excludeList = listOf(
            "java.lang.*", "java.util.*", "java.io.*", "sun.misc.*", "java.net.*", "javax.servlet.*",
            "javax.crypto.*", "com.google.android.gms.internal.firebase-perf.*", "com.leanplum.*"
        )
        Options.v().set_exclude(excludeList)  //XXX:
        Options.v().set_include(listOf(packageName + ".*"))
        Options.v().setPhaseOption("jb", "use-original-names:false") // true value triggers bug in soot
        Options.v().setPhaseOption("jb.lp", "enabled:false")

        val classpath = Scene.v().getAndroidJarPath(androidJar.toString(), apkFileLocation.toString())
        Options.v().set_soot_classpath("VIRTUAL_FS_FOR_JDK" + File.pathSeparator + classpath)
        Main.v().autoSetOptions()
        addNecessaryAndroidClasses()
        Scene.v().loadNecessaryClasses()

        // remove android support lib classes from application classes list
        val supportClasses = Scene.v().applicationClasses.filter { it.name.startsWith("android.support") }
        supportClasses.forEach { it.setLibraryClass() }
        val androidClasses = Scene.v().applicationClasses.filter { it.isAndroidClass() }
        androidClasses.forEach { it.setLibraryClass() }
        val systemClasses = Scene.v().applicationClasses.filter { it.isInSystemPackage() || it.isAndroidClass() }
        systemClasses.forEach { it.setLibraryClass() }
        val libClasses = Scene.v().applicationClasses.filter { it.isFromLib() }
        libClasses.forEach { it.setLibraryClass() }
        excludeMethods()
        patchDateTimeView()
//        patchAndroidStubs()

        retrieveAllBodies()
        Scene.v().getOrMakeFastHierarchy()
        BoomerangPretransformer.v().reset()
        /** Patch the callgraph to support additional edges. We do this now, because during callback discovery, the context-insensitive callgraph
        algorithm would flood us with invalid edges.
        Patch the android.os.Handler implementation
        Patch the java.lang.Thread implementation
        Patch the android.app.Activity implementation (getApplication())
        Patch the java.util.Timer implementation
        Patch activity getFragmentManager() XXX: it uses android.app.FragmentManager which is deprecated in Android 28
        Patch the various overloads of Message.obtain()
         */
        val patcher = LibraryClassPatcher()
        patcher.patchLibraries()
//        patchViewGroupChildAt()
//        PackManager.v().getPack("jb").apply()
        PackManager.v().getPack("wjpp").apply()
        return Scene.v()
    }

    private fun patchViewGroupChildAt() {
        val vgMethod = Scene.v().getSootClass("android.view.ViewGroup").getMethod("android.view.View getChildAt(int)")
        val viewClass = Scene.v().getSootClass("android.view.View")
        val b = vgMethod.retrieveActiveBody()
        b.units.removeLast()
        b.units.removeLast()
        val returnStmt = Jimple.v().newReturnStmt(b.thisLocal)
        b.units.addLast(returnStmt)
        return
    }

    private fun patchDateTimeView() {
        val dtClassName = "android.widget.DateTimeView"
        val viewClass = Scene.v().getSootClass("android.view.View")
        val dtClass = Scene.v().forceResolve(dtClassName, SootClass.BODIES)
        if (dtClass.isPhantom) {
            dtClass.superclass = viewClass
        }
    }

    private fun retrieveAllBodies() {
        val clIt: Iterator<SootClass> = Scene.v().applicationClasses.snapshotIterator()
        while (clIt.hasNext()) {
            val cl = clIt.next()
            val methodIt: Iterator<SootMethod> = ArrayList(cl.methods).iterator()
            while (methodIt.hasNext()) {
                val m = methodIt.next()
                if (m.isConcrete) {
                    m.retrieveActiveBody()
                }
            }
        }
    }

    private fun excludeMethods() {
        val methodSignatures = listOf("<org.telegram.ui.ChatActivity: org.telegram.ui.ActionBar.ThemeDescription[] getThemeDescriptions()>")
        methodSignatures.forEach {
            Scene.v().grabMethod(it)?.isPhantom = true
        }
    }

    private fun addNecessaryAndroidClasses() {
        Options.v().classes().addAll(listOf("android.widget.EditText", "android.view.ViewGroup", "android.view.View"))
    }

    /**
     * copied from Scene.class, max java8 support
     * TODO: either supply rt.jar from java8 explicitly or read and unpackage java.base jmod file
     * */
    val javaClassPath: String
        get() {
            val javaHome = System.getProperty("java.home")
            var rtJar = Paths.get(javaHome, "lib", "rt.jar")
            if (Files.isRegularFile(rtJar))
                return rtJar.toAbsolutePath().toString()
            // in case we're not in JRE environment, try JDK
            rtJar = Paths.get(javaHome, "jre", "lib", "rt.jar")
            if (Files.isRegularFile(rtJar))
                return rtJar.toAbsolutePath().toString()
            // not in JDK either
            throw FileNotFoundException("Cannot find rt.jar.")
        }

    fun <T, S> Collection<T>.cartesianProduct(other: Iterable<S>): List<Pair<T, S>> {
        return cartesianProduct(other) { first, second -> first to second }
    }

    private fun <T, S, V> Collection<T>.cartesianProduct(other: Iterable<S>, transformer: (first: T, second: S) -> V): List<V> {
        return this.flatMap { first -> other.map { second -> transformer.invoke(first, second) } }
    }

    fun generateId(): String {
        return RandomStringUtils.randomAlphanumeric(STRING_ID_LENGTH)
    }

    fun getTargetMethodByName(activity: ActivityOrFragmentClass, targetMethodName: String): SootMethod? {
        val methodCandidate = activity.methodIterator().asSequence()
            .filter { it.name == targetMethodName }
            .toList()

//        val targetMethod = activity.getMethodByNameUnsafe(targetMethodName)
        val targetMethod = when {
            methodCandidate.size == 1 -> methodCandidate.first()
            methodCandidate.isEmpty() -> null
            else -> methodCandidate.firstOrNull { it.parameterCount == 1 }
        }
        if (targetMethod != null) return targetMethod
        val superclass = activity.superclassUnsafe
        if (superclass == null || superclass.isAndroidClass()) return null
        return getTargetMethodByName(superclass, targetMethodName)
    }

    fun getTargetMethodByNameArgs(activity: ActivityOrFragmentClass, targetMethodName: String, args: List<Type>, returnType: Type = VoidType.v()): SootMethod? {
        val targetMethod = activity.getMethodUnsafe(targetMethodName, args, returnType)
        if (targetMethod != null) return targetMethod
        val superclass = activity.superclassUnsafe
        if (superclass == null || superclass.isAndroidClass()) return null
        return getTargetMethodByNameArgs(superclass, targetMethodName, args, returnType)
    }
}
