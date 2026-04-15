package eu.su.mas.dedaleEtu.mas.knowledge;

import java.io.Serializable;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class MapDelta implements Serializable {
    private static final long serialVersionUID = 1L;
    
    public Set<String> newEdges = new HashSet<>(); // format "nodeA-nodeB"
    public Map<String, MapRepresentation.MapAttribute> nodeUpdates = new HashMap<>();
    public Map<String, Boolean> scentUpdates = new HashMap<>();
    
    public boolean isEmpty() {
        return newEdges.isEmpty() && nodeUpdates.isEmpty() && scentUpdates.isEmpty();
    }
}