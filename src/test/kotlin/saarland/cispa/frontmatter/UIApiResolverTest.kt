package saarland.cispa.frontmatter

import org.junit.Test
import saarland.cispa.frontmatter.analyses.AppPlatform
import saarland.cispa.frontmatter.analyses.FrontmatterMetadata
import saarland.cispa.frontmatter.cg.CallgraphAssembler
import saarland.cispa.frontmatter.cg.InterproceduralCFGWrapper
import saarland.cispa.frontmatter.resolvers.UIApiResolver
import saarland.cispa.frontmatter.resolvers.ValueResolver
import saarland.cispa.frontmatter.results.ApiModel
import saarland.cispa.frontmatter.results.ResultsHandler
import saarland.cispa.frontmatter.results.SimpleLayoutUIElement
import soot.jimple.infoflow.android.manifest.ProcessManifest
import java.nio.file.Path
import java.nio.file.Paths

class UIApiResolverTest {

    private var androidJar: Path = Paths.get("/Users/kuznetsov/work/android/sdk/platforms/")

    //    private var apkFile: String = "/Users/kuznetsov/work/workspace/apk_samples/uni-app-android/android-app/build/outputs/apk/debug/android-app-debug.apk"
//    private var apkFile: String = "/Users/kuznetsov/work/workspace/apk_samples/backstage_test_apps/FragmentApp/app/build/outputs/apk/app-debug.apk"
//    private val uiFile = Paths.get("/Users/kuznetsov/work/workspace/backstage_project/backstage-revived/data_v2/uni-app.json")

    @Test
    fun testTraceApi() {
        val apkFile: Path = Paths.get("/Users/kuznetsov/work/workspace/apk_samples/backstage_test_apps/Button_xml/app/build/outputs/apk/debug/app-debug.apk")
        val manifest = ProcessManifest(apkFile.toFile())
        val sceneV = Utils.initializeSoot(androidJar, apkFile, manifest.packageName)
        val apkInfo = ApkInfo(sceneV, apkFile, manifest)
        val layoutAnalysis = XmlLayoutAnalysis(sceneV, apkInfo)
        val callbackAnalysis = CallbackAnalysis(sceneV, layoutAnalysis)
        val callgraphAssembler = CallgraphAssembler(sceneV, callbackAnalysis.possibleCallbacks, apkInfo)
        val icfg = InterproceduralCFGWrapper(sceneV, callgraphAssembler.callgraph)
        val valueResolver = ValueResolver(sceneV, icfg)
        val guid = "mockGUID"
        val listener = "<com.example.avdiienko.button_xml.OneListenerMultipleButtons$2: void onClick(android.view.View)>"
        val uiElements = listOf(
            SimpleLayoutUIElement(guid, 2131165250, setOf(listener)),
            SimpleLayoutUIElement(guid, 2131165215, setOf(listener)),
            SimpleLayoutUIElement(guid, 2131165217, setOf(listener))
        )
        val uiApiResolver = UIApiResolver(sceneV, icfg, valueResolver, apkInfo, layoutAnalysis.uiFactory)
        uiApiResolver.resolve(uiElements)
    }

    @Test
    fun testResolveApi() {
        val uiFile = Paths.get("/Users/kuznetsov/work/workspace/backstage_project/backstage-revived/data_v2/uni-app.json")
        val apkFile = Paths.get("/Users/kuznetsov/work/workspace/apk_samples/uni-app-android/android-app/build/outputs/apk/debug/android-app-debug.apk")
        val destPath = Paths.get("/Users/kuznetsov/work/workspace/backstage_project/backstage-revived/data_v2/uni-app-api.json")
        val manifest = ProcessManifest(apkFile.toFile())
        val sceneV = Utils.initializeSoot(androidJar, apkFile, manifest.packageName)
        val apkInfo = ApkInfo(sceneV, apkFile, manifest)
        val layoutAnalysis = XmlLayoutAnalysis(sceneV, apkInfo)
        val callbackAnalysis = CallbackAnalysis(sceneV, layoutAnalysis)
        val callgraphAssembler = CallgraphAssembler(sceneV, callbackAnalysis.possibleCallbacks, apkInfo)
        val icfg = InterproceduralCFGWrapper(sceneV, callgraphAssembler.callgraph)
        val valueResolver = ValueResolver(sceneV, icfg)
        val flatUI = ResultsHandler.loadFlatUI(uiFile)
        val meta = FrontmatterMetadata("", "", 0, 0, "", AppPlatform.NORMAL, "")
        val apiModel = ApiModel(flatUI, meta, manifest.permissions)
        val uiApiResolver = UIApiResolver(sceneV, icfg, valueResolver, apkInfo, layoutAnalysis.uiFactory)
        uiApiResolver.resolve(apiModel.layoutUiElements)
        ResultsHandler.saveApi(apiModel, destPath)
    }

    @Test
    fun testLoadFlatUI() {
        val apkFile: String = "/Users/kuznetsov/work/workspace/gator/gator-3.8/AndroidBench/APKs/CGO14ASE15/vlc.apk"
        val uiFile = Paths.get("/Users/kuznetsov/work/workspace/backstage_project/backstage-revived/data_v2/vlc.json")
        val flatModel = ResultsHandler.loadFlatUI(uiFile)
        print("")
    }
//    val uiId = 2131492948 // 2131230909
//    val listener = "<com.example.avdiienko.fragmentapp.SecondActivity: void onClick(android.view.View)>"

}
