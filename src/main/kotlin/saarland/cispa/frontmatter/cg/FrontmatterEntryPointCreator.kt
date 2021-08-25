package saarland.cispa.frontmatter.cg

import mu.KLogging
import saarland.cispa.frontmatter.DUMMY_ACTIVITY_NAME
import soot.Local
import soot.Scene
import soot.SootClass
import soot.SootField
import soot.SootMethod
import soot.Value
import soot.jimple.Jimple
import soot.jimple.NullConstant
import soot.jimple.Stmt
import soot.jimple.infoflow.android.entryPointCreators.AndroidEntryPointConstants
import soot.jimple.infoflow.android.entryPointCreators.AndroidEntryPointCreator
import soot.jimple.infoflow.android.manifest.ProcessManifest
import soot.util.MultiMap
import java.util.*

class FrontmatterEntryPointCreator(manifest: ProcessManifest,
                                   private val components: Collection<SootClass>, private val callbackSignatures: MultiMap<SootClass, SootMethod>,
                                   private val fragments: MultiMap<SootClass, SootClass>) : AndroidEntryPointCreator(manifest, components) {
    //    private val entryPointCreator = AndroidEntryPointCreator(manifest, components)
    companion object : KLogging()

    private val allFragments = fragments[Scene.v().getSootClass(DUMMY_ACTIVITY_NAME)]

    init {
        this.callbackFunctions = callbackSignatures
        this.setFragments(fragments)
    }

    override fun getRequiredClasses(): Collection<String> {
        return components.map { it.name }
    }

    override fun getAdditionalMethods(): Collection<SootMethod> {
        return emptySet()
    }

    override fun getAdditionalFields(): Collection<SootField> {
        return emptySet()
    }

    override fun createDummyMainInternal(): SootMethod {
        super.createDummyMainInternal()
        logger.warn("addItemsWithCallbacks DISABLED")
        // addItemsWithCallbacks() // add generation of other callbacks to mainMethod

        // they are created by AndroidEntryPointCreator but because of late Activity-Fragment binding they are not added to the dummyMain method
        addFragmentEntryPoints()
        return mainMethod
    }

    private fun addFragmentEntryPoints() {
        body.units.removeLast() //remove return statement
        for (fragment in allFragments) {
            val dummyMethodName = getDummyMethod(fragment)
            // Call the fragment's main method
            val args = ArrayList<Value>()
            args.add(NullConstant.v())
            args.add(NullConstant.v())
            body.units.add(Jimple.v().newInvokeStmt(Jimple.v().newStaticInvokeExpr(dummyMethodName.makeRef(), args)))
        }
        body.units.add(Jimple.v().newReturnVoidStmt())
    }

    private fun getDummyMethod(component: SootClass): SootMethod {
        var componentPart = component.name
        if (componentPart.contains("."))
            componentPart = componentPart.replace("_", "__").replace(".", "_")
        val methodName = "${dummyMethodName}_$componentPart"
        return Scene.v().getSootClass(dummyClassName).getMethodByName(methodName)
    }

    private fun addItemsWithCallbacks() {
        body.units.removeLast() //remove return statement
        callbackSignatures.keySet().filter { it !in components }
            .filter { it !in allFragments }
            .forEach { sootClass ->
                val sootMethods = callbackSignatures.get(sootClass)
                val componentCreator = ViewEntryPointCreator(sootClass, sootMethods, manifest)
                val callbackMethod = componentCreator.createDummyMain()
                val invokeCallBack = Jimple.v().newInvokeStmt(Jimple.v().newStaticInvokeExpr(callbackMethod.makeRef(),
                    listOf(NullConstant.v())))
                body.units.add(invokeCallBack)
            }
        body.units.add(Jimple.v().newReturnVoidStmt())

    }

    /**
     * don't filter out lifecycle methods implemented in system classes
     */
    override fun searchAndBuildMethod(subsignature: String, currentClass: SootClass?, classLocal: Local?, parentClasses: Set<SootClass>): Stmt? {
        if (currentClass == null || classLocal == null) return null
        val method = findMethod(currentClass, subsignature)
        if (method == null) {
            logger.warn("Could not find Android entry point method: {}", subsignature)
            return null
        }
        // If the method is in one of the predefined Android classes, it cannot
        // contain custom code, so we do not need to call it
        // If the method is in one of the predefined Android classes, it cannot
        // contain custom code, so we do not need to call it
        if (AndroidEntryPointConstants.isLifecycleClass(method.declaringClass.name)) return null

        return buildMethodCall(method, classLocal, parentClasses)
    }
}
