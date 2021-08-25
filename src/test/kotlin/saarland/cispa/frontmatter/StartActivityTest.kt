package saarland.cispa.frontmatter


import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import saarland.cispa.frontmatter.analyses.AppPlatform
import saarland.cispa.frontmatter.analyses.FrontmatterMetadata
import saarland.cispa.frontmatter.cg.CallgraphAssembler
import saarland.cispa.frontmatter.cg.InterproceduralCFGWrapper
import saarland.cispa.frontmatter.data.ActivityOrigin
import saarland.cispa.frontmatter.resolvers.Transition
import saarland.cispa.frontmatter.resolvers.TransitionResolver
import saarland.cispa.frontmatter.resolvers.ValueResolver
import soot.SootClass
import soot.jimple.infoflow.android.manifest.ProcessManifest
import soot.util.HashMultiMap
import java.nio.file.Path
import java.nio.file.Paths


class StartActivityTest {

    private var androidJar = Paths.get("/Users/kuznetsov/work/android/sdk/platforms/")
    private val meta = FrontmatterMetadata("", "", 0, 0, "", AppPlatform.NORMAL, "")
    private fun findTransitions(apkFile: Path): MutableSet<Transition> {
        val manifest = ProcessManifest(apkFile.toFile())
        val sceneV = Utils.initializeSoot(androidJar, apkFile, manifest.packageName)
        val apkInfo = ApkInfo(sceneV, apkFile, manifest)
        val layoutAnalysis = XmlLayoutAnalysis(sceneV, apkInfo)
        val callbackAnalysis = CallbackAnalysis(sceneV, layoutAnalysis)
        val callgraphAssembler = CallgraphAssembler(sceneV, callbackAnalysis.possibleCallbacks, apkInfo)//, InfoflowConfiguration.CallgraphAlgorithm.GEOM)
        val icfg = InterproceduralCFGWrapper(sceneV, callgraphAssembler.callgraph)
        val valueResolver = ValueResolver(sceneV, icfg)
//        val layoutMapper = LayoutMapper(sceneV, icfg, valueResolver, layoutAnalysis.layoutIdsToFragments)
//        val fragmentResolver = FragmentResolver(sceneV, icfg, valueResolver, layoutMapper.resolveXml())
        val appModel = AppModel(apkInfo.declaredActivities, meta)
        val atf = TransitionResolver(sceneV, appModel, icfg, valueResolver, apkInfo.resourceParser, apkInfo.intentFilters)
        atf.resolve()
        val activityTransitions = appModel.transitions
        println(activityTransitions.size)
        for (activityTransition in activityTransitions) {
            println(activityTransition)
        }
        return activityTransitions

    }

    @Test
    fun scopeTest() {
        val apkFile = Paths.get("/Users/kuznetsov/work/workspace/apk_samples/backstage_test_apps/Button_xml/app/build/outputs/apk/debug/app-debug.apk")
        val transitions = findTransitions(apkFile)
        assertThat(transitions).hasSize(2)
    }

    @Test
    fun activityCommunication3Test() {
        val apkFile = Paths.get("/Users/kuznetsov/work/workspace/DroidBench/apk/InterComponentCommunication/ActivityCommunication3.apk")
        val transitions = findTransitions(apkFile)
        assertThat(transitions).hasSize(1)
    }

    @Test
    fun activityCommunication4Test() {
        val apkFile = Paths.get("/Users/kuznetsov/work/workspace/DroidBench/apk/InterComponentCommunication/ActivityCommunication4.apk")
        val transitions = findTransitions(apkFile)
        assertThat(transitions).hasSize(1)
    }

    @Test
    fun activityCommunication5Test() {
        val apkFile = Paths.get("/Users/kuznetsov/work/workspace/DroidBench/apk/InterComponentCommunication/ActivityCommunication5.apk")
        val transitions = findTransitions(apkFile)
        assertThat(transitions).hasSize(1)
    }

    @Test
    fun valueFromList1Test() {
        val apkFile = Paths.get("/Users/kuznetsov/work/workspace/DroidBench/apk/InterComponentCommunication/ActivityCommunication6.apk")
        val transitions = findTransitions(apkFile)
        assertThat(transitions).hasSize(1)
    }

    @Test
    fun valueFromList2Test() {
        val apkFile = Paths.get("/Users/kuznetsov/work/workspace/DroidBench/apk/InterComponentCommunication/ActivityCommunication7.apk")
        val transitions = findTransitions(apkFile)
        assertThat(transitions).hasSize(1)
    }

    @Test
    fun componentNotInManifest1Test() {
        val apkFile = Paths.get("/Users/kuznetsov/work/workspace/DroidBench/apk/InterComponentCommunication/ComponentNotInManifest1.apk")
        val transitions = findTransitions(apkFile)
        assertThat(transitions).hasSize(0)
    }

    @Test
    fun intentSource1Test() {
        val apkFile = Paths.get("/Users/kuznetsov/work/workspace/DroidBench/apk/InterComponentCommunication/IntentSource1.apk")
        val transitions = findTransitions(apkFile)
        assertThat(transitions).hasSize(1)
    }

    @Test
    fun unresolvableIntent1Test() {
        val apkFile = Paths.get("/Users/kuznetsov/work/workspace/DroidBench/apk/InterComponentCommunication/UnresolvableIntent1.apk")
        val transitions = findTransitions(apkFile)
        assertThat(transitions).hasSize(0)
    }

    @Test
    fun realApp1Test() {
        val apkFile = Paths.get("/Users/kuznetsov/work/workspace/apk_samples/apk/com.digcy.pilot.apk")
        val transitions = findTransitions(apkFile)
        assertThat(transitions).hasSize(380) //355
    }

    @Test
    fun fragments1Test() {
        val apkFile = Paths.get("/Users/kuznetsov/work/workspace/apk_samples/backstage_test_apps/apps/fragementapp.apk")
        val transitions = findTransitions(apkFile)
        assertThat(transitions).hasSize(1)
    }

    @Test
    fun flixbusTest() {
        val apkFile = Paths.get("src/test/resources/de.flixbus.app.apk")
        val transitions = findTransitions(apkFile)
        assertThat(transitions).hasSize(80) // was 60
    }

    @Test
    fun indianrailwayTest() {
        val apkFile = Paths.get("src/test/resources/com.recoverinfotech.com.indianrailway.apk")
        val transitions = findTransitions(apkFile)
        assertThat(transitions).hasSize(-1) //incomplete
    }

    @Test
    fun birthdayDroidTest() {
        val apkFile = Paths.get("/Users/kuznetsov/work/workspace/apk_samples/backstage_test_apps/Button_xml/app/build/outputs/apk/debug/app-debug.apk")
        val transitions = findTransitions(apkFile)
        assertThat(transitions).hasSize(-1) //incomplete
    }

    @Test
    fun irctcTest() {
        val apkFile = Paths.get("src/test/resources/com.app.text.rail.information.offline.apk")
        val transitions = findTransitions(apkFile)
//        for (applicationClass in Scene.v().applicationClasses) {
//            for (method in applicationClass.methods) {
//                if (method.isConcrete)
//                    if (method.activeBody.units.filterIsInstance<Stmt>()
//                            .filter { it.toString().contains("com.app.text.rail.information.offline.l:") }.any())
//                        println(method)
//            }
//        }
        assertThat(transitions).hasSize(13) //incomplete, was 11 with callback entrypoints

    }


}
