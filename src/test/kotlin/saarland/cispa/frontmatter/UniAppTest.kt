package saarland.cispa.frontmatter


import org.assertj.core.api.Assertions.assertThat
import org.junit.Ignore
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import saarland.cispa.frontmatter.analyses.AppPlatform
import saarland.cispa.frontmatter.analyses.FrontmatterMetadata
import saarland.cispa.frontmatter.cg.CallgraphAssembler
import saarland.cispa.frontmatter.cg.InterproceduralCFGWrapper
import saarland.cispa.frontmatter.resolvers.TransitionResolver
import saarland.cispa.frontmatter.resolvers.ValueResolver
import saarland.cispa.frontmatter.resolvers.ViewResolver
import soot.Scene
import soot.jimple.infoflow.android.manifest.ProcessManifest
import java.nio.file.Paths


class UniAppTest {
    private lateinit var appModel: AppModel
    private lateinit var resParser: ResourceParser
    private lateinit var sceneV: Scene
    private lateinit var apkInfo: ApkInfo
    private var apkFile = Paths.get("/Users/kuznetsov/work/workspace/apk_samples/uni-app-android/android-app/build/outputs/apk/debug/android-app-debug.apk")

    //    private var apkFile: String = "/Users/kuznetsov/work/workspace/apk_samples/backstage_test_apps/Button_xml/app/build/outputs/apk/debug/app-debug.apk"
    private val meta = FrontmatterMetadata("", "", 0, 0, "", AppPlatform.NORMAL, "")
    private var androidJar = Paths.get(System.getenv("ANDROID_HOME"), "platforms")

    @BeforeEach
    fun init() {
        val manifest = ProcessManifest(apkFile.toFile())
        sceneV = Utils.initializeSoot(androidJar, apkFile, manifest.packageName)
        apkInfo = ApkInfo(sceneV, apkFile, manifest)
        resParser = apkInfo.resourceParser
        appModel = AppModel(apkInfo.declaredActivities, meta)
    }

    @Test
    fun callGraphSizeTest() {
        val layoutAnalysis = XmlLayoutAnalysis(sceneV, apkInfo)
        val callbackAnalysis = CallbackAnalysis(sceneV, layoutAnalysis)
        val callgraphAssembler = CallgraphAssembler(sceneV, callbackAnalysis.possibleCallbacks, apkInfo)
        val callGraph = callgraphAssembler.callgraph
        assertThat(callGraph).hasSize(7523)
    }

    @Test
    fun setContentViewTest() {
        val layoutAnalysis = XmlLayoutAnalysis(sceneV, apkInfo)
        val callbackAnalysis = CallbackAnalysis(sceneV, layoutAnalysis)
        val callgraphAssembler = CallgraphAssembler(sceneV, callbackAnalysis.possibleCallbacks, apkInfo)
        val icfg = InterproceduralCFGWrapper(sceneV, callgraphAssembler.callgraph)
        val valueResolver = ValueResolver(sceneV, icfg)
        val activityHolder = AppModel(apkInfo.declaredActivities, meta)
        val lm = ActivityLayoutResolver(sceneV, activityHolder, valueResolver, apkInfo.resourceParser, layoutAnalysis)
        val ah = lm.resolve()
        val layoutMapping = ah.activityLayouts
        val actualActivityNames = layoutMapping.keySet().map { it.name }.toSet()
        print(actualActivityNames)
        val expectedActivityNames = setOf(
            "de.unisaarland.uniApp.staff.SearchResultActivity",
            "de.unisaarland.uniApp.bus.BusDetailActivity",
            "de.unisaarland.uniApp.about.AboutActivity",
            "de.unisaarland.uniApp.campus.CampusSearchActivity",
            "de.unisaarland.uniApp.campus.CampusActivity",
            "de.unisaarland.uniApp.rssViews.RSSDetailActivity",
            "de.unisaarland.uniApp.bus.BusActivity",
            "de.unisaarland.uniApp.MainActivity",
            "de.unisaarland.uniApp.staff.SearchStaffActivity",
            "de.unisaarland.uniApp.staff.SearchResultItemDetailActivity",
            "de.unisaarland.uniApp.staff.PersonDetailWebActivity",
            "de.unisaarland.uniApp.rssViews.RSSActivity",
            "de.unisaarland.uniApp.restaurant.OpeningHoursActivity",
            "de.unisaarland.uniApp.restaurant.RestaurantActivity",
            "de.unisaarland.uniApp.restaurant.uihelper.MensaShowIngredientsActivity",
            "de.unisaarland.uniApp.settings.SettingsActivity"
        )
        assertThat(actualActivityNames).containsExactlyInAnyOrder(*expectedActivityNames.toTypedArray())
    }

    @Test
    @Ignore("deprecated")
    fun setCallbackTest() {
        val layoutAnalysis = XmlLayoutAnalysis(sceneV, apkInfo)
        val callbackAnalysis = CallbackAnalysis(sceneV, layoutAnalysis)
        val callgraphAssembler = CallgraphAssembler(sceneV, callbackAnalysis.possibleCallbacks, apkInfo)
        val icfg = InterproceduralCFGWrapper(sceneV, callgraphAssembler.callgraph)
        val valueResolver = ValueResolver(sceneV, icfg)
        val viewResolver = ViewResolver(sceneV.getSootClass(DUMMY_ACTIVITY_NAME), appModel, sceneV, valueResolver, apkInfo.resourceParser, layoutAnalysis)
        val cf = CallbackFinder(sceneV, valueResolver, viewResolver)
        val callbacks = cf.resolve()
        println(callbacks.size)
        callbacks.onEach { println(it) }
        assertThat(callbacks.filter { it.third?.name == "de.unisaarland.uniApp.MainActivity" }).hasSize(6)
        assertThat(callbacks.filter { it.third?.name == "de.unisaarland.uniApp.about.AboutActivity" }).hasSize(2)
        assertThat(callbacks.filter { it.third?.name == "de.unisaarland.uniApp.bus.BusDetailActivity" }).hasSize(2)
        assertThat(callbacks.filter { it.third?.name == "de.unisaarland.uniApp.bus.BusActivity" }).hasSize(2) //BusStationsAdapter & SearchStationAdapter
        assertThat(callbacks.filter { it.third?.name == "de.unisaarland.uniApp.campus.CampusActivity" }).hasSize(4)
        assertThat(callbacks.filter { it.third?.name == "de.unisaarland.uniApp.campus.CampusSearchActivity" }).hasSize(1)
        assertThat(callbacks.filter { it.third?.name == "de.unisaarland.uniApp.campus.CampusSearchActivity\$CampusCategoriesAdapter" }).hasSize(3)
        assertThat(callbacks.filter { it.third?.name == "de.unisaarland.uniApp.campus.uihelper.SearchAdapter" }).hasSize(1)
        assertThat(callbacks.filter { it.third?.name == "de.unisaarland.uniApp.staff.PersonDetailWebActivity" }).hasSize(2)
        assertThat(callbacks.filter { it.third?.name == "de.unisaarland.uniApp.staff.SearchResultItemDetailActivity" }).hasSize(4)
        assertThat(callbacks.filter { it.third?.name == "de.unisaarland.uniApp.staff.SearchStaffActivity" }).hasSize(3)
        assertThat(callbacks.filter { it.third?.name == "de.unisaarland.uniApp.staff.SearchResultActivity" }).hasSize(1)
        assertThat(callbacks.filter { it.third?.name == "de.unisaarland.uniApp.rssViews.RSSAdapter" }).hasSize(1)
    }

    @Test
    fun callChainTest() {
        val layoutAnalysis = XmlLayoutAnalysis(sceneV, apkInfo)
        val callbackAnalysis = CallbackAnalysis(sceneV, layoutAnalysis)
        val callgraphAssembler = CallgraphAssembler(sceneV, callbackAnalysis.possibleCallbacks, apkInfo)
        val icfg = InterproceduralCFGWrapper(sceneV, callgraphAssembler.callgraph)
        val cw = CallchainWalker(sceneV, appModel, ValueResolver(sceneV, icfg))
        val ids = cw.traceToViewId(
            sceneV.getMethod("<com.example.avdiienko.button_xml.OneListenerMultipleButtons$2: void onClick(android.view.View)>"),
            sceneV.getMethod("<android.telephony.TelephonyManager: java.lang.String getDeviceId()>")
        )
        assertThat(ids).containsExactly(2131165250)
//        val resolveFromCallback = cw.bindCallbackToViews(sceneV.getMethod("<com.example.avdiienko.button_xml.OneListenerMultipleButtons$2: void onClick(android.view.View)>"))
//        val resolveFromCallback2 = cw.resolveCallbackUnitsById(sceneV.getMethod("<com.example.avdiienko.button_xml.OneListenerMultipleButtons$2: void onClick(android.view.View)>"), 2131230730)
    }
//     @Test
//     public void saveResultsTest() {
//         XmlLayoutAnalysis layoutAnalysis = new XmlLayoutAnalysis(sceneV, apkManifestInfo);
//         CallbackAnalysis callbackAnalysis = new CallbackAnalysis(sceneV, layoutAnalysis);
//         CallgraphAssembler callgraphAssembler = new CallgraphAssembler(sceneV, callbackAnalysis.getPossibleCallbacks(), apkManifestInfo);
//         IInfoflowCFG icfg = new InterproceduralCFGWrapper(sceneV, callgraphAssembler.getCallgraph());
//         ValueResolver valueResolver = new ValueResolver(sceneV, icfg);
//         LayoutMapper lm = new LayoutMapper(sceneV, icfg, valueResolver);
//         MultiMap<SootClass, Value> layoutMapping = lm.resolveLayoutMapping();
//         String pkg = Paths.get(apkFile).getFileName().toString();
//         String resFilePath = "layouts_" + pkg + ".json";
//         ResultsHandler.saveActivitiesContent(layoutAnalysis.getLayoutsWithId(), layoutMapping, Paths.get(resFilePath));
//     }

    @Test
    fun simpleLayoutsTest() {
        val layoutAnalysis = XmlLayoutAnalysis(sceneV, apkInfo)
        val userControls = layoutAnalysis.flatLayouts
        assertThat(userControls.keySet()).hasSize(73)//45
    }

    @Test
    fun startActivityTest() {
        val layoutAnalysis = XmlLayoutAnalysis(sceneV, apkInfo)
        println("===${apkInfo.manifest.targetSdkVersion()}")
        val callbackAnalysis = CallbackAnalysis(sceneV, layoutAnalysis)
        val callgraphAssembler = CallgraphAssembler(sceneV, callbackAnalysis.possibleCallbacks, apkInfo)
        val icfg = InterproceduralCFGWrapper(sceneV, callgraphAssembler.callgraph)
        val valueResolver = ValueResolver(sceneV, icfg)
        val atf = TransitionResolver(sceneV, appModel, icfg, valueResolver, apkInfo.resourceParser, apkInfo.intentFilters)
        atf.resolve()
        val activityTransitions = appModel.transitions
        for (activityTransition in activityTransitions) {
            System.out.println(activityTransition)
        }
//        assertThat(activityTransitions[sceneV.getSootClass("de.unisaarland.uniApp.MainActivity")]).hasSize(7)
//        assertThat(activityTransitions[sceneV.getSootClass("de.unisaarland.uniApp.campus.CampusActivity")]).hasSize(3)
//        assertThat(activityTransitions[sceneV.getSootClass("de.unisaarland.uniApp.restaurant.RestaurantActivity")]).hasSize(2)
//        assertThat(activityTransitions[sceneV.getSootClass("de.unisaarland.uniApp.staff.SearchResultItemDetailActivity")]).hasSize(2)
//        assertThat(activityTransitions[sceneV.getSootClass("de.unisaarland.uniApp.staff.SearchStaffActivity")]).hasSize(1)
//        assertThat(activityTransitions[sceneV.getSootClass("de.unisaarland.uniApp.staff.SearchResultActivity")]).hasSize(1)
//
//        assertThat(activityTransitions[sceneV.getSootClass("de.unisaarland.uniApp.about.AboutActivity")]).isEmpty()
        assertThat(activityTransitions).hasSize(18)
        //TODO: complete
    }
}
