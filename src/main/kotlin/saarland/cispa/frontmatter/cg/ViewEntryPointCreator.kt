package saarland.cispa.frontmatter.cg

import soot.Local
import soot.RefType
import soot.Scene
import soot.SootClass
import soot.SootField
import soot.SootMethod
import soot.VoidType
import soot.jimple.Jimple
import soot.jimple.NullConstant
import soot.jimple.infoflow.android.entryPointCreators.AbstractAndroidEntryPointCreator
import soot.jimple.infoflow.android.manifest.ProcessManifest
import soot.util.HashMultiMap
import soot.util.MultiMap
import java.lang.reflect.Modifier
import java.util.HashSet

class ViewEntryPointCreator(private val component: SootClass, private val callbacks: Collection<SootMethod>, manifest: ProcessManifest) : AbstractAndroidEntryPointCreator(manifest) {
    init {
        val classes = when {
            component.isInterface -> Scene.v().activeHierarchy.getImplementersOf(component)
            component.isAbstract -> Scene.v().activeHierarchy.getSubclassesOf(component)
            else -> null
        }
        classes?.let {
            val classNames = it.map { c -> c.name }
            this.setSubstituteClasses(classNames)
            this.setSubstituteCallParams(true)
        }
    }

    override fun createEmptyMainMethod() {
        // Generate a method name
        var componentPart = component.name
        if (componentPart.contains("."))
            componentPart = componentPart.replace("_", "__").replace(".", "_")
        val baseMethodName = dummyMethodName + "_" + componentPart

        // Get the target method
        var methodIndex = 0
        var methodName = baseMethodName
        val mainClass = Scene.v().getSootClass(dummyClassName)
        if (!overwriteDummyMainMethod)
            while (mainClass.declaresMethodByName(methodName))
                methodName = baseMethodName + "_" + methodIndex++

        // Remove the existing main method if necessary. Do not clear the
        // existing one, this would take much too long.
        mainMethod = mainClass.getMethodByNameUnsafe(methodName)
        if (mainMethod != null) {
            mainClass.removeMethod(mainMethod)
            mainMethod = null
        }

        // Create the method
//        val defaultParams = getDefaultMainMethodParams()
//        val additionalParams = getAdditionalMainMethodParams()
        val argList = listOf(VoidType.v())//ArrayList<Type>(defaultParams)
        mainMethod = Scene.v().makeSootMethod(methodName, argList, component.type)

        // Create the body
        val body = Jimple.v().newBody()
        body.method = mainMethod
        mainMethod.activeBody = body

        // Add the method to the class
        mainClass.addMethod(mainMethod)

        // First add class to scene, then make it an application class
        // as addClass contains a call to "setLibraryClass"
        mainClass.setApplicationClass()
        mainMethod.modifiers = Modifier.PUBLIC or Modifier.STATIC

        // Add the identity statements to the body. This must be done after the
        // method has been properly declared.
        body.insertIdentityStmts()

        for (i in argList.indices) {
            val lc = body.getParameterLocal(i)
            if (lc.type is RefType) {
                val rt = lc.type as RefType
                localVarsForClasses[rt.sootClass] = lc
            }
        }
    }

    override fun createDummyMainInternal(): SootMethod {
        val thisLocal = generateClassConstructor(component)
        localVarsForClasses[component] = thisLocal //local of this type

        addCallbackMethods()
        if (thisLocal == null)
            body.units.add(Jimple.v().newReturnStmt(NullConstant.v()))
        else
            body.units.add(Jimple.v().newReturnStmt(thisLocal))
        return mainMethod
    }

    override fun getRequiredClasses(): Collection<String> {
        return emptySet() //XXX: check if it's enough
    }

    override fun getAdditionalMethods(): Collection<SootMethod> {
        return emptySet()
    }

    override fun getAdditionalFields(): Collection<SootField> {
        return emptySet()
    }

    /**
     * Gets all callback methods registered for the given class
     * it may also return inner classes
     *
     * @return The callback methods registered for the given class
     */
    private fun getCallbackMethods(): MultiMap<SootClass, SootMethod> {
        val callbackClasses = HashMultiMap<SootClass, SootMethod>()
        callbacks.forEach { callbackClasses.put(it.declaringClass, it) }
        return callbackClasses
    }

    private fun addCallbackMethods() {
        // Get all classes in which callback methods are declared
        val callbackClasses = getCallbackMethods()
        val referenceClasses = setOf(component)
//        val processedClasses = HashMap<SootClass, SootMethod>()
        for (callbackClass in callbackClasses.keySet()) {
            val callbackMethods = callbackClasses.get(callbackClass)
            if (!callbackClass.isConcrete) {
                logger.warn("Class $callbackClass is either interface or an abstract class and cannot be added as an entrypoint")
                continue
            }

            val classLocal: Local? = localVarsForClasses[callbackClass] ?: run {
                val tempLocals = HashSet<Local>()
                generateClassConstructor(callbackClass, mutableSetOf(),
                    referenceClasses, tempLocals)
            }
            for (callbackMethod in callbackMethods) {
                buildMethodCall(callbackMethod, classLocal)  //XXX: check if we need to pass referenceClasses
            }
        }

    }

}
