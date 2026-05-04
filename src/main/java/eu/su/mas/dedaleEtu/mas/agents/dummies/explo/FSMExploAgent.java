package eu.su.mas.dedaleEtu.mas.agents.dummies.explo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import dataStructures.serializableGraph.SerializableSimpleGraph;
import eu.su.mas.dedale.mas.AbstractDedaleAgent;
import eu.su.mas.dedale.mas.agent.behaviours.platformManagment.StartMyBehaviours;
import eu.su.mas.dedaleEtu.mas.behaviours.BlockerBehaviour;
import eu.su.mas.dedaleEtu.mas.behaviours.ExplorationBehaviour;
import eu.su.mas.dedaleEtu.mas.behaviours.HuntBehaviour;
import eu.su.mas.dedaleEtu.mas.behaviours.SignalBehaviour;
import eu.su.mas.dedaleEtu.mas.behaviours.YellowSetupBehaviour;
import eu.su.mas.dedaleEtu.mas.knowledge.GolemInfo;
import eu.su.mas.dedaleEtu.mas.knowledge.MapRepresentation;
import eu.su.mas.dedaleEtu.mas.knowledge.MapRepresentation.MapAttribute;
import jade.core.AID;
import jade.core.behaviours.Behaviour;
import jade.core.behaviours.FSMBehaviour;
import jade.domain.DFService;
import jade.domain.FIPAException;
import jade.domain.FIPAAgentManagement.DFAgentDescription;
import jade.domain.FIPAAgentManagement.ServiceDescription;

public class FSMExploAgent extends AbstractDedaleAgent {

    private static final long serialVersionUID = 830885575375128225L;

    // State constants
    public static final int MODE_EXPLORATION = 0;
    public static final int MODE_HUNT = 1;
    public static final int MODE_BLOCKING = 2;
    public static final int MODE_CAPTURED = 3;

    private MapRepresentation myMap;
    private int mode = MODE_EXPLORATION;
    private int style;     // 0 : TreeHunt, 1 : GraphHunt
    private int lastMapHash = 0;

    private boolean moved = true;
    private String lastPosition = "";

    // Destination flags
    private boolean dest_wumpusfound = false;
    private boolean dest_interblocage = false;
    private boolean dest_alea = false;
    private boolean dest_stench = false;
    private boolean dest_insideStench = false;

    // Counters
    private int wait;
    private int wumpusCnt;
    private int getoutCnt;

    // Observations
    private List<String> openNextNodes = new ArrayList<>();
    private List<String> nextNodes = new ArrayList<>();
    private List<String> stenchNodes = new ArrayList<>();

    // Informations
    private String destination = null;
    private String nextDest;
    private List<String> position = new ArrayList<>();
    private List<String> stenchDirection = new ArrayList<>();
    private List<String> insideStench = new ArrayList<>();
    private String ownStenchDirection;
    private String ownInsideStench;

    // ========== MULTI-GOLEM MANAGEMENT ==========
    private final Map<String, GolemInfo> knownGolems = new ConcurrentHashMap<>();
    private String currentTargetGolemId = null;
    private final Set<String> capturedGolems = Collections.synchronizedSet(new HashSet<>());
    // ===========================================

    // Communication range
    private int communicationRange = 0;

    // Blocking mode variables
    private String blockingNode = null;
    private Set<String> blockingTargets = Collections.synchronizedSet(new HashSet<>());

    // ========== MESSAGE DEDUPLICATION ==========
    private final Set<String> receivedPositionMsgIds = Collections.synchronizedSet(new HashSet<>());
    private final Set<String> receivedGolemInfoMsgIds = Collections.synchronizedSet(new HashSet<>());
    private final Set<String> receivedStenchMsgIds = Collections.synchronizedSet(new HashSet<>());
    private int positionMsgSeq = 0;
    // ===========================================

    // ========== BLOCKING NEGOTIATION (2PC Enhanced) ==========
    public boolean isManager = false;
    public String activeCFPGolemId = null;
    public long cfpStartTime = 0;
    public static final long CFP_TIMEOUT = 1500;
    public static final long AWARD_ACK_TIMEOUT = 2000;
    
    // Track assigned blockers for ACK
    public Map<String, Boolean> expectedAcks = Collections.synchronizedMap(new HashMap<>());
    public Map<String, String> assignedBlockers = Collections.synchronizedMap(new HashMap<>()); // agentName -> node
    // ===========================================

    // ========== AGENT POSITION TRACKING ==========
    public final Map<String, String> agentPositions = new ConcurrentHashMap<>();
    private final Map<String, Long> positionTimestamps = new ConcurrentHashMap<>();
    private static final long POSITION_EXPIRY_MS = 15000;
    // ===========================================

    @Override
    protected void setup() {
        super.setup();

        Object[] args = getArguments();
        if (args != null && args.length > 0 && args[0] instanceof eu.su.mas.dedale.env.EntityCharacteristics) {
            eu.su.mas.dedale.env.EntityCharacteristics ec = (eu.su.mas.dedale.env.EntityCharacteristics) args[0];
            this.communicationRange = ec.getCommunicationReach();
        }

        List<Behaviour> lb = new ArrayList<>();

        final String YellowSetup = "YellowSetup";
        final String Exploration = "Exploration";
        final String Hunt = "Hunt";
        final String Blocking = "Blocking";
        final String Signal = "Signal";
        final String CheckMode = "CheckMode";

        FSMBehaviour fsm = new FSMBehaviour(this);

        fsm.registerFirstState(new YellowSetupBehaviour(this, "Explorer"), YellowSetup);
        fsm.registerState(new ExplorationBehaviour(this), Exploration);
        fsm.registerState(new HuntBehaviour(this), Hunt);
        fsm.registerState(new BlockerBehaviour(this), Blocking);
        fsm.registerState(new SignalBehaviour(this), Signal);
        fsm.registerState(new CheckModeBehaviour(), CheckMode);

        fsm.registerDefaultTransition(YellowSetup, Exploration);
        fsm.registerTransition(Exploration, Signal, 0);
        fsm.registerTransition(Hunt, Signal, 0);
        fsm.registerTransition(Blocking, Signal, 0);
        fsm.registerDefaultTransition(Signal, CheckMode);
        fsm.registerTransition(CheckMode, Exploration, 0);
        fsm.registerTransition(CheckMode, Hunt, 1);
        fsm.registerTransition(CheckMode, Blocking, 2);

        lb.add(fsm);
        addBehaviour(new StartMyBehaviours(this, lb));

        System.out.println("Agent " + getLocalName() + " started with multi-golem support. CommRange=" + communicationRange);
    }

    @Override
    protected void takeDown() {
        try {
            super.takeDown();
        } catch (NullPointerException e) {
            System.err.println("Agent " + getLocalName() + " died before environment setup, skipping cleanup.");
        }
    }

    private class CheckModeBehaviour extends Behaviour {
        private static final long serialVersionUID = 1L;
        private boolean finished = false;

        @Override
        public void action() {
            finished = true;
        }

        @Override
        public boolean done() {
            return finished;
        }

        @Override
        public int onEnd() {
            int currentMode = getMode();
            if (currentMode == MODE_EXPLORATION) return 0;
            else if (currentMode == MODE_HUNT) return 1;
            else return 2;
        }
    }

    // ===================== Helper Methods =====================
    public AID[] getServices(String service) {
        DFAgentDescription dfd = new DFAgentDescription();
        ServiceDescription sd = new ServiceDescription();
        sd.setType(service);
        dfd.addServices(sd);
        AID[] agentsAID = null;
        try {
            DFAgentDescription[] result = DFService.search(this, dfd);
            agentsAID = new AID[result.length];
            for (int i = 0; i < result.length; ++i) {
                agentsAID[i] = result[i].getName();
            }
        } catch (FIPAException e) {
            e.printStackTrace();
        }
        return agentsAID;
    }

    public String dataToString(List<String> data) {
        return String.join(",", data);
    }

    public List<String> stringToData(String string) {
        return Stream.of(string.split(",", -1)).collect(Collectors.toList());
    }

    public int getCommunicationRange() {
        return communicationRange;
    }

    public boolean isWithinCommunicationRange(String myPos, String targetNode) {
        if (communicationRange <= 0) return true;
        if (myPos == null || targetNode == null) return false;
        if (myPos.equals(targetNode)) return true;
        List<String> path = myMap.getShortestPath(myPos, targetNode);
        return path != null && path.size() <= communicationRange;
    }

    public boolean isWithinCommunicationRange(String targetNode) {
        String myPos = getCurrentPosition().getLocationId();
        return isWithinCommunicationRange(myPos, targetNode);
    }

    // ===================== Message ID Generation =====================
    public String generatePositionMsgId() {
        return getLocalName() + "-POS-" + System.currentTimeMillis() + "-" + (++positionMsgSeq);
    }

    public String generateGolemInfoMsgId() {
        return getLocalName() + "-GOLEM-" + System.currentTimeMillis();
    }

    public String generateStenchMsgId(String type) {
        return getLocalName() + "-STENCH-" + type + "-" + System.currentTimeMillis();
    }

    public Set<String> getReceivedPositionMsgIds() {
        return receivedPositionMsgIds;
    }

    public Set<String> getReceivedGolemInfoMsgIds() {
        return receivedGolemInfoMsgIds;
    }

    public Set<String> getReceivedStenchMsgIds() {
        return receivedStenchMsgIds;
    }

    // ===================== Agent Position Tracking =====================
    public void updateAgentPosition(String agentName, String nodeId) {
        agentPositions.put(agentName, nodeId);
        positionTimestamps.put(agentName, System.currentTimeMillis());
    }

    public Set<String> getKnownAgentPositions() {
        long now = System.currentTimeMillis();
        positionTimestamps.entrySet().removeIf(e -> now - e.getValue() > POSITION_EXPIRY_MS);
        agentPositions.keySet().retainAll(positionTimestamps.keySet());
        return new HashSet<>(agentPositions.values());
    }

    public String getAgentPosition(String agentName) {
        return agentPositions.get(agentName);
    }

    // ===================== Getters and Setters =====================
    public int getWait() { return wait; }
    public void decreaseWait() { this.wait--; }
    public void setWait(int wait) { this.wait = wait; }

    public MapRepresentation getMyMap() { return myMap; }
    public void setMyMap(MapRepresentation myMap) { this.myMap = myMap; }
    public void initiateMyMap() {
        this.myMap = new MapRepresentation(getLocalName());
        this.myMap.showGUI();
    }
    public void myMapAddNode(String node, MapAttribute attr) { myMap.addNode(node, attr); }
    public boolean myMapAddNewNode(String id) { return myMap.addNewNode(id); }
    public void myMapAddEdge(String id1, String id2) { myMap.addEdge(id1, id2); }
    public List<String> myMapShortestPathToClosestOpenNode(String pos, Set<String> set) {
        return myMap.getShortestPathToClosestOpenNode(pos, set);
    }
    public SerializableSimpleGraph<String, MapAttribute> getMyMapSerial() {
        return myMap.getSerializableGraph();
    }

    public int getLastMapHash() { return lastMapHash; }
    public void setLastMapHash(int hash) { this.lastMapHash = hash; }

    public List<String> getPosition() { return position; }
    public void cleanPosition() { position.clear(); }
    public void addPosition(String node) { position.add(node); }
    public void setPosition(List<String> position) { this.position = position; }

    public String getOwnStenchDirection() { return ownStenchDirection; }
    public void setOwnStenchDirection(String s) { ownStenchDirection = s; }
    public String getOwnInsideStench() { return ownInsideStench; }
    public void setOwnInsideStench(String s) { ownInsideStench = s; }

    public List<String> getStenchDirection() { return stenchDirection; }
    public void cleanStenchDirection() { stenchDirection.clear(); }
    public void addStenchDirection(String node) { stenchDirection.add(node); }
    public void setStenchDirection(List<String> list) { this.stenchDirection = list; }

    public List<String> getInsideStench() { return insideStench; }
    public void cleanInsideStench() { insideStench.clear(); }
    public void addInsideStench(String node) { insideStench.add(node); }
    public void setInsideStench(List<String> list) { this.insideStench = list; }

    public List<String> getOpenNextNodes() { return openNextNodes; }
    public void cleanOpenNextNodes() { openNextNodes.clear(); }
    public void addOpenNextNodes(String node) { openNextNodes.add(node); }

    public List<String> getNextNodes() { return nextNodes; }
    public void cleanNextNodes() { nextNodes.clear(); }
    public void addNextNodes(String node) { nextNodes.add(node); }
    public void removeNextNodes(String node) { nextNodes.remove(node); }

    public List<String> getStenchNodes() { return stenchNodes; }
    public void cleanStenchNodes() { stenchNodes.clear(); }
    public void addStenchNodes(String node) { stenchNodes.add(node); }

    public synchronized int getMode() { return mode; }
    public synchronized void setMode(int mode) { this.mode = mode; }

    public void setDestinationInterblocage(boolean t) { dest_interblocage = t; }
    public boolean getDestinationInterblocage() { return dest_interblocage; }

    public void setDestinationAlea(boolean t) { dest_alea = t; }
    public boolean getDestinationAlea() { return dest_alea; }

    public void setDestinationStench(boolean t) { dest_stench = t; }
    public boolean getDestinationStench() { return dest_stench; }

    public void setDestinationInsideStench(boolean t) { dest_insideStench = t; }
    public boolean getDestinationInsideStench() { return dest_insideStench; }

    public String getLastPosition() { return lastPosition; }
    public void setLastPosition(String lastPosition) { this.lastPosition = lastPosition; }

    public boolean getMoved() { return moved; }
    public void setMoved(boolean moved) { this.moved = moved; }

    public String getDestination() { return destination; }
    public void setDestination(String destination) { this.destination = destination; }

    public int getStyle() { return style; }
    public void setStyle(int style) { this.style = style; }

    public String getNextDest() { return nextDest; }
    public void setNextDest(String nextDest) { this.nextDest = nextDest; }

    public int getWumpusCnt() { return wumpusCnt; }
    public void increaseWumpusCnt() { wumpusCnt++; }
    public void setWumpusCnt(int cnt) { this.wumpusCnt = cnt; }

    public boolean isDest_wumpusfound() { return dest_wumpusfound; }
    public void setDest_wumpusfound(boolean b) { dest_wumpusfound = b; }

    public int getGetoutCnt() { return getoutCnt; }
    public void increaseGetoutCnt() { getoutCnt++; }
    public void setGetoutCnt(int cnt) { this.getoutCnt = cnt; }

    // ========== MULTI-GOLEM METHODS ==========
    public synchronized void addOrUpdateGolem(String golemId, String position, boolean confirmed) {
        GolemInfo existing = knownGolems.get(golemId);
        long now = System.currentTimeMillis();

        if (existing == null) {
            GolemInfo newInfo = new GolemInfo(golemId, position, confirmed);
            System.out.println(getLocalName() + " discovered new Golem: " + golemId + " at " + position
                    + " (confirmed=" + confirmed + ")");
            knownGolems.put(golemId, newInfo);
            return;
        }

        boolean shouldUpdate = false;

        if (confirmed && !existing.isConfirmed()) {
            shouldUpdate = true;
        }
        else if (confirmed && existing.isConfirmed() && now > existing.getTimestamp()) {
            shouldUpdate = true;
        }
        else if (!confirmed && !existing.isConfirmed() && now > existing.getTimestamp()) {
            shouldUpdate = true;
        }

        if (shouldUpdate) {
            existing.updatePosition(position);
            if (confirmed) {
                existing.setConfirmed();
            }
            existing.setTimestamp(now);
            System.out.println(getLocalName() + " updated Golem " + golemId + " to " + position
                    + " (confirmed=" + existing.isConfirmed() + ", timestamp=" + now + ")");
        } else {
            System.out.println(getLocalName() + " ignored stale/unconfirmed update for Golem " + golemId
                    + " from " + position);
        }
    }

    public synchronized void markGolemCaptured(String golemId) {
        capturedGolems.add(golemId);
        knownGolems.remove(golemId);
        if (currentTargetGolemId != null && currentTargetGolemId.equals(golemId)) {
            currentTargetGolemId = null;
        }
        // Clear any pending blocking tasks for this golem
        blockingTargets.remove(golemId);
        if (blockingTargets.isEmpty()) {
            blockingNode = null;
        }
        System.out.println(getLocalName() + " Golem CAPTURED: " + golemId);
        if (knownGolems.isEmpty() && myMap != null && !myMap.hasOpenNode()) {
            mode = MODE_CAPTURED;
            System.out.println(getLocalName() + " ALL GOLEMS CAPTURED! Mission complete.");
        }
    }

    public GolemInfo selectBestTarget(String myPos) {
        if (knownGolems.isEmpty()) return null;
        return knownGolems.values().stream()
                .filter(g -> !capturedGolems.contains(g.getId()))
                .min(Comparator.comparingInt(g -> {
                    List<String> path = myMap.getShortestPath(myPos, g.getLastKnownPosition());
                    return path == null ? Integer.MAX_VALUE : path.size();
                }))
                .orElse(null);
    }

    public Map<String, GolemInfo> getKnownGolems() {
        return knownGolems;
    }

    public String getCurrentTargetGolemId() {
        return currentTargetGolemId;
    }

    public void setCurrentTargetGolemId(String id) {
        this.currentTargetGolemId = id;
    }

    public Set<String> getCapturedGolems() {
        return capturedGolems;
    }

    public String getBlockingNode() { return blockingNode; }
    public void setBlockingNode(String node) { this.blockingNode = node; }
    public Set<String> getBlockingTargets() { return blockingTargets; }
    public void addBlockingTarget(String golemId) { blockingTargets.add(golemId); }
    public void clearBlockingTargets() { blockingTargets.clear(); }
    
 // ========== BLACKLIST / FAILED MOVE MANAGEMENT ==========
    private Set<String> blockedNodes = Collections.synchronizedSet(new HashSet<>());
    private Map<String, Long> blockedTimestamps = new ConcurrentHashMap<>();
    private static final long BLOCKED_EXPIRY_MS = 8000; // 8 seconds

    private String lastFailedNode = null;
    private String lastFailedNodePosition = null;

    public boolean isNodeBlocked(String nodeId) {
        Long ts = blockedTimestamps.get(nodeId);
        if (ts == null) return false;
        if (System.currentTimeMillis() - ts > BLOCKED_EXPIRY_MS) {
            blockedNodes.remove(nodeId);
            blockedTimestamps.remove(nodeId);
            return false;
        }
        return true;
    }

    public void addBlockedNode(String nodeId) {
        if (blockedTimestamps.containsKey(nodeId)) {
            // Already blocked, just refresh timestamp to avoid log spam
            blockedTimestamps.put(nodeId, System.currentTimeMillis());
            return;
        }
        blockedNodes.add(nodeId);
        blockedTimestamps.put(nodeId, System.currentTimeMillis());
        System.out.println(getLocalName() + " [BLACKLIST] add " + nodeId);
    }

    public void cleanBlockedNodes() {
        long now = System.currentTimeMillis();
        blockedTimestamps.entrySet().removeIf(e -> now - e.getValue() > BLOCKED_EXPIRY_MS);
        blockedNodes.retainAll(blockedTimestamps.keySet());
    }

    public void setLastFailedNode(String node, String myPos) {
        this.lastFailedNode = node;
        this.lastFailedNodePosition = myPos;
    }

    public String getLastFailedNode() { return lastFailedNode; }
    public String getLastFailedNodePosition() { return lastFailedNodePosition; }
    // ==========================================================
}