package saarland.cispa.frontmatter


import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import saarland.cispa.frontmatter.analyses.AppPlatform
import saarland.cispa.frontmatter.analyses.FrontmatterMetadata
import saarland.cispa.frontmatter.cg.CallgraphAssembler
import saarland.cispa.frontmatter.cg.InterproceduralCFGWrapper
import saarland.cispa.frontmatter.resolvers.FragmentLayoutResolver
import saarland.cispa.frontmatter.resolvers.FragmentResolver
import saarland.cispa.frontmatter.resolvers.ValueResolver
import soot.jimple.infoflow.android.manifest.ProcessManifest
import soot.util.MultiMap
import java.nio.file.Path
import java.nio.file.Paths


class FragmentsTest {

    private var androidJar: Path = Paths.get("/Users/kuznetsov/work/android/sdk/platforms/")

    private fun findFragments(apkFile: Path): MultiMap<ActivityClass, FragmentWithContainer> {
        val manifest = ProcessManifest(apkFile.toFile())
        val sceneV = Utils.initializeSoot(androidJar, apkFile, manifest.packageName)
        val apkManifestInfo = ApkInfo(sceneV, apkFile, manifest)
        val layoutAnalysis = XmlLayoutAnalysis(sceneV, apkManifestInfo)
//        println("XML fragments:\n ${layoutAnalysis.xmlLayoutIdToFragments}")
//        println("XML fragments size: ${layoutAnalysis.xmlLayoutIdToFragments.size()}")
        val callbackAnalysis = CallbackAnalysis(sceneV, layoutAnalysis)
        val callgraphAssembler = CallgraphAssembler(sceneV, callbackAnalysis.possibleCallbacks, apkManifestInfo)
        val icfg = InterproceduralCFGWrapper(sceneV, callgraphAssembler.callgraph)
        val valueResolver = ValueResolver(sceneV, icfg, true)
        // resolve layouts
        val appModel = AppModel(apkManifestInfo.declaredActivities, FrontmatterMetadata("", "", 0, 0, "", AppPlatform.NORMAL, ""))
        val lm = ActivityLayoutResolver(sceneV, appModel, valueResolver, apkManifestInfo.resourceParser, layoutAnalysis)
        lm.resolve()
        val fr = FragmentResolver(sceneV, appModel, valueResolver)
        fr.resolve()
        val fragmentLayoutResolver = FragmentLayoutResolver(sceneV, icfg, valueResolver, appModel, apkManifestInfo.resourceParser, layoutAnalysis)
        fragmentLayoutResolver.resolve()


        val fragmentMapping = appModel.fragmentMapping
        println("Number of fragments: ${callgraphAssembler.getAllFragments().size}")
        println("Declared fragments: ${callgraphAssembler.getAllFragments()}")
        println("Fragments associated: ${fragmentMapping.size()}")
        for (activityTransition in fragmentMapping) {
            println(activityTransition)
        }
        return fragmentMapping

    }

    @Test
    fun fragmentLifecycle1Test() {
        val apkFile = Paths.get("/Users/kuznetsov/work/workspace/DroidBench/apk/Lifecycle/FragmentLifecycle1.apk")
        val fragments = findFragments(apkFile)
        assertThat(fragments).hasSize(1)
    }

    @Test
    fun fragmentLifecycle2Test() {
        val apkFile = Paths.get("/Users/kuznetsov/work/workspace/DroidBench/apk/Lifecycle/FragmentLifecycle1.apk")
        val fragments = findFragments(apkFile)
        assertThat(fragments).hasSize(1)
    }

    @Test
    fun fragments1Test() {
        val apkFile = Paths.get("/Users/kuznetsov/work/workspace/apk_samples/backstage_test_apps/apps/fragementapp.apk")
        val fragments = findFragments(apkFile)
        assertThat(fragments).hasSize(2)
    }

    @Test
    fun realApp1Test() {
        val apkFile = Paths.get("/Users/kuznetsov/work/workspace/apk_samples/apk/com.digcy.pilot.apk")
        val fragments = findFragments(apkFile)
        assertThat(fragments).hasSize(52)
    }

    @Test
    fun uniAppTest() {
        val apkFile = Paths.get("/Users/kuznetsov/work/workspace/apk_samples/uni-app-android/android-app/android-app-release.apk")
        val fragments = findFragments(apkFile)
        assertThat(fragments).hasSize(2)
    }
}
