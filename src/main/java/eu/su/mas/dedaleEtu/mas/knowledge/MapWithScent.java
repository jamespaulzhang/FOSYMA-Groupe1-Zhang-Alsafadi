package eu.su.mas.dedaleEtu.mas.knowledge;

import dataStructures.serializableGraph.SerializableSimpleGraph;
import java.io.Serializable;
import java.util.Map;

public class MapWithScent implements Serializable {
    private static final long serialVersionUID = 1L;

    public final SerializableSimpleGraph<String, MapRepresentation.MapAttribute> graph;
    public final Map<String, Boolean> scent;

    public MapWithScent(SerializableSimpleGraph<String, MapRepresentation.MapAttribute> graph,
                        Map<String, Boolean> scent) {
        this.graph = graph;
        this.scent = scent;
    }
}