package saarland.cispa.frontmatter

import mu.KLogging
import org.assertj.core.api.Assertions
import org.junit.jupiter.api.Test
import saarland.cispa.frontmatter.Utils.setBoomerangTimeout
import saarland.cispa.frontmatter.analyses.AppPlatform
import saarland.cispa.frontmatter.analyses.FrontmatterMetadata
import saarland.cispa.frontmatter.cg.CallgraphAssembler
import saarland.cispa.frontmatter.cg.InterproceduralCFGWrapper
import saarland.cispa.frontmatter.data.StmtSite
import saarland.cispa.frontmatter.resolvers.StringResolver
import saarland.cispa.frontmatter.resolvers.ValueResolver
import saarland.cispa.frontmatter.resolvers.ViewResolver
import saarland.cispa.frontmatter.results.ResultsHandler
import soot.Scene
import soot.SootMethod
import soot.Value
import soot.jimple.AssignStmt
import soot.jimple.DefinitionStmt
import soot.jimple.InstanceFieldRef
import soot.jimple.Jimple
import soot.jimple.StaticFieldRef
import soot.jimple.Stmt
import soot.jimple.infoflow.android.manifest.ProcessManifest
import java.nio.file.Paths

class ValueResolverTest {

    private var androidJar = Paths.get("/Users/kuznetsov/work/android/sdk/platforms/")
    private val meta = FrontmatterMetadata("", "", 0, 0, "", AppPlatform.NORMAL, "")

    companion object : KLogging()


    @Test
    fun stringReqursionTest() {
        val methodSig = "<b.b.b.l: void f(android.widget.TextView)>"
        val stmtSig = "\$r4 = virtualinvoke \$r29.<java.lang.StringBuilder: java.lang.String toString()>()"
        setBoomerangTimeout(60)
        val apkFile = Paths.get("/Users/kuznetsov/work/workspace/backstage_project/issues/apks/com.nmbs--59.apk")
        val manifest = ProcessManifest(apkFile.toFile())
        val sceneV = Utils.initializeSoot(androidJar, apkFile, manifest.packageName)
        // shrinkFields(stmtSite.method)
        val apkInfo = ApkInfo(sceneV, apkFile, manifest)
        val layoutAnalysis = XmlLayoutAnalysis(sceneV, apkInfo)
        val callbackAnalysis = CallbackAnalysis(sceneV, layoutAnalysis)
        val callgraphAssembler = CallgraphAssembler(sceneV, callbackAnalysis.possibleCallbacks, apkInfo)//, InfoflowConfiguration.CallgraphAlgorithm.GEOM)
        val icfg = InterproceduralCFGWrapper(sceneV, callgraphAssembler.callgraph)
        val valueResolver = ValueResolver(sceneV, icfg)
        val stmtSite = getStmtSite(methodSig, stmtSig)
        val stringResolver = StringResolver(valueResolver, apkInfo.resourceParser)
        val invMethods = stringResolver.resolveAssignedString(stmtSite)
        print(invMethods)
    }

    @Test
    fun forwardResolveTest() {
//        val methodSig = "<com.nmbs.activities.MainActivity: void J()>"
//        val stmtSig = "\$r2 = new androidx.viewpager.widget.ViewPager"
//        val methodSig = "<com.nmbs.activities.StationInfoMapActivity: void B()>"
//        val stmtSig = "\$r4 = new android.widget.ListView"
        val methodSig = "<com.nmbs.activities.ScheduleResultActivity: void z()>"
        val stmtSig = "\$r14 = new android.widget.LinearLayout"
        setBoomerangTimeout(60)
        val apkFile = Paths.get("/Users/kuznetsov/work/workspace/backstage_project/issues/apks/com.nmbs--59.apk")
        val manifest = ProcessManifest(apkFile.toFile())
        val sceneV = Utils.initializeSoot(androidJar, apkFile, manifest.packageName)
        // shrinkFields(stmtSite.method)
        val apkInfo = ApkInfo(sceneV, apkFile, manifest)
        val layoutAnalysis = XmlLayoutAnalysis(sceneV, apkInfo)
        val callbackAnalysis = CallbackAnalysis(sceneV, layoutAnalysis)
        val callgraphAssembler = CallgraphAssembler(sceneV, callbackAnalysis.possibleCallbacks, apkInfo)//, InfoflowConfiguration.CallgraphAlgorithm.GEOM)
        val icfg = InterproceduralCFGWrapper(sceneV, callgraphAssembler.callgraph)
        val valueResolver = ValueResolver(sceneV, icfg)
        val stmtSite = getStmtSite(methodSig, stmtSig)
        val invMethods = valueResolver.resolveInvokedMethods((stmtSite.stmt as DefinitionStmt).leftOp, stmtSite, null)
        print(invMethods)
    }

    @Test
    fun listViewTest() {
        setBoomerangTimeout(60)
        val apkFile = Paths.get("/Users/kuznetsov/work/workspace/backstage_project/issues/apks/com.nmbs--59.apk")
        val manifest = ProcessManifest(apkFile.toFile())
        val sceneV = Utils.initializeSoot(androidJar, apkFile, manifest.packageName)
        // shrinkFields(stmtSite.method)
        val apkInfo = ApkInfo(sceneV, apkFile, manifest)
        val layoutAnalysis = XmlLayoutAnalysis(sceneV, apkInfo)
        val callbackAnalysis = CallbackAnalysis(sceneV, layoutAnalysis)
        val callgraphAssembler = CallgraphAssembler(sceneV, callbackAnalysis.possibleCallbacks, apkInfo)//, InfoflowConfiguration.CallgraphAlgorithm.GEOM)
        val icfg = InterproceduralCFGWrapper(sceneV, callgraphAssembler.callgraph)
        val valueResolver = ValueResolver(sceneV, icfg)
        val appModel = AppModel(apkInfo.declaredActivities, meta)
        val activity = sceneV.getSootClass("com.nmbs.activities.MessageActivity")
        val layoutMapper = ActivityLayoutResolver(sceneV, appModel, valueResolver, apkInfo.resourceParser, layoutAnalysis)
//        val uiElements = layoutMapper.resolveLayoutMapping(sceneV.getSootClass("com.nmbs.activities.MessageActivity"))
//        appModel.activityLayouts.putAll(activity, uiElements)
        val viewResolver = ViewResolver(activity, appModel, sceneV, valueResolver, apkInfo.resourceParser, layoutAnalysis)
//        val methodSig = "<com.nmbs.activities.MessageActivity: void w(java.util.List,java.lang.String,int)>"
//        val stmtSig = "\$r6 = virtualinvoke \$r5.<android.view.View: android.view.View findViewById(int)>(varReplacer8573)"
        val methodSig = "<com.nmbs.activities.MessageActivity: void x()>"
        val stmtSig = "\$r1 = virtualinvoke r0.<android.app.Activity: android.view.View findViewById(int)>"
//        $r6 = virtualinvoke $r5.<android.view.View: android.view.View findViewById(int)>(varReplacer8573);
//        $r7 = new com.nmbs.activity.LinearLayoutForListView;
        val stmtSite = getStmtSite(methodSig, stmtSig)
        viewResolver.resolveView(stmtSite, null)
        ResultsHandler.saveUIModel(appModel, Paths.get("/Users/kuznetsov/work/workspace/backstage_project/backstage-revived/data_v2/com.nmbs_part.json"))
        print("")
    }

    @Test
    fun v1Test() {
        setBoomerangTimeout(60)
        val apkFile = Paths.get("/Users/kuznetsov/work/workspace/gator/gator-3.8/AndroidBench/APKs/CGO14ASE15/vlc.apk")
        val manifest = ProcessManifest(apkFile.toFile())
        val sceneV = Utils.initializeSoot(androidJar, apkFile, manifest.packageName)
        val stmtSig = "virtualinvoke \$r21.<android.widget.ImageButton: void setOnClickListener(android.view.View\$OnClickListener)>(\$r22)"
        val methodSig = "<org.videolan.vlc.widget.AudioMiniPlayer: android.view.View onCreateView(android.view.LayoutInflater,android.view.ViewGroup,android.os.Bundle)>"
        val stmtSite = getStmtSite(methodSig, stmtSig)
        shrinkFields(stmtSite.method)
        val apkInfo = ApkInfo(sceneV, apkFile, manifest)
        val layoutAnalysis = XmlLayoutAnalysis(sceneV, apkInfo)
        val callbackAnalysis = CallbackAnalysis(sceneV, layoutAnalysis)
        val callgraphAssembler = CallgraphAssembler(sceneV, callbackAnalysis.possibleCallbacks, apkInfo)//, InfoflowConfiguration.CallgraphAlgorithm.GEOM)
        val icfg = InterproceduralCFGWrapper(sceneV, callgraphAssembler.callgraph)
        val valueResolver = ValueResolver(sceneV, icfg)
        logger.warn("${stmtSite.method.activeBody}")
//        val v = valueResolver.resolveVar((stmtSite.stmt as AssignStmt).leftOp, stmtSite)
        val v = valueResolver.resolveArg(stmtSite, 0, null)
        Assertions.assertThat(v).isNotEmpty

    }

    @Test
    fun staticFieldTest() {
        setBoomerangTimeout(60)
        val apkFile = Paths.get("/Users/kuznetsov/work/workspace/backstage_project/issues/apks/info.metadude.android.bitsundbaeume.schedule_51-instrumented.apk")
        val manifest = ProcessManifest(apkFile.toFile())
        val sceneV = Utils.initializeSoot(androidJar, apkFile, manifest.packageName)
        val stmtSite = getStaticStmtSite()
        val apkInfo = ApkInfo(sceneV, apkFile, manifest)
        val layoutAnalysis = XmlLayoutAnalysis(sceneV, apkInfo)
        val callbackAnalysis = CallbackAnalysis(sceneV, layoutAnalysis)
        val callgraphAssembler = CallgraphAssembler(sceneV, callbackAnalysis.possibleCallbacks, apkInfo)//, InfoflowConfiguration.CallgraphAlgorithm.GEOM)
        val icfg = InterproceduralCFGWrapper(sceneV, callgraphAssembler.callgraph)
        val valueResolver = ValueResolver(sceneV, icfg)
        logger.warn("${stmtSite.method.activeBody}")
//        val v = valueResolver.resolveVar((stmtSite.stmt as AssignStmt).leftOp, stmtSite)
        val m = Scene.v().getMethod("<nerd.tuxmobil.fahrplan.congress.reporting.TraceDroidEmailSender\$\$Lambda\$2: void <clinit>()>")
        val variable = (m.activeBody.units.getPredOf(stmtSite.stmt) as AssignStmt).leftOp
        val v = valueResolver.resolveVar(variable, stmtSite, null)
        Assertions.assertThat(v).isNotEmpty
    }

    private fun getStaticStmtSite(): StmtSite {
        val m = Scene.v().getMethod("<nerd.tuxmobil.fahrplan.congress.reporting.TraceDroidEmailSender\$\$Lambda\$2: void <clinit>()>")

        val stmt = m.activeBody.units.last as Stmt
        return StmtSite(stmt, m)

    }

    private fun getStmtSite(method_sig: String, stmt_sig: String): StmtSite {
        val m = Scene.v().getMethod(method_sig)
        val stmt = m.activeBody.units.first {
            it.toString()
                .startsWith(stmt_sig)
        } as Stmt
        return StmtSite(stmt, m)

    }

    private fun getStmtSite2(): StmtSite {
        val m = Scene.v().getMethod("<com.slidingmenu.lib.SlidingMenu: void <init>(android.content.Context,android.util.AttributeSet,int)>")
        val stmt = m.activeBody.units.first {
            it.toString()
                .startsWith("virtualinvoke \$r0.<com.slidingmenu.lib.SlidingMenu: void addView(android.view.View,android.view.ViewGroup\$LayoutParams)>(\$r9, \$r3)")
        } as Stmt
        return StmtSite(stmt, m)

    }

    private fun shrinkFields(method: SootMethod) {
        val body = method.activeBody
        var prevUnit = body.units.first
        for (unit in body.units.snapshotIterator()) {
            if (unit is AssignStmt && prevUnit is AssignStmt) {
                if (isEqual(unit.rightOp, prevUnit.leftOp)) {
                    val substAssignStmt = Jimple.v().newAssignStmt(unit.leftOp, prevUnit.rightOp)
                    body.units.insertBefore(substAssignStmt, unit)
                    body.units.remove(unit)
                }
            }
            prevUnit = unit
        }
    }

    private fun isEqual(rightOp: Value, leftOp: Value): Boolean {
        if (rightOp is StaticFieldRef && leftOp is StaticFieldRef)
            return (rightOp.fieldRef == leftOp.fieldRef)
        if (rightOp is InstanceFieldRef && leftOp is InstanceFieldRef)
            return (rightOp.fieldRef == leftOp.fieldRef) && (rightOp.base == rightOp.base)
        return false
    }

}
