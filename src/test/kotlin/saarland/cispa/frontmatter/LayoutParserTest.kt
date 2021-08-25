package saarland.cispa.frontmatter


import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import saarland.cispa.frontmatter.analyses.AppPlatform
import saarland.cispa.frontmatter.analyses.FrontmatterMetadata
import saarland.cispa.frontmatter.cg.CallgraphAssembler
import saarland.cispa.frontmatter.cg.InterproceduralCFGWrapper
import saarland.cispa.frontmatter.data.ActivityOrigin
import saarland.cispa.frontmatter.resolvers.TransitionResolver
import saarland.cispa.frontmatter.resolvers.UIView
import saarland.cispa.frontmatter.resolvers.ValueResolver
import saarland.cispa.frontmatter.results.ResultsHandler
import soot.Scene
import soot.jimple.infoflow.android.manifest.ProcessManifest
import java.nio.file.Path
import java.nio.file.Paths


class LayoutParserTest {
    //    private var apkFile: String = "/Users/kuznetsov/work/workspace/reactive-system-anomalies/apks/train/com.newnycway.apk"
    private var apkFile: Path = Paths.get(LayoutParserTest::class.java.classLoader.getResource("app-debug.apk").file)
    private val androidJar: Path = Paths.get("/Users/kuznetsov/work/android/sdk/platforms")//System.getProperty("android_jar")
    private lateinit var sceneV: Scene
    private lateinit var apkInfo: ApkInfo
    private lateinit var pkg: String
    private lateinit var resParser: ResourceParser
    private val meta = FrontmatterMetadata("", "", 0, 0, "", AppPlatform.NORMAL, "")
    // String targetAPK = "testApps/fragementapp.apk";
    // apkFile = "/Volumes/Internal/workspace/reactive-system-anomalies/apks/train/au.gov.vic.ptv.apk";
    // apkFile = "/Volumes/Internal/workspace/reactive-system-anomalies/apks/train/com.thales.android.eastcoast.apk";
    // apkFile = "/Volumes/Internal/workspace/reactive-system-anomalies/apks/train/com.newnycway.apk"
    // apkFile = "/Volumes/Internal/workspace/reactive-system-anomalies/apks/train/com.wanderu.wanderu.apk";
    // apkFile = LayoutParserTest.class.getClassLoader().getResource("app-debug.apk").getFile();
    // apkFile = "/Users/kuznetsov/LAB/workspace/apk_samples/uni-app-android/android-app/android-app-release.apk";

    @BeforeEach
    fun init() {
        val manifest = ProcessManifest(apkFile.toFile())
        sceneV = Utils.initializeSoot(androidJar, apkFile, manifest.packageName)
        apkInfo = ApkInfo(sceneV, apkFile, manifest)
        pkg = apkFile.fileName.toString()
    }

    @Test
    fun callGraphSizeTest() {
        val layoutAnalysis = XmlLayoutAnalysis(sceneV, apkInfo)
        val callbackAnalysis = CallbackAnalysis(sceneV, layoutAnalysis)
        val callgraphAssembler = CallgraphAssembler(sceneV, callbackAnalysis.possibleCallbacks, apkInfo)
        val callGraph = callgraphAssembler.callgraph
        assertThat(callGraph).hasSize(1465)
    }

    @Test
    fun setContentViewTest() {
        val expectedActivities = mapOf(
            "com.example.avdiienko.button_xml.ActivityClassImplementsListenerInterface" to 2130968601,
            "com.example.avdiienko.button_xml.AnonymousOnClickHandler" to 2130968602,
            "com.example.avdiienko.button_xml.BaseActivity" to 2130968603,
            "com.example.avdiienko.button_xml.ButtonWithListenerInMethod" to 2130968604,
            "com.example.avdiienko.button_xml.IdInArray" to 2130968605,
            "com.example.avdiienko.button_xml.InterfaceType" to 2130968606,
            "com.example.avdiienko.button_xml.MainActivity" to 2130968607,
            "com.example.avdiienko.button_xml.MemberClass" to 2130968608,
            "com.example.avdiienko.button_xml.OneListenerMultipleButtons" to 2130968609
        )
        val layoutAnalysis = XmlLayoutAnalysis(sceneV, apkInfo)
        val callbackAnalysis = CallbackAnalysis(sceneV, layoutAnalysis)
        val callgraphAssembler = CallgraphAssembler(sceneV, callbackAnalysis.possibleCallbacks, apkInfo)
        val icfg = InterproceduralCFGWrapper(sceneV, callgraphAssembler.callgraph)
        val valueResolver = ValueResolver(sceneV, icfg)
//        val inflateResolver = InflateResolver(sceneV, icfg, valueResolver, viewResolver)
        val appModel = AppModel(apkInfo.declaredActivities, meta)
        val lm = ActivityLayoutResolver(sceneV, appModel, valueResolver, apkInfo.resourceParser, layoutAnalysis)
        val layoutMapping = lm.resolve()
        val expectedActivityNames = expectedActivities.keys
        val actualActivityNames = layoutMapping.activityLayouts.keySet().map { it.name }.toSet()
        assertThat<String>(actualActivityNames).containsExactlyInAnyOrder(*expectedActivityNames.toTypedArray())

        for (pair in layoutMapping.activityLayouts) {
            val activityId = pair.o2
            val actualActivity = pair.o1
            val actualActivityName = actualActivity.name
            val expectedActivityId = expectedActivities.get(actualActivityName).toString()
            assertThat(activityId.toString()).isEqualTo(expectedActivityId)
        }
    }


    @Test
    fun resultsHandlerTest() {
        val layoutAnalysis = XmlLayoutAnalysis(sceneV, apkInfo)
        val callbackAnalysis = CallbackAnalysis(sceneV, layoutAnalysis)
        val callgraphAssembler = CallgraphAssembler(sceneV, callbackAnalysis.possibleCallbacks, apkInfo)
        val icfg = InterproceduralCFGWrapper(sceneV, callgraphAssembler.callgraph)
        val valueResolver = ValueResolver(sceneV, icfg)
        val appModel = AppModel(apkInfo.declaredActivities, meta)
        val lm = ActivityLayoutResolver(sceneV, appModel, valueResolver, apkInfo.resourceParser, layoutAnalysis)
        val ah = lm.resolve()
        val resFilePath = "layouts_$pkg.json"
        val layoutMapping = ah.activityLayouts.map { (k, v) -> k to (v as UIView).uiViewElement.id }.toMultiMap()
        ResultsHandler.saveActivitiesContent(layoutAnalysis.layoutsWithId, layoutMapping, Paths.get(resFilePath))
    }

    @Test
    fun simpleLayoutsTest() {
        val layoutAnalysis = XmlLayoutAnalysis(sceneV, apkInfo)
        val userControls = layoutAnalysis.flatLayouts
        assertThat(userControls.keySet()).hasSize(45)
    }

    @Test
    fun startActivityTest() {
        val layoutAnalysis = XmlLayoutAnalysis(sceneV, apkInfo)
        val callbackAnalysis = CallbackAnalysis(sceneV, layoutAnalysis)
        val callgraphAssembler = CallgraphAssembler(sceneV, callbackAnalysis.possibleCallbacks, apkInfo)
        val icfg = InterproceduralCFGWrapper(sceneV, callgraphAssembler.callgraph)
        val valueResolver = ValueResolver(sceneV, icfg)
        val appModel = AppModel(apkInfo.declaredActivities, meta)
        val atf = TransitionResolver(sceneV, appModel, icfg, valueResolver, apkInfo.resourceParser, apkInfo.intentFilters)
        atf.resolve()
        val activityTransitions = appModel.transitions
        val resFilePath = "transitions_$pkg.json"
        // ResultsHandler.saveTransitions(activityTransitions, Paths.get(resFilePath));
        for (activityTransition in activityTransitions) {
            println(activityTransition)
        }
        assertThat(sceneV.callGraph).hasSize(1482)
    }

}
