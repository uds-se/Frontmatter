package saarland.cispa.frontmatter

import mu.KLogging
import saarland.cispa.frontmatter.Utils.isAndroidClass
import saarland.cispa.frontmatter.Utils.isInSystemPackage
import soot.Hierarchy
import soot.Scene
import soot.SootClass
import soot.SootMethod
import soot.jimple.infoflow.android.callbacks.AndroidCallbackDefinition
import soot.jimple.infoflow.android.callbacks.AndroidCallbackDefinition.CallbackType
import soot.util.HashMultiMap
import soot.util.MultiMap
import java.util.*

class CallbackAnalysis(private val sceneV: Scene, val xmlLayoutAnalysis: XmlLayoutAnalysis) {

    companion object : KLogging()

    val possibleCallbacks: MultiMap<SootClass, AndroidCallbackDefinition> = collectPossibleCallbacks()

    init {
        logger.info("Perform callback analysis")
    }

    /**
     * collect methods that can be callbacks from all app classes
     */
    private fun collectPossibleCallbacks(): MultiMap<SootClass, AndroidCallbackDefinition> {
        val results = HashMultiMap<SootClass, AndroidCallbackDefinition>()
        for (applicationClass in sceneV.applicationClasses) {
            // get Overridden methods
            val possibleCallbacks = analyzeClassForOverriddenCallbacks(applicationClass)
            results.putAll(applicationClass, possibleCallbacks)
            // get methods which class implements interfaces
            val possibleInterfaceCallbacks = analyzeClassInterfaceCallbacks(applicationClass)
            // val possibleInterfaceCallbacks_old = analyzeClassInterfaceCallbacks_old(applicationClass, androidCallbacks)
            possibleInterfaceCallbacks.forEach { results.put(getCallbackOwner(it), it) }
            //onClick callbacks from xml layouts
            val possibleXmlCallbacks = analyzeClassForXmlCallbacks(applicationClass)
            results.putAll(applicationClass, possibleXmlCallbacks)
        }
        return results
    }

    private fun getCallbackOwner(callback: AndroidCallbackDefinition): SootClass {
        // XXX: Assumption: inner class callbacks are assigned in outer class; not always the case - check!
        val declaringClass = callback.targetMethod.declaringClass
        return if (declaringClass.isInnerClass) {
            declaringClass.outerClass
        } else declaringClass
    }

    private fun canBeCallback(method: SootMethod): Boolean {
        //TODO: add criteria to filter out false positives
        return !method.isConstructor && !method.isStaticInitializer
    }

    /**
     * From Flowdroid
     * Gets whether the given callback interface or class represents a UI callback
     *
     * @param sootClass The callback interface or class to check
     * @return True if the given callback interface or class represents a UI
     * callback, otherwise false
     */
    private fun isUICallback(sootClass: SootClass): Boolean {
        val name = sootClass.name
        return name.startsWith("android.widget") || name.startsWith("android.view") || name.startsWith("android.content.DialogInterface$")
    }

    /**
     * Based on analyzeMethodOverrideCallbacks from Flowdroid
     * overridden method is considered as potential listener
     *
     * @param sootClass
     */
    private fun analyzeClassForOverriddenCallbacks(sootClass: SootClass): Set<AndroidCallbackDefinition> {
        val results = HashSet<AndroidCallbackDefinition>()
        if (!sootClass.isConcrete)
            return results
        if (sootClass.isInterface)
            return results
        // Do not start the search in system classes
        if (sootClass.isInSystemPackage() || sootClass.isAndroidClass())
            return results
        // There are also some classes that implement interesting callback methods.
        // We model this as follows: Whenever the user overwrites a method in an
        // Android OS class, we treat it as a potential callback.
        // collect all methods of system superclasses
        val systemMethods = sceneV.activeHierarchy.getSuperclassesOf(sootClass).asSequence()
            .filter { it.isAndroidClass() } // here we may miss some obfuscated classes
            .flatMap { it.methods.asSequence() }
            .filterNot { it.isConstructor }
            .map { it.subSignature to it }
            .toMap()
//        val systemMethods = HashMap<String, SootMethod>(10000)
//        for (parentClass in sceneV.activeHierarchy.getSuperclassesOf(sootClass)) {
//            if (parentClass.isAndroidClass()) {
//                for (sm in parentClass.methods)
//                    if (!sm.isConstructor) {
//                        systemMethods[sm.subSignature] = sm
//                    }
//            }
//        }
        // Iterate over all user-implemented methods. If they are inherited
        // from a system class, they are callback candidates.
        sootClass.methods.filter { it.subSignature in systemMethods }
            .filter { canBeCallback(it) }
            .forEach {
                val callbackType = if (isUICallback(it.declaringClass)) CallbackType.Widget else CallbackType.Default
                results.add(AndroidCallbackDefinition(it, systemMethods[it.subSignature], callbackType))
            }

        // do the same with custom superclasses
        sceneV.activeHierarchy.getSuperclassesOf(sootClass).asSequence()
            .filterNot { it.isAndroidClass() }
            .filterNot { it.isInSystemPackage() }
            .flatMap { it.methods.asSequence() }
            .filter { it.subSignature in systemMethods }
            .filter { canBeCallback(it) }
            .forEach { results.add(AndroidCallbackDefinition(it, systemMethods[it.subSignature], CallbackType.Default)) }
        return results
    }


    /**
     * From AbstractCallbackAnalyser
     *
     * @param sootClass lifecycleElement supposed to be the lifecycle class from which the listener is associated
     */
    private fun analyzeClassInterfaceCallbacks(sootClass: SootClass): Set<AndroidCallbackDefinition> {
        val results = HashSet<AndroidCallbackDefinition>()
        // Do not analyze system classes
        if (sootClass.isInSystemPackage() || sootClass.isAndroidClass())
            return results
        // If we are a class, one of our superclasses might implement an Android interface

        val activeHierarchy = sceneV.activeHierarchy
        // collect all Android interfaces
        val interfaceMethods = getInterfaces(sootClass, activeHierarchy)

        // interfaceClasses are all implemented interfaces, so we should call all methods from this class (and probably from superclasses)
        //  = sootClass.interfaces.flatMap { activeHierarchy.getSuperinterfacesOfIncluding(it) }
        // since we analyse all classes no need to go for superclasses here
        // it's not the case if we want to analyse a particular class!
        // List<SootClass> superClasses = activeHierarchy.getSuperclassesOfIncluding(sootClass);
        //
        val callbacks = sootClass.methods.filter { it.subSignature in interfaceMethods }
            .map { AndroidCallbackDefinition(it, interfaceMethods[it.subSignature], CallbackType.Default) }
            .toSet()
        return callbacks
    }

    /**
     * From AbstractCallbackAnalyser
     *
     * @param sootClass lifecycleElement supposed to be the lifecycle class from which the listener is associated
     */
    private fun analyzeClassInterfaceCallbacks_old(sootClass: SootClass, androidCallbacks: Set<String>): Set<AndroidCallbackDefinition> {
        val results = HashSet<AndroidCallbackDefinition>()
        // Do not analyze system classes
        if (sootClass.isInSystemPackage() || sootClass.isAndroidClass())
            return results
        val activeHierarchy = sceneV.activeHierarchy
        // collect all interfaces
        val interfaces = try {
            (if (sootClass.isInterface) activeHierarchy.getSuperinterfacesOfIncluding(sootClass) else activeHierarchy.getSuperclassesOfIncluding(sootClass))
                .flatMap { it.interfaces }
                .flatMap { sceneV.activeHierarchy.getSuperinterfacesOfIncluding(it) }
        } catch (e: Exception) {
            logger.error("Weird error in soot", e)
            emptyList<SootClass>()
        }
        //  = sootClass.interfaces.flatMap { activeHierarchy.getSuperinterfacesOfIncluding(it) }
        // since we analyse all classes no need to go for superclasses here
        // it's not the case if we want to analyse a particular class!
        // List<SootClass> superClasses = activeHierarchy.getSuperclassesOfIncluding(sootClass);
        val classMethods = sootClass.methods.map { it.subSignature to it }.toMap()

        for (implementedInterface in interfaces) {
            val interfaceMethods = implementedInterface.methods.map { it.subSignature to it }.toMap()
            if (androidCallbacks.contains(implementedInterface.name)) {
                val callbackType = if (isUICallback(implementedInterface)) CallbackType.Widget else CallbackType.Default
                val implementedMethods = HashSet(classMethods.keys)
                implementedMethods.retainAll(interfaceMethods.keys)
                for (implementedMethod in implementedMethods) {
                    results.add(AndroidCallbackDefinition(classMethods[implementedMethod], interfaceMethods[implementedMethod], callbackType))
                }
            }
        }
        return results
    }

    /**
     * get interfaces of Android classes
     */
    private fun getInterfaces(sootClass: SootClass, activeHierarchy: Hierarchy): Map<String, SootMethod> {
        try {
            if (sootClass.isInterface) { // get all superinterfaces of this interface
                return activeHierarchy.getSuperinterfacesOfIncluding(sootClass)
                    .filterNot { it.isInSystemPackage() }
                    .filter { it.isAndroidClass() }
                    .flatMap { it.methods }
                    .map { it.subSignature to it }
                    .toMap()
            }
            return activeHierarchy.getSuperclassesOfIncluding(sootClass)
                .asSequence()
                .flatMap { it.interfaces.asSequence() } // get interfaces of all superclasses of this class
                .flatMap { sceneV.activeHierarchy.getSuperinterfacesOfIncluding(it).asSequence() } // and all superinterfaces of these interfaces
                .filterNot { it.isInSystemPackage() }
                .filter { it.isAndroidClass() }
                .flatMap { it.methods.asSequence() }
                .map { it.subSignature to it }
                .toMap()
        } catch (e: Exception) {
            logger.error("Weird error in soot", e)
        }
        return emptyMap()
    }

    private fun analyzeClassForXmlCallbacks(applicationClass: SootClass): Set<AndroidCallbackDefinition> {
        val results = HashSet<AndroidCallbackDefinition>()
        // FIXME: now it's wrong
        val xmlListeners: Set<String> = Collections.unmodifiableSet(xmlLayoutAnalysis.callbackMethods.values())
        for (method in applicationClass.methods) {
            if (xmlListeners.contains(method.name)) { //XXX: should be class sensitive
                results.add(AndroidCallbackDefinition(method, method, CallbackType.Widget))

            }
        }
        return results
    }

    // /*
    //  * From Flowdroid
    //  * FIXME: refactor
    //  * Analyzes the given method and looks for callback registrations
    //  * Search for any invocations and check if its parameter is of callback class
    //  *
    //  * @param lifecycleElement The lifecycle element (activity, etc.) with which to associate the
    //  *                         found callbacks
    //  * @param method           The method in which to look for callbacks
    //  *
    // protected void analyzeMethodForCallbackRegistrations(SootClass lifecycleElement, SootMethod method, List<String> androidCallbacks) {
    // // Do not analyze system classes
    // if (SystemClassHandler.isClassInSystemPackage(method.getDeclaringClass().getName()))
    // return;
    // if (!method.isConcrete())
    // return;
    // // Iterate over all statement and find callback registration methods
    // Set<SootClass> callbackClasses = new HashSet<>();
    // for (Unit u : method.retrieveActiveBody().getUnits()) {
    // Stmt stmt = (Stmt) u;
    // // Callback registrations are always instance invoke expressions
    // if (stmt.containsInvokeExpr() && stmt.getInvokeExpr() instanceof InstanceInvokeExpr) {
    // InstanceInvokeExpr invokeExpr = (InstanceInvokeExpr) stmt.getInvokeExpr();
    // if (!SystemClassHandler.isClassInSystemPackage(invokeExpr.getMethod().getDeclaringClass().getName()))
    // continue;
    // SootMethodRef methodRef = invokeExpr.getMethodRef();
    // for (Value arg : invokeExpr.getArgs()) {
    // Type argType = arg.getType();
    // if (!(argType instanceof RefType)) {
    // continue;
    // }
    // String param = argType.toString();
    // if (androidCallbacks.contains(param)) {
    // }
    // }
    //
    // for (int i = 0; i < invokeExpr.getArgCount(); i++) {
    // final Type type = methodRef.parameterType(i);
    // if (!(type instanceof RefType))
    // continue;
    // String param = type.toString();
    // if (androidCallbacks.contains(param)) {
    // Value arg = invokeExpr.getArg(i);
    // // This call must be to a system API in order to
    // // register an OS-level callback
    // if (!SystemClassHandler.isClassInSystemPackage(invokeExpr.getMethod().getDeclaringClass().getName()))
    // continue;
    //
    // // We have a formal parameter type that corresponds to one of the Android
    // // callback interfaces. Look for definitions of the parameter to estimate the
    // // actual type.
    // if (arg instanceof Local) {
    // Set<Type> possibleTypes = sceneV.getPointsToAnalysis().reachingObjects((Local) arg).possibleTypes();
    // // If we don't have pointsTo information, we take the type of the local
    // if (possibleTypes.isEmpty()) {
    // possibleTypes.add(arg.getType());
    // }
    // for (Type possibleType : possibleTypes) {
    // RefType baseType;
    // if (possibleType instanceof RefType)
    // baseType = (RefType) possibleType;
    // else if (possibleType instanceof AnySubType)
    // baseType = ((AnySubType) possibleType).getBase();
    // else {
    // logger.warn("Unsupported type detected in callback analysis");
    // continue;
    // }
    // SootClass targetClass = baseType.getSootClass();
    // if (!SystemClassHandler.isClassInSystemPackage(targetClass.getName()))
    // callbackClasses.add(targetClass);
    // }
    // }
    // }
    // }
    // }
    // }
    // // Analyze all found callback classes
    // // for (SootClass callbackClass : callbackClasses)
    // //     analyzeClassInterfaceCallbacks(callbackClass, callbackClass, lifecycleElement);
    // }
    //  */
}
