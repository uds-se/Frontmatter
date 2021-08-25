package saarland.cispa.frontmatter.analyses

import saarland.cispa.frontmatter.ApkInfo
import saarland.cispa.frontmatter.CallbackAnalysis
import saarland.cispa.frontmatter.XmlLayoutAnalysis
import saarland.cispa.frontmatter.cg.CallgraphAssembler
import saarland.cispa.frontmatter.cg.InterproceduralCFGWrapper
import saarland.cispa.frontmatter.resolvers.ValueResolver
import soot.Scene
import soot.jimple.infoflow.InfoflowConfiguration

internal abstract class Analysis(protected val sceneV: Scene, protected val apkInfo: ApkInfo) {

    val layoutAnalysis = XmlLayoutAnalysis(sceneV, apkInfo)
    val callbackAnalysis = CallbackAnalysis(sceneV, layoutAnalysis)
    val callgraphAssembler = CallgraphAssembler(sceneV, callbackAnalysis.possibleCallbacks, apkInfo, InfoflowConfiguration.CallgraphAlgorithm.SPARK)
    val icfg = InterproceduralCFGWrapper(sceneV, callgraphAssembler.callgraph)
    val valueResolver = ValueResolver(sceneV, icfg, true)

}
