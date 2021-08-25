package saarland.cispa.frontmatter


import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import saarland.cispa.frontmatter.analyses.AppPlatform
import saarland.cispa.frontmatter.analyses.FrontmatterMetadata
import saarland.cispa.frontmatter.cg.CallgraphAssembler
import saarland.cispa.frontmatter.cg.InterproceduralCFGWrapper
import saarland.cispa.frontmatter.resolvers.FragmentResolver
import saarland.cispa.frontmatter.resolvers.ValueResolver
import soot.SootClass
import soot.jimple.infoflow.InfoflowConfiguration
import soot.jimple.infoflow.android.manifest.ProcessManifest
import soot.util.MultiMap
import java.nio.file.Path
import java.nio.file.Paths


class TitleResolverTest {

    private var androidJar = Paths.get("/Users/kuznetsov/work/android/sdk/platforms/")
    private val meta = FrontmatterMetadata("", "", 0, 0, "", AppPlatform.NORMAL, "")
    private fun findTitles(apkFile: Path): MultiMap<SootClass, String> {
        val manifest = ProcessManifest(apkFile.toFile())
        val sceneV = Utils.initializeSoot(androidJar, apkFile, manifest.packageName)
        val apkManifestInfo = ApkInfo(sceneV, apkFile, manifest)
        val appModel = AppModel(apkManifestInfo.declaredActivities, meta)
        val layoutAnalysis = XmlLayoutAnalysis(sceneV, apkManifestInfo)
        val callbackAnalysis = CallbackAnalysis(sceneV, layoutAnalysis)
        val callgraphAssembler = CallgraphAssembler(sceneV, callbackAnalysis.possibleCallbacks, apkManifestInfo, InfoflowConfiguration.CallgraphAlgorithm.SPARK)
        val icfg = InterproceduralCFGWrapper(sceneV, callgraphAssembler.callgraph)
        val valueResolver = ValueResolver(sceneV, icfg)
//        val layoutMapper = ActivityLayoutResolver(sceneV, appModel, valueResolver, apkManifestInfo.resourceParser, layoutAnalysis)
//        val ah = layoutMapper.resolve()
//        val fragmentResolver = FragmentResolver(sceneV, appModel, valueResolver)
//        fragmentResolver.resolve()
        val fragments = appModel.fragmentToActivity
        val titleResolver = TitleResolver(sceneV, appModel, valueResolver, apkManifestInfo.resourceParser)
        titleResolver.resolve()
        for (title in appModel.activityLabels) {
            println(title)
        }
        //
        return appModel.activityLabels

    }

    @Test
    fun realApp1Test() {
        val apkFile = Paths.get("/Users/kuznetsov/work/workspace/apk_samples/apk/com.digcy.pilot.apk")
        val titles = findTitles(apkFile)
        assertThat(titles).hasSize(5)
    }

    @Test
    fun fragments1Test() {
        val apkFile = Paths.get("/Users/kuznetsov/work/workspace/apk_samples/backstage_test_apps/apps/fragementapp.apk")
        val titles = findTitles(apkFile)
        assertThat(titles).hasSize(1)
    }

    @Test
    fun flixbusTest() {
        val apkFile = Paths.get("src/test/resources/de.flixbus.app.apk")
        val titles = findTitles(apkFile)
        assertThat(titles).hasSize(80) // was 60
    }

    @Test
    fun indianrailwayTest() {
        val apkFile = Paths.get("src/test/resources/com.recoverinfotech.com.indianrailway.apk")
        val titles = findTitles(apkFile)
        assertThat(titles).hasSize(-1) //incomplete
    }

    @Test
    fun birthdayDroidTest() {
        val apkFile = Paths.get("/Users/kuznetsov/work/workspace/apk_samples/backstage_test_apps/Button_xml/app/build/outputs/apk/debug/app-debug.apk")
        val titles = findTitles(apkFile)
        assertThat(titles).hasSize(-1)
    }

    @Test
    fun irctcTest() {
        val apkFile = Paths.get("src/test/resources/com.app.text.rail.information.offline.apk")
        val titles = findTitles(apkFile)
        assertThat(titles).hasSize(-1)

    }


}
