package saarland.cispa.frontmatter

import boomerang.DefaultBoomerangOptions
import boomerang.callgraph.ObservableICFG
import boomerang.jimple.AllocVal
import boomerang.jimple.Statement
import boomerang.jimple.Val
import boomerang.solver.AbstractBoomerangSolver
import com.google.common.base.Optional
import saarland.cispa.frontmatter.Utils.getBoomerangTimeout
import saarland.cispa.frontmatter.Utils.isActivity
import saarland.cispa.frontmatter.Utils.isAndroidMethod
import saarland.cispa.frontmatter.Utils.isContext
import saarland.cispa.frontmatter.Utils.isDummyMain
import saarland.cispa.frontmatter.Utils.isDummy
import saarland.cispa.frontmatter.Utils.isFromLib
import saarland.cispa.frontmatter.Utils.isInternalJavaMethod
import soot.Modifier
import soot.PrimType
import soot.RefType
import soot.SootMethod
import soot.Type
import soot.Unit
import soot.Value
import soot.jimple.AssignStmt
import soot.jimple.ClassConstant
import soot.jimple.InstanceInvokeExpr
import soot.jimple.IntConstant
import soot.jimple.StaticFieldRef
import soot.jimple.Stmt
import soot.toolkits.scalar.Pair

class FrontmatterBoomerangOptions : DefaultBoomerangOptions() {

    private fun isTargetType(type: Type): Boolean {
        if (type is PrimType)
            return true
        if (type == RefType.v("java.lang.String"))
            return true
        return false
    }

    override fun isAllocationVal(value: Value): Boolean {
        if (value is ClassConstant) {
            return true
        }
        if (value is IntConstant) {
            return true
        }
        if (value is StaticFieldRef) {
            if (Modifier.isFinal(value.field.modifiers))// && isTargetType(value.type))
                return true
        }
        return super.isAllocationVal(value)
    }

    /***
     * define target allocation sites to be searched
     */
    override fun getAllocationVal(
        m: SootMethod, stmt: Stmt, fact: Val,
        icfg: ObservableICFG<Unit, SootMethod>
    ): Optional<AllocVal> {
        if (stmt.containsInvokeExpr()) {
            val callee = stmt.invokeExpr.method
            // signatures
            when (callee.signature) {
                //* assignment statements
                //* stringManipulationMethods
                stringBuilderToString,
                in prefStringSignatures,
                -> {
                    if (stmt !is AssignStmt) {
                        return Optional.absent()
                    }
                    if (fact.value() == stmt.leftOp)
                        return Optional.of(AllocVal(stmt.leftOp, m, stmt.leftOp, Statement(stmt, m)))
                }
            }
            // subsignatures
            when (callee.subSignature) {
                // assignment statements
                in getListViewSubsignature,
                in getFragmentManager,
                in fragmentGetActivity,
                in inflatorSubsignatures -> {
                    if (stmt !is AssignStmt) {
                        return Optional.absent()
                    }
                    if (fact.value() == stmt.leftOp)
                        return Optional.of(AllocVal(stmt.leftOp, m, stmt.leftOp, Statement(stmt, m)))
                }
            }
        }
        return super.getAllocationVal(m, stmt, fact, icfg)
    }

    override fun typeCheck(): Boolean {
        return false
    }

    override fun onTheFlyCallGraph(): Boolean {
        return false // Must be turned off if no SeedFactory is specified.
    }

    override fun throwFlows(): Boolean {
        return true
    }

    /**
     * We ignore all system android methods and instead of applying callIntoFlow boomerang uses normalFlow jumping over the statement
     *
     * */
    override fun isIgnoredMethod(method: SootMethod): Boolean {
        return method.isAndroidMethod() || method.isInternalJavaMethod() || super.isIgnoredMethod(method) || method.signature == "<java.lang.Object: void <init>()>" || method.isFromLib() || method.isDummyMain()
    }

    override fun trackReturnOfInstanceOf(): Boolean {
        return false
    }

    override fun trackStrings(): Boolean {
        return true
    }

    override fun trackNullAssignments(): Boolean {
        return true
    }

    override fun trackStaticFieldAtEntryPointToClinit(): Boolean {
        return true
    }

    override fun analysisTimeoutMS() = getBoomerangTimeout() * 1000

    override fun passTroughCall(curr: Stmt, fact: Val, solver: AbstractBoomerangSolver<*>): List<Pair<Value, Value>> {
        if (!curr.containsInvokeExpr()) {
            return super.passTroughCall(curr, fact, solver)
        }
        val invokeExpr = curr.invokeExpr
        if (curr is AssignStmt) {
            when (invokeExpr.method.subSignature) {
                in beginFragmentTransaction -> {
                    val leftVal = curr.leftOp
                    val rightVal = (invokeExpr as InstanceInvokeExpr).base
                    return listOf(Pair(leftVal, rightVal))
                }
                in resourcesActivityGetStringSubsignatures -> {
                    if (invokeExpr.method.declaringClass.isActivity() || invokeExpr.method.declaringClass.isContext()) {
                        val leftVal = curr.leftOp
                        val rightVal = invokeExpr.getArg(0)
                        return listOf(Pair(leftVal, rightVal))
                    }
                }
                in dialogBuilderSetTitleSubsignatures,
                in dialogBuilderSetMessageSubsignatures,
                in dialogBuilderSetButtonSubsignatures,
                in dialogBuilderSetIconSubsignatures,
                in dialogBuilderSetViewSubsignatures,
                in dialogBuilderOtherSubsignatures -> {
                    val leftVal = curr.leftOp
                    val rightVal = (invokeExpr as InstanceInvokeExpr).base
                    return listOf(Pair(leftVal, rightVal))

                }
            }
            when (invokeExpr.method.signature) {
                in javaClassMethodSignatures -> {
                    val leftVal = curr.leftOp
                    val rightVal = if (invokeExpr.argCount == 0) (invokeExpr as InstanceInvokeExpr).base else invokeExpr.getArg(0)
                    return listOf(Pair(leftVal, rightVal))

                }
                in resourcesGetStringSignatures,
                in resourcesGetDrawableSignatures -> {
                    val leftVal = curr.leftOp
                    val rightVal = invokeExpr.getArg(0)
                    return listOf(Pair(leftVal, rightVal))
                }
                in stringFormatSignatures -> {
                    val leftVal = curr.leftOp
                    //TODO resolve Object[] values
                    val rightVal = when (invokeExpr.method.signature) {
                        "<java.lang.String: java.lang.String format(java.lang.String,java.lang.Object[])>" -> invokeExpr.getArg(0)
                        "<java.lang.String: java.lang.String format(java.util.Locale,java.lang.String,java.lang.Object[])>" -> invokeExpr.getArg(1)
                        else -> error("Unknown signature")
                    }
                    return listOf(Pair(leftVal, rightVal))
                }
                in stringManipulationAssignSignatures,
                in stringManipulationInvokeSignatures -> {
                    val leftVal = curr.leftOp
                    val rightVal = (invokeExpr as InstanceInvokeExpr).base
                    return listOf(Pair(leftVal, rightVal))
                }
                in stringBuilderSignatures -> {
                    val res = mutableListOf<Pair<Value, Value>>()
                    val leftVal = curr.leftOp
                    val rightVal = invokeExpr.getArg(0)
                    val rightVal2 = (invokeExpr as InstanceInvokeExpr).base
                    res.add(Pair(leftVal, rightVal))
                    res.add(Pair(leftVal, rightVal2))
                    return res
                }
                in stringBuilderSkipSignatures -> {
                    val leftVal = curr.leftOp
                    val rightVal = (invokeExpr as InstanceInvokeExpr).base
                    return listOf(Pair(leftVal, rightVal))
                }
                in androidHtmlSignatures -> {
                    val leftVal = curr.leftOp
                    val rightVal = invokeExpr.getArg(0)
                    return listOf(Pair(leftVal, rightVal))
                }
                in uriMethodSignatures -> {
                    val leftVal = curr.leftOp
                    return invokeExpr.args.map { Pair(leftVal, it) }
                }
                in setOf("<java.util.List: java.lang.Object get(int)>") -> {
                    val leftVal = curr.leftOp
                    val rightVal = (invokeExpr as InstanceInvokeExpr).base
                    return listOf(Pair(leftVal, rightVal))
                }
                in setOf("<java.lang.Integer: int intValue()>") -> {
                    val leftVal = curr.leftOp
                    val rightVal = (invokeExpr as InstanceInvokeExpr).base
                    return listOf(Pair(leftVal, rightVal))
                }
                in setOf("<java.lang.Integer: java.lang.Integer valueOf(int)>") -> {
                    val leftVal = curr.leftOp
                    val rightVal = invokeExpr.getArg(0)
                    return listOf(Pair(leftVal, rightVal))
                }
            }
        }

        when (invokeExpr.method.signature) {
            in componentNameConstructors -> {//FIXME: support relative class names
                val leftVal = (invokeExpr as InstanceInvokeExpr).base
                val rightVal = invokeExpr.getArg(1)
                return listOf(Pair(leftVal, rightVal))
            }
            in intentClassSetters -> {
                val leftVal = if (curr is AssignStmt) curr.leftOp else (invokeExpr as InstanceInvokeExpr).base
                val rightVal = when (invokeExpr.method.subSignature) {
                    "android.content.Intent setClass(android.content.Context,java.lang.Class)",
                    "android.content.Intent setClassName(android.content.Context,java.lang.String)",
                    "android.content.Intent setClassName(java.lang.String,java.lang.String)" -> invokeExpr.getArg(1)
                    "android.content.Intent setComponent(android.content.ComponentName)",
                    "android.content.Intent setAction(java.lang.String)" -> invokeExpr.getArg(0)
                    else -> error("Unknown signature")
                }
                return listOf(Pair(leftVal, rightVal))
            }
            in intentConstructors -> {
                val leftVal = (invokeExpr as InstanceInvokeExpr).base
                val rightVal = when (invokeExpr.method.signature) {
                    "<android.content.Intent: void <init>(android.content.Intent)>",
                    "<android.content.Intent: void <init>(java.lang.String)>" -> invokeExpr.getArg(0)
                    "<android.content.Intent: void <init>(android.content.Context,java.lang.Class)>",
                    "<android.content.Intent: void <init>(java.lang.String,android.net.Uri)>" -> invokeExpr.getArg(1)
                    "<android.content.Intent: void <init>(java.lang.String,android.net.Uri,android.content.Context,java.lang.Class)>" -> invokeExpr.getArg(3)
                    else -> error("Unknown signature")
                }
                return listOf(Pair(leftVal, rightVal))
            }
            in intentFilterConstructors -> {
                val leftVal = (invokeExpr as InstanceInvokeExpr).base
                val rightVal = invokeExpr.getArg(0)
                return listOf(Pair(leftVal, rightVal))
            }
            in intentFilterSetters -> {
                val leftVal = (invokeExpr as InstanceInvokeExpr).base
                val rightVal = invokeExpr.getArg(0)
                return listOf(Pair(leftVal, rightVal))
            }

            in stringBuilderSignatures,
            in listAddSignatures -> {
                val res = mutableListOf<Pair<Value, Value>>()
                val leftVal = (invokeExpr as InstanceInvokeExpr).base
                val rightVal = invokeExpr.getArg(0)
                res.add(Pair(leftVal, leftVal))
                res.add(Pair(leftVal, rightVal))
                return res
            }
        }

        return super.passTroughCall(curr, fact, solver)
    }
}
