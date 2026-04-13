package eu.su.mas.dedaleEtu.mas.knowledge;

import java.io.Serializable;
import java.util.*;
import java.util.stream.Collectors;

import org.graphstream.algorithm.Dijkstra;
import org.graphstream.algorithm.Toolkit;
import org.graphstream.graph.Edge;
import org.graphstream.graph.Graph;
import org.graphstream.graph.Node;
import org.graphstream.graph.implementations.SingleGraph;
import org.graphstream.ui.fx_viewer.FxViewPanel;
import org.graphstream.ui.fx_viewer.FxViewer;
import org.graphstream.ui.javafx.FxGraphRenderer;
import org.graphstream.ui.view.Viewer;

import dataStructures.serializableGraph.SerializableNode;
import dataStructures.serializableGraph.SerializableSimpleGraph;
import dataStructures.tuple.Couple;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.layout.AnchorPane;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

public class MapRepresentation implements Serializable {

    public enum MapAttribute { agent, open, closed }

    private static final long serialVersionUID = -1333959882640838272L;

    private transient Graph g;
    private transient Viewer viewer;
    private transient volatile boolean guiShown = false;
    private Integer nbEdges;
    private String agentName;

    private SerializableSimpleGraph<String, MapAttribute> sg;
    private Map<String, Boolean> serializableScent;

    private transient Map<String, Integer> scentStrength;
    private transient Map<String, Boolean> wumpusScent;
    private static final int SCENT_DECAY_PER_STEP = 2;
    private static final int SCENT_THRESHOLD = 5;

    private final String nodeStyle = "node {" +
            "fill-color: black; size-mode:fit;text-alignment:under; text-size:14;text-color:white;text-background-mode:rounded-box;text-background-color:black;}" +
            "node.open {fill-color: forestgreen;}" +
            "node.agent {fill-color: blue;}";

    public MapRepresentation(String agentName) {
        System.setProperty("org.graphstream.ui", "javafx");
        this.g = new SingleGraph("My world vision");
        this.g.setAttribute("ui.stylesheet", nodeStyle);
        this.agentName = agentName;
        this.nbEdges = 0;
        this.scentStrength = new HashMap<>();
        this.wumpusScent = new HashMap<>();
        this.guiShown = false;
    }

    public synchronized void showGUI() {
        if (guiShown) return;
        if (viewer == null) {
            try {
                Platform.runLater(() -> {
                    if (!guiShown) {
                        openGui4();
                        guiShown = true;
                    }
                });
            } catch (IllegalStateException e) {
                System.err.println("JavaFX not initialized, GUI not shown for " + agentName);
            }
        }
    }

    private synchronized void openGui4() {
        Stage stage = new Stage();
        StackPane root = new StackPane();
        AnchorPane ap = new AnchorPane();
        FxViewer viewer = new FxViewer(g, FxViewer.ThreadingModel.GRAPH_IN_ANOTHER_THREAD);
        g.setAttribute("ui.antialias");
        g.setAttribute("ui.quality");
        viewer.enableAutoLayout();
        viewer.setCloseFramePolicy(Viewer.CloseFramePolicy.CLOSE_VIEWER);
        FxViewPanel panel = (FxViewPanel) viewer.addDefaultView(false, new FxGraphRenderer());
        ap.getChildren().add(panel);
        root.getChildren().add(ap);
        stage.setTitle(agentName);
        stage.setScene(new Scene(root, 800, 800));
        stage.show();
        this.viewer = viewer;
    }

    private synchronized void closeGui() {
        if (viewer != null) {
            try { viewer.close(); } catch (Exception ignored) {}
            viewer = null;
        }
    }

    public synchronized void addNode(String id, MapAttribute attr) {
        Node n = g.getNode(id);
        if (n == null) n = g.addNode(id);
        n.setAttribute("ui.class", attr.toString());
        n.setAttribute("ui.label", id);
    }

    public synchronized boolean addNewNode(String id) {
        if (g.getNode(id) == null) {
            addNode(id, MapAttribute.open);
            return true;
        }
        return false;
    }

    public synchronized void addEdge(String id1, String id2) {
        String edgeId = id1 + "-" + id2;
        if (g.getEdge(edgeId) == null) {
            try {
                g.addEdge(edgeId, id1, id2);
                nbEdges++;
            } catch (Exception e) {}
        }
    }

    public synchronized boolean hasNode(String nodeId) {
        return g.getNode(nodeId) != null;
    }

    public synchronized List<String> getNeighbors(String nodeId) {
        Node n = g.getNode(nodeId);
        if (n == null) return Collections.emptyList();
        return n.neighborNodes().map(Node::getId).collect(Collectors.toList());
    }

    public synchronized void setScentStrength(String nodeId, int strength) {
        if (strength <= SCENT_THRESHOLD) {
            scentStrength.remove(nodeId);
            wumpusScent.remove(nodeId);
        } else {
            scentStrength.put(nodeId, strength);
            wumpusScent.put(nodeId, true);
        }
    }

    public synchronized void addScent(String nodeId, int increment) {
        int old = scentStrength.getOrDefault(nodeId, 0);
        int newStrength = Math.min(100, old + increment);
        setScentStrength(nodeId, newStrength);
    }

    public synchronized void decayScent() {
        List<String> toRemove = new ArrayList<>();
        for (Map.Entry<String, Integer> e : scentStrength.entrySet()) {
            int newStrength = e.getValue() - SCENT_DECAY_PER_STEP;
            if (newStrength <= SCENT_THRESHOLD) {
                toRemove.add(e.getKey());
            } else {
                e.setValue(newStrength);
            }
        }
        for (String node : toRemove) {
            scentStrength.remove(node);
            wumpusScent.remove(node);
        }
    }

    public synchronized void updateScentFromObservation(Set<String> observedScentNodes) {
        decayScent();
        for (String node : observedScentNodes) {
            addScent(node, 50);
        }
    }

    public synchronized List<String> getWumpusScentNodes() {
        return new ArrayList<>(wumpusScent.keySet());
    }

    public synchronized Map<String, Integer> getScentStrengthMap() {
        return new HashMap<>(scentStrength);
    }

    public synchronized String getStrongestScentNode() {
        return scentStrength.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(null);
    }

    public synchronized List<String> getShortestPath(String from, String to) {
        if (from == null || to == null) return null;
        if (from.equals(to)) return Collections.emptyList();
        Node fromNode = g.getNode(from);
        Node toNode = g.getNode(to);
        if (fromNode == null || toNode == null) return null;
        Dijkstra dijk = new Dijkstra();
        dijk.init(g);
        dijk.setSource(fromNode);
        dijk.compute();
        List<Node> path = dijk.getPath(toNode).getNodePath();
        dijk.clear();
        if (path == null || path.size() <= 1) return null;
        return path.stream().skip(1).map(Node::getId).collect(Collectors.toList());
    }

    public synchronized List<String> getShortestPathToClosestOpenNode(String pos) {
        List<String> open = getOpenNodes();
        if (open.isEmpty()) return null;
        String closest = null;
        int minDist = Integer.MAX_VALUE;
        for (String target : open) {
            List<String> path = getShortestPath(pos, target);
            int dist = (path == null) ? Integer.MAX_VALUE : path.size();
            if (dist < minDist) {
                minDist = dist;
                closest = target;
            }
        }
        if (closest == null) return null;
        return getShortestPath(pos, closest);
    }

    public synchronized List<String> getOpenNodes() {
        return g.nodes()
                .filter(n -> {
                    Object cls = n.getAttribute("ui.class");
                    return cls != null && cls.equals(MapAttribute.open.toString());
                })
                .map(Node::getId)
                .collect(Collectors.toList());
    }

    public synchronized boolean hasOpenNode() {
        return g.nodes().anyMatch(n -> {
            Object cls = n.getAttribute("ui.class");
            return cls != null && cls.equals(MapAttribute.open.toString());
        });
    }

    public synchronized SerializableSimpleGraph<String, MapAttribute> getSerializableGraph() {
        serialize();
        return sg;
    }

    public synchronized Map<String, Boolean> getSerializableScent() {
        return new HashMap<>(wumpusScent);
    }

    private void serialize() {
        sg = new SerializableSimpleGraph<>();
        g.nodes().forEach(n -> {
            String classAttr = (String) n.getAttribute("ui.class");
            MapAttribute attr;
            try {
                attr = MapAttribute.valueOf(classAttr);
            } catch (IllegalArgumentException e) {
                attr = MapAttribute.open;
            }
            sg.addNode(n.getId(), attr);
        });
        g.edges().forEach(e -> {
            String source = e.getSourceNode().getId();
            String target = e.getTargetNode().getId();
            sg.addEdge(e.getId(), source, target);
        });
        serializableScent = new HashMap<>(wumpusScent);
    }

    public synchronized void prepareMigration() {
        serialize();
        closeGui();
        g = null;
        guiShown = false;
    }

    public synchronized void loadSavedData() {
        g = new SingleGraph("My world vision");
        g.setAttribute("ui.stylesheet", nodeStyle);
        int edgeCounter = 0;
        if (sg != null) {
            for (SerializableNode<String, MapAttribute> n : sg.getAllNodes()) {
                Node node = g.addNode(n.getNodeId());
                node.setAttribute("ui.class", n.getNodeContent().toString());
                node.setAttribute("ui.label", n.getNodeId());
                for (String s : sg.getEdges(n.getNodeId())) {
                    String edgeId = n.getNodeId() + "-" + s;
                    if (g.getEdge(edgeId) == null) {
                        g.addEdge(edgeId, n.getNodeId(), s);
                        edgeCounter++;
                    }
                }
            }
        }
        this.wumpusScent = new HashMap<>();
        this.scentStrength = new HashMap<>();
        if (serializableScent != null) {
            for (Map.Entry<String, Boolean> e : serializableScent.entrySet()) {
                if (e.getValue()) {
                    wumpusScent.put(e.getKey(), true);
                    scentStrength.put(e.getKey(), 50);
                }
            }
        }
        System.out.println(agentName + " map loaded (" + (sg == null ? 0 : sg.getAllNodes().size()) + " nodes, " + edgeCounter + " edges)");
    }

    public synchronized boolean mergeMap(SerializableSimpleGraph<String, MapAttribute> received) {
        boolean changed = false;
        for (SerializableNode<String, MapAttribute> n : received.getAllNodes()) {
            Node existing = g.getNode(n.getNodeId());
            if (existing == null) {
                addNode(n.getNodeId(), n.getNodeContent());
                changed = true;
            } else {
                String cur = (String) existing.getAttribute("ui.class");
                if (cur != null && cur.equals(MapAttribute.open.toString()) && n.getNodeContent() == MapAttribute.closed) {
                    existing.setAttribute("ui.class", MapAttribute.closed.toString());
                    changed = true;
                }
            }
        }
        for (SerializableNode<String, MapAttribute> n : received.getAllNodes()) {
            for (String target : received.getEdges(n.getNodeId())) {
                String edgeId = n.getNodeId() + "-" + target;
                if (g.getEdge(edgeId) == null) {
                    try {
                        g.addEdge(edgeId, n.getNodeId(), target);
                        nbEdges++;
                        changed = true;
                    } catch (Exception ignored) {}
                }
            }
        }
        return changed;
    }

    public synchronized boolean mergeMap(SerializableSimpleGraph<String, MapAttribute> received, Map<String, Boolean> receivedScent) {
        boolean changed = mergeMap(received);
        if (receivedScent != null) {
            for (Map.Entry<String, Boolean> e : receivedScent.entrySet()) {
                if (e.getValue() && !wumpusScent.containsKey(e.getKey())) {
                    wumpusScent.put(e.getKey(), true);
                    scentStrength.put(e.getKey(), 50);
                    changed = true;
                }
            }
        }
        return changed;
    }

    public String getAgentName() {
        return agentName;
    }

    public synchronized Graph getGraph() {
        return g;
    }
    
    public synchronized void setWumpusScent(String nodeId, boolean hasScent) {
        wumpusScent.put(nodeId, hasScent);
    }

    public List<String> getShortestPathToClosestOpenNode(String myPosition, List<String> agentNodes) {
        List<String> openNodes = getOpenNodes();
        openNodes.removeAll(agentNodes);
        if (openNodes.isEmpty()) {
            List<String> pos = new ArrayList<>();
            pos.add(myPosition);
            return pos;
        }
        List<Couple<String, Integer>> distances = openNodes.stream()
                .map(on -> {
                    List<String> path = getShortestPath(myPosition, on);
                    int dist = (path == null) ? Integer.MAX_VALUE : path.size();
                    return new Couple<>(on, dist);
                })
                .collect(Collectors.toList());
        Optional<Couple<String, Integer>> closest = distances.stream().min(Comparator.comparing(Couple::getRight));
        return getShortestPath(myPosition, closest.get().getLeft());
    }

    public synchronized List<String> getClosedNodes() {
        return g.nodes()
                .filter(n -> Objects.equals(n.getAttribute("ui.class"), MapAttribute.closed.toString()))
                .map(Node::getId)
                .collect(Collectors.toList());
    }

    public String getRandomNode() {
        List<String> closed = getClosedNodes();
        if (closed.isEmpty()) return null;
        return closed.get(new Random().nextInt(closed.size()));
    }

    public List<String> getOneNodes() {
        List<String> closed = getClosedNodes();
        List<String> result = new ArrayList<>();
        for (String id : closed) {
            if (g.getNode(id).getDegree() == 1) result.add(id);
        }
        return result;
    }

    public String getRandomOneNode() {
        List<String> ones = getOneNodes();
        if (ones.isEmpty()) return getRandomNode();
        return ones.get(new Random().nextInt(ones.size()));
    }

    public double checkTypeGraph() {
        return Toolkit.averageClusteringCoefficient(g);
    }

    public int getContentHash() {
        int hash = getSerializableGraph().hashCode();
        hash = 31 * hash + wumpusScent.hashCode();
        return hash;
    }

    /**
     * Compute articulation points using DFS.
     * Fixed: use neighbor nodes instead of edges to avoid GraphStream API issues.
     */
    public synchronized Set<String> getArticulationPoints() {
        Set<String> visited = new HashSet<>();
        Map<String, Integer> disc = new HashMap<>();
        Map<String, Integer> low = new HashMap<>();
        Map<String, String> parent = new HashMap<>();
        Set<String> aps = new HashSet<>();
        int[] time = new int[]{0};

        for (Node node : g) {
            String u = node.getId();
            if (!visited.contains(u)) {
                dfsAP(u, visited, disc, low, parent, aps, time);
            }
        }
        return aps;
    }

    private void dfsAP(String u, Set<String> visited, Map<String, Integer> disc, Map<String, Integer> low,
            Map<String, String> parent, Set<String> aps, int[] time) {
		int children = 0;
		visited.add(u);
		time[0]++;
		disc.put(u, time[0]);
		low.put(u, time[0]);
		
		Node nodeU = g.getNode(u);
		if (nodeU == null) return;
		
		// Iterate over neighbors directly, avoiding edge objects
		List<Node> neighbors = nodeU.neighborNodes().collect(Collectors.toList());
		for (Node neighbor : neighbors) {
		 String v = neighbor.getId();
		 if (!visited.contains(v)) {
		     children++;
		     parent.put(v, u);
		     dfsAP(v, visited, disc, low, parent, aps, time);
		     low.put(u, Math.min(low.get(u), low.get(v)));
		
		     // Articulation point conditions
		     if (parent.get(u) == null && children > 1) {
		         aps.add(u);
		     }
		     if (parent.get(u) != null && low.get(v) >= disc.get(u)) {
		         aps.add(u);
		     }
		 } else if (!v.equals(parent.get(u))) {
		     low.put(u, Math.min(low.get(u), disc.get(v)));
		 }
		}
}
}