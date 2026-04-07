package eu.su.mas.dedaleEtu.mas.knowledge;

import java.io.Serializable;
import java.util.*;
import java.util.stream.Collectors;

import org.graphstream.algorithm.Dijkstra;
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
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.layout.AnchorPane;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

/**
 * Map representation class containing topology, scent information, GUI display, path planning, etc.
 * Supports serialization, merging, scent decay and Golem position prediction.
 */
public class MapRepresentation implements Serializable {

    public enum MapAttribute { agent, open, closed }

    private static final long serialVersionUID = -1333959882640838272L;

    // Graph structure (non‑serializable, rebuilt on migration)
    private transient Graph g;
    private transient Viewer viewer;
    private transient volatile boolean guiShown = false;
    private Integer nbEdges;
    private String agentName;

    // Serializable version of the map (used for transmission and recovery)
    private SerializableSimpleGraph<String, MapAttribute> sg;
    private Map<String, Boolean> serializableScent;   // serialized backup

    // Scent strength (non‑serializable, runtime only)
    private transient Map<String, Integer> scentStrength;
    private static final int SCENT_DECAY_PER_STEP = 10;
    private static final int SCENT_THRESHOLD = 20;
    private transient Set<String> lastGolemPositions;

    // Traditional boolean scent (for compatibility with older code)
    private transient Map<String, Boolean> wumpusScent;

    // GUI style
    private final String nodeStyle = "node {" +
            "fill-color: black; size-mode:fit;text-alignment:under; text-size:14;text-color:white;text-background-mode:rounded-box;text-background-color:black;}" +
            "node.open {fill-color: forestgreen;}" +
            "node.agent {fill-color: blue;}";

    // ----------------------------------------------------------------------
    // Construction & Initialization
    // ----------------------------------------------------------------------
    public MapRepresentation(String agentName) {
        System.setProperty("org.graphstream.ui", "javafx");
        this.g = new SingleGraph("My world vision");
        this.g.setAttribute("ui.stylesheet", nodeStyle);
        this.agentName = agentName;
        this.nbEdges = 0;
        this.wumpusScent = new HashMap<>();
        this.scentStrength = new HashMap<>();
        this.lastGolemPositions = new HashSet<>();
        this.guiShown = false;
    }

    // ----------------------------------------------------------------------
    // GUI Management
    // ----------------------------------------------------------------------
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

    // ----------------------------------------------------------------------
    // Node & Edge Operations
    // ----------------------------------------------------------------------
    public synchronized void addNode(String id, MapAttribute attr) {
        Node n = g.getNode(id);
        if (n == null) n = g.addNode(id);
        n.clearAttributes();
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
        // Avoid duplicate edges
        String edgeId = id1 + "-" + id2;
        if (g.getEdge(edgeId) == null) {
            try {
                g.addEdge(edgeId, id1, id2);
                nbEdges++;
            } catch (Exception e) {
                // edge may already exist
            }
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

    // ----------------------------------------------------------------------
    // Scent Management (with decay)
    // ----------------------------------------------------------------------
    /**
     * Sets the scent strength for a given node (0 means no scent).
     */
    public synchronized void setScentStrength(String nodeId, int strength) {
        if (strength <= 0) {
            scentStrength.remove(nodeId);
            wumpusScent.remove(nodeId);
        } else {
            scentStrength.put(nodeId, strength);
            wumpusScent.put(nodeId, true);
        }
    }

    /**
     * Increases the scent at a node (cumulative, maximum 100).
     */
    public synchronized void addScent(String nodeId, int increment) {
        int old = scentStrength.getOrDefault(nodeId, 0);
        int newStrength = Math.min(100, old + increment);
        setScentStrength(nodeId, newStrength);
    }

    /**
     * Applies a decay step to all scents.
     */
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

    /**
     * Traditional boolean scent (for compatibility with older code).
     */
    public synchronized void setWumpusScent(String nodeId, boolean hasScent) {
        if (hasScent) {
            if (!wumpusScent.containsKey(nodeId)) {
                wumpusScent.put(nodeId, true);
                scentStrength.putIfAbsent(nodeId, 50);
            }
        } else {
            wumpusScent.remove(nodeId);
            scentStrength.remove(nodeId);
        }
    }

    public synchronized boolean hasWumpusScent(String nodeId) {
        return wumpusScent.getOrDefault(nodeId, false);
    }

    public synchronized List<String> getWumpusScentNodes() {
        return new ArrayList<>(wumpusScent.keySet());
    }

    /**
     * Returns a map of all scent nodes with their strength.
     */
    public synchronized Map<String, Integer> getScentStrengthMap() {
        return new HashMap<>(scentStrength);
    }

    // ----------------------------------------------------------------------
    // Golem Position Prediction (based on scent and neighbor propagation)
    // ----------------------------------------------------------------------
    /**
     * Predicts the possible set of Golem positions.
     * @return set of predicted node IDs
     */
    public synchronized Set<String> predictGolemPositions() {
        Set<String> candidates = new HashSet<>(getWumpusScentNodes());
        // If no scent, consider neighbors of last known positions (simulate movement)
        if (candidates.isEmpty() && !lastGolemPositions.isEmpty()) {
            for (String pos : lastGolemPositions) {
                candidates.addAll(getNeighbors(pos));
            }
        }
        if (candidates.isEmpty()) {
            return candidates;
        }
        // Store this prediction for next use
        lastGolemPositions = new HashSet<>(candidates);
        return candidates;
    }

    /**
     * Updates the scent based on actual observations (external call, also decays).
     * @param observedScentNodes nodes where scent was observed in the current observation
     */
    public synchronized void updateScentFromObservation(Set<String> observedScentNodes) {
        // first decay all scents
        decayScent();
        // add newly observed scents (strength +30)
        for (String node : observedScentNodes) {
            addScent(node, 30);
        }
    }

    // ----------------------------------------------------------------------
    // Path Planning
    // ----------------------------------------------------------------------
    public synchronized List<String> getShortestPath(String from, String to) {
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
        // return nodes after the start
        return path.stream().skip(1).map(Node::getId).collect(Collectors.toList());
    }

    public synchronized List<String> getShortestPathToClosestOpenNode(String pos) {
        List<String> open = getOpenNodes();
        if (open.isEmpty()) return null;
        Optional<String> closest = open.stream()
                .min(Comparator.comparingInt(n -> {
                    List<String> p = getShortestPath(pos, n);
                    return p == null ? Integer.MAX_VALUE : p.size();
                }));
        return closest.map(c -> getShortestPath(pos, c)).orElse(null);
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

    // ----------------------------------------------------------------------
    // Serialization & Migration
    // ----------------------------------------------------------------------
    public synchronized SerializableSimpleGraph<String, MapAttribute> getSerializableGraph() {
        serialize();
        return sg;
    }

    public synchronized Map<String, Boolean> getSerializableScent() {
        Map<String, Boolean> boolScent = new HashMap<>();
        for (String node : wumpusScent.keySet()) {
            boolScent.put(node, true);
        }
        return boolScent;
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
        // Save scent: store nodes with scent in serializableScent (boolean)
        serializableScent = new HashMap<>();
        for (String node : wumpusScent.keySet()) {
            serializableScent.put(node, true);
        }
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
        // Restore scent: rebuild wumpusScent and scentStrength from serializableScent
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
        // do not automatically show GUI
    }

    // ----------------------------------------------------------------------
    // Map Merging
    // ----------------------------------------------------------------------
    /**
     * Merges a received map (including topology and scent).
     * @param received serialized graph
     * @param receivedScent boolean scent map
     * @return true if any change occurred
     */
    public synchronized boolean mergeMap(SerializableSimpleGraph<String, MapAttribute> received, Map<String, Boolean> receivedScent) {
        boolean changed = false;
        // merge nodes
        for (SerializableNode<String, MapAttribute> n : received.getAllNodes()) {
            Node existing = g.getNode(n.getNodeId());
            if (existing == null) {
                addNode(n.getNodeId(), n.getNodeContent());
                changed = true;
            } else {
                String cur = (String) existing.getAttribute("ui.class");
                // upgrade from open to closed if received is closed
                if (cur.equals(MapAttribute.open.toString()) && n.getNodeContent() == MapAttribute.closed) {
                    existing.setAttribute("ui.class", MapAttribute.closed.toString());
                    changed = true;
                }
            }
        }
        // merge edges
        for (SerializableNode<String, MapAttribute> n : received.getAllNodes()) {
            for (String s : received.getEdges(n.getNodeId())) {
                String edgeId = n.getNodeId() + "-" + s;
                if (g.getEdge(edgeId) == null) {
                    try {
                        g.addEdge(edgeId, n.getNodeId(), s);
                        nbEdges++;
                        changed = true;
                    } catch (Exception ignored) {}
                }
            }
        }
        // merge scents
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

    public synchronized boolean mergeMap(SerializableSimpleGraph<String, MapAttribute> received) {
        return mergeMap(received, null);
    }

    // ----------------------------------------------------------------------
    // Helper Methods
    // ----------------------------------------------------------------------
    public String getAgentName() {
        return agentName;
    }

    /**
     * Returns a read‑only copy of the current graph (for debugging).
     */
    public synchronized Graph getGraph() {
        return g;
    }
}