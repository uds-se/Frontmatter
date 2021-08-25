package soot.toolkits.scalar;

import soot.toolkits.graph.UnitGraph;

public class SimpleLocalDefsNoSSA extends SimpleLocalDefs {
    public SimpleLocalDefsNoSSA(UnitGraph graph) {
        super(graph, FlowAnalysisMode.OmitSSA);
    }
}
