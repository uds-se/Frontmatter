package saarland.cispa.frontmatter.resolvers

import mu.KLogging
import saarland.cispa.frontmatter.ActivityClass
import saarland.cispa.frontmatter.ActivityOrFragmentClass
import saarland.cispa.frontmatter.AppModel
import saarland.cispa.frontmatter.Resolver
import saarland.cispa.frontmatter.ResourceParser
import saarland.cispa.frontmatter.Utils.isSystemMethod
import saarland.cispa.frontmatter.Utils.isAndroidMethod
import saarland.cispa.frontmatter.Utils.isDummy
import saarland.cispa.frontmatter.XmlLayoutAnalysis
import saarland.cispa.frontmatter.data.ButtonType
import saarland.cispa.frontmatter.data.Dialog
import saarland.cispa.frontmatter.data.DialogButton
import saarland.cispa.frontmatter.data.MenuType
import saarland.cispa.frontmatter.data.StmtSite
import saarland.cispa.frontmatter.dialogBuilderClasses
import saarland.cispa.frontmatter.dialogBuilderSetButtonSubsignatures
import saarland.cispa.frontmatter.dialogBuilderSetIconSubsignatures
import saarland.cispa.frontmatter.dialogBuilderSetMessageSubsignatures
import saarland.cispa.frontmatter.dialogBuilderSetTitleSubsignatures
import soot.Scene
import soot.SootMethod
import soot.jimple.AssignStmt
import soot.jimple.NewExpr

/**
 * Determine mapping between layouts and activities
 * */
// TODO add support for DialogFragment

class DialogResolver(
    sceneV: Scene, private val appModel: AppModel,
    val valueResolver: ValueResolver,
    val resourceParser: ResourceParser,
    private val layoutAnalysis: XmlLayoutAnalysis
) : Resolver(sceneV) {
    companion object : KLogging()

    val activities = appModel.activities
    private val resourceResolver = ResourceResolver(valueResolver)
    private val stringResolver = StringResolver(valueResolver, resourceParser)

    fun resolveDialogsFrom(activity: ActivityOrFragmentClass, reachableMethods: Sequence<SootMethod>) {
        val dialogBuilderSites = reachableMethods
            .flatMap { finddialogBuilder(it).asSequence() }
            .toList()
        for (dialogBuilderSite in dialogBuilderSites) {
            require(dialogBuilderSite.stmt is AssignStmt)
            val scope = appModel.contextToScope[activity]
            val invokedMethods = valueResolver.resolveInvokedMethods(dialogBuilderSite.stmt.leftOp, dialogBuilderSite, scope)
            val titles = invokedMethods
                .filter { it.getInvokeExprSubsignature() in dialogBuilderSetTitleSubsignatures }
                .flatMap { stringResolver.resolveStringArg(it, 0, scope) }.toSet()
            val messages = invokedMethods
                .filter { it.getInvokeExprSubsignature() in dialogBuilderSetMessageSubsignatures }
                .flatMap { stringResolver.resolveStringArg(it, 0, scope) }.toSet()
            val icons = invokedMethods
                .filter { it.getInvokeExprSubsignature() in dialogBuilderSetIconSubsignatures }
                .flatMap { resourceResolver.resolveResourceId(it, scope, 0) }
                .mapNotNull { resourceParser.drawable[it.value] }.toSet()
            val buttons = invokedMethods
                .filter { it.getInvokeExprSubsignature() in dialogBuilderSetButtonSubsignatures }
                .map { getButton(it, scope) }
            val dialog = Dialog(titles, messages, buttons, icons)
            appModel.activityDialogs.put(activity, dialog)
        }
    }

    private fun getButton(setButtonsSite: StmtSite, scope: Set<String>): DialogButton {
        val buttonType = when (setButtonsSite.getInvokeExprSubsignature()) {
            "android.app.AlertDialog\$Builder setPositiveButton(int,android.content.DialogInterface\$OnClickListener)",
            "android.support.v7.app.AlertDialog\$Builder setPositiveButton(int,android.content.DialogInterface\$OnClickListener)",
            "android.app.AlertDialog\$Builder setPositiveButton(java.lang.CharSequence,android.content.DialogInterface\$OnClickListener)",
            "android.support.v7.app.AlertDialog\$Builder setPositiveButton(java.lang.CharSequence,android.content.DialogInterface\$OnClickListener)" -> {
                ButtonType.POSITIVE
            }
            "android.app.AlertDialog\$Builder setNegativeButton(int,android.content.DialogInterface\$OnClickListener)",
            "android.support.v7.app.AlertDialog\$Builder setNegativeButton(int,android.content.DialogInterface\$OnClickListener)",
            "android.app.AlertDialog\$Builder setNegativeButton(java.lang.CharSequence,android.content.DialogInterface\$OnClickListener)",
            "android.support.v7.app.AlertDialog\$Builder setNegativeButton(java.lang.CharSequence,android.content.DialogInterface\$OnClickListener)" -> {
                ButtonType.NEGATIVE
            }
            "android.app.AlertDialog\$Builder setNeutralButton(int,android.content.DialogInterface\$OnClickListener)",
            "android.support.v7.app.AlertDialog\$Builder setNeutralButton(int,android.content.DialogInterface\$OnClickListener)",
            "android.app.AlertDialog\$Builder setNeutralButton(java.lang.CharSequence,android.content.DialogInterface\$OnClickListener)",
            "android.support.v7.app.AlertDialog\$Builder setNeutralButton(java.lang.CharSequence,android.content.DialogInterface\$OnClickListener)" -> {
                ButtonType.NEUTRAL
            }
            else -> error("Unknown button type in $setButtonsSite")
        }
        val listeners = resourceResolver.resolveListener(setButtonsSite, scope, 1)
        val labels = stringResolver.resolveStringArg(setButtonsSite, 0, scope)
        return DialogButton(labels, buttonType, listeners)
    }

    private fun finddialogBuilder(method: SootMethod): List<StmtSite> {
        require(method.isConcrete)
        val activeBody = method.retrieveActiveBody()
        return activeBody.units.asSequence()
            .filterIsInstance<AssignStmt>()
            .filter { it.rightOp is NewExpr }
            .filter { (it.rightOp as NewExpr).baseType in dialogBuilderClasses }
            .map { StmtSite(it, method) }
            .toList()
    }

}
