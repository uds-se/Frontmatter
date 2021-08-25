package saarland.cispa.frontmatter

import org.assertj.core.api.Assertions
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import saarland.cispa.frontmatter.analyses.AppPlatform
import saarland.cispa.frontmatter.analyses.FrontmatterMetadata
import saarland.cispa.frontmatter.cg.CallgraphAssembler
import saarland.cispa.frontmatter.cg.InterproceduralCFGWrapper
import saarland.cispa.frontmatter.resolvers.ValueResolver
import soot.Scene
import soot.jimple.infoflow.android.manifest.ProcessManifest
import java.nio.file.Path
import java.nio.file.Paths

class MultipleListenerTest {
    private lateinit var resParser: ResourceParser
    private lateinit var sceneV: Scene
    private lateinit var apkInfo: ApkInfo
    private var apkFile: Path = Paths.get("/Users/kuznetsov/work/workspace/apk_samples/backstage_test_apps/MultiListener/app/build/outputs/apk/debug/app-debug.apk")

    private var androidJar: Path = Paths.get("/Users/kuznetsov/work/android/sdk/platforms")
    private val meta = FrontmatterMetadata("", "", 0, 0, "", AppPlatform.NORMAL, "")

    @BeforeEach
    fun init() {
        val manifest = ProcessManifest(apkFile.toFile())
        sceneV = Utils.initializeSoot(androidJar, apkFile, manifest.packageName)
        apkInfo = ApkInfo(sceneV, apkFile, manifest)
        resParser = apkInfo.resourceParser
    }

    @Test
    fun callChainTest() {
        val layoutAnalysis = XmlLayoutAnalysis(sceneV, apkInfo)
        val callbackAnalysis = CallbackAnalysis(sceneV, layoutAnalysis)
        val callgraphAssembler = CallgraphAssembler(sceneV, callbackAnalysis.possibleCallbacks, apkInfo)
        val icfg = InterproceduralCFGWrapper(sceneV, callgraphAssembler.callgraph)
        val appModel = AppModel(apkInfo.declaredActivities, meta)
        val cw = CallchainWalker(sceneV, appModel, ValueResolver(sceneV, icfg))
//        val resolveFromCallback = cw.bindCallbackToViews(sceneV.getMethod("<saarland.cispa.st.backstage.testapps.multilistener.MainActivity$1: void onClick(android.view.View)>"))
        val ids = cw.traceToViewId(
            sceneV.getMethod("<saarland.cispa.st.backstage.testapps.multilistener.MainActivity$1: void onClick(android.view.View)>"),
            sceneV.getMethod("<android.telephony.TelephonyManager: java.lang.String getDeviceId()>")
        )
        Assertions.assertThat(ids).containsExactly(2130968579)
    }
}
