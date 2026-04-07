package eu.su.mas.dedaleEtu.mas.behaviours;

import java.io.IOException;
import java.util.*;
import dataStructures.tuple.Couple;
import eu.su.mas.dedale.env.Location;
import eu.su.mas.dedale.env.Observation;
import eu.su.mas.dedale.env.gs.GsLocation;
import eu.su.mas.dedale.mas.AbstractDedaleAgent;
import eu.su.mas.dedaleEtu.mas.knowledge.MapRepresentation;
import eu.su.mas.dedaleEtu.mas.knowledge.MapRepresentation.MapAttribute;
import eu.su.mas.dedaleEtu.mas.knowledge.MapWithScent;
import jade.core.AID;
import jade.core.behaviours.SimpleBehaviour;
import jade.domain.DFService;
import jade.domain.FIPAAgentManagement.DFAgentDescription;
import jade.domain.FIPAAgentManagement.ServiceDescription;
import jade.domain.FIPAException;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;
import jade.lang.acl.UnreadableException;

public class ExploCoopBehaviour extends SimpleBehaviour {

    private static final long serialVersionUID = 8567689731496787661L;

    private static final String PROTOCOL_SHARE = "SHARE-TOPO";
    private static final String PROTOCOL_TARGET = "HUNT-TARGET";
    private static final String PROTOCOL_CAPTURE = "CAPTURE";
    private static final String PROTOCOL_CONFIRM = "CONFIRM_READY";
    private static final String PROTOCOL_RESERVE = "RESERVE";

    private boolean finished = false;
    private MapRepresentation myMap;
    private ShareMapBehaviour shareBehaviour;
    private Map<String, String> agentPositions;

    private boolean hunting = false;
    private String currentTargetScent = null;
    private Set<String> blockedPositions = new HashSet<>();
    private String myReservedNeighbor = null;
    private long myReservationTime = 0;
    private static final long RESERVATION_TIMEOUT = 5000; // 5秒超时

    private static class Reservation {
        String node;
        long timestamp;
        Reservation(String node, long time) { this.node = node; this.timestamp = time; }
    }
    private Map<String, Reservation> reservedNeighbors = new HashMap<>();

    private final String coordinatorName = "Explo1";

    private int localConfirmCount = 0;
    private static final int LOCAL_CONFIRM_NEEDED = 5;
    private boolean hasSentConfirm = false;

    private Set<String> receivedConfirmations = new HashSet<>();
    private long lastConfirmCheck = 0;
    private static final long CONFIRM_TIMEOUT = 10000;

    private Set<String> allAgents = new HashSet<>();

    public ExploCoopBehaviour(final AbstractDedaleAgent myagent,
                              MapRepresentation myMap,
                              ShareMapBehaviour shareBehaviour,
                              Map<String, String> agentPositions) {
        super(myagent);
        this.myMap = myMap;
        this.shareBehaviour = shareBehaviour;
        this.agentPositions = agentPositions;
    }

    @Override
    public void action() {
        if (finished) return;

        if (checkCaptureMessage()) {
            finished = true;
            return;
        }

        if (myMap == null) {
            myMap = new MapRepresentation(myAgent.getLocalName());
        }
        myMap.showGUI();

        Location myPos = ((AbstractDedaleAgent) myAgent).getCurrentPosition();
        if (myPos == null) return;

        List<Couple<Location, List<Couple<Observation, String>>>> observations =
                ((AbstractDedaleAgent) myAgent).observe();

        try { Thread.sleep(500); } catch (InterruptedException e) {}

        updateTopologyAndScent(myPos, observations);
        receiveSharedMap();
        receiveTargetMessage();
        receiveConfirmations();
        receiveReserveMessages();

        if (myMap.hasOpenNode()) {
            explore(myPos, observations);
            return;
        }

        if (!hunting) {
            hunting = true;
            System.out.println(myAgent.getLocalName() + " exploration done, starting hunt.");
            if (myAgent.getLocalName().equals(coordinatorName)) {
                allAgents = getAllExplorerAgentNames();
                receivedConfirmations.add(coordinatorName);
                System.out.println(coordinatorName + " initialized agent set: " + allAgents);
                selectAndBroadcastTarget();
                lastConfirmCheck = System.currentTimeMillis();
            }
        }

        if (myAgent.getLocalName().equals(coordinatorName)) {
            selectAndBroadcastTarget();
            if (System.currentTimeMillis() - lastConfirmCheck > CONFIRM_TIMEOUT) {
                if (!receivedConfirmations.isEmpty()) {
                    System.out.println(myAgent.getLocalName() + " confirm timeout, resetting confirmations.");
                    receivedConfirmations.clear();
                    receivedConfirmations.add(coordinatorName);
                }
                lastConfirmCheck = System.currentTimeMillis();
            }
        }

        if (currentTargetScent == null) {
            block(500);
            return;
        }

        huntTarget(myPos, observations);
    }

    // ---------- 拓扑更新 ----------
    private void updateTopologyAndScent(Location myPos, List<Couple<Location, List<Couple<Observation, String>>>> obs) {
        String myId = myPos.getLocationId();
        myMap.addNode(myId, MapAttribute.closed);
        shareBehaviour.markUpdate();

        for (int i = 1; i < obs.size(); i++) {
            String nbId = obs.get(i).getLeft().getLocationId();
            boolean isNew = myMap.addNewNode(nbId);
            if (isNew) shareBehaviour.markUpdate();
            myMap.addEdge(myId, nbId);

            boolean hasStench = obs.get(i).getRight().stream()
                    .anyMatch(p -> p.getLeft() == Observation.STENCH);
            if (hasStench) {
                myMap.setWumpusScent(nbId, true);
                shareBehaviour.markUpdate();
                System.out.println(myAgent.getLocalName() + " detected scent at " + nbId);
            }
        }
    }

    // ---------- 探索 ----------
    private void explore(Location myPos, List<Couple<Location, List<Couple<Observation, String>>>> obs) {
        String next = null;
        for (int i = 1; i < obs.size(); i++) {
            String nb = obs.get(i).getLeft().getLocationId();
            if (myMap.hasNode(nb) && myMap.getOpenNodes().contains(nb) && !blockedPositions.contains(nb)) {
                next = nb;
                break;
            }
        }
        if (next == null) {
            List<String> path = myMap.getShortestPathToClosestOpenNode(myPos.getLocationId());
            if (path != null && !path.isEmpty()) next = path.get(0);
        }
        if (next != null) {
            if (!((AbstractDedaleAgent) myAgent).moveTo(new GsLocation(next))) {
                blockedPositions.add(next);
                for (int i = 1; i < obs.size(); i++) {
                    String nb = obs.get(i).getLeft().getLocationId();
                    if (!blockedPositions.contains(nb)) {
                        ((AbstractDedaleAgent) myAgent).moveTo(new GsLocation(nb));
                        break;
                    }
                }
            }
        } else {
            for (int i = 1; i < obs.size(); i++) {
                String nb = obs.get(i).getLeft().getLocationId();
                if (!blockedPositions.contains(nb)) {
                    ((AbstractDedaleAgent) myAgent).moveTo(new GsLocation(nb));
                    break;
                }
            }
        }
    }

    // ---------- 协调者：选择度数最低的气味节点 ----------
    private void selectAndBroadcastTarget() {
        List<String> scentNodes = myMap.getWumpusScentNodes();
        if (scentNodes.isEmpty()) return;
        String best = scentNodes.stream()
                .min(Comparator.comparingInt(n -> myMap.getNeighbors(n).size()))
                .orElse(scentNodes.get(0));
        if (!best.equals(currentTargetScent)) {
            currentTargetScent = best;
            broadcastTarget(currentTargetScent);
            localConfirmCount = 0;
            hasSentConfirm = false;
            myReservedNeighbor = null;
            reservedNeighbors.clear();
            if (myAgent.getLocalName().equals(coordinatorName)) {
                receivedConfirmations.clear();
                receivedConfirmations.add(coordinatorName);
                lastConfirmCheck = System.currentTimeMillis();
            }
            System.out.println(myAgent.getLocalName() + " broadcast target (lowest degree): " + currentTargetScent +
                    " with " + myMap.getNeighbors(currentTargetScent).size() + " neighbors");
        }
    }

    private void broadcastTarget(String target) {
        ACLMessage msg = new ACLMessage(ACLMessage.INFORM);
        msg.setProtocol(PROTOCOL_TARGET);
        msg.setSender(myAgent.getAID());
        for (String name : getAllKnownAgents()) {
            if (!name.equals(myAgent.getLocalName())) {
                msg.addReceiver(new AID(name, AID.ISLOCALNAME));
            }
        }
        try {
            msg.setContentObject(target);
            ((AbstractDedaleAgent) myAgent).sendMessage(msg);
        } catch (IOException e) {}
    }

    private void receiveTargetMessage() {
        MessageTemplate tmpl = MessageTemplate.and(
                MessageTemplate.MatchProtocol(PROTOCOL_TARGET),
                MessageTemplate.MatchPerformative(ACLMessage.INFORM));
        ACLMessage msg = myAgent.receive(tmpl);
        if (msg != null && !myAgent.getLocalName().equals(coordinatorName)) {
            try {
                String newTarget = (String) msg.getContentObject();
                if (!newTarget.equals(currentTargetScent)) {
                    currentTargetScent = newTarget;
                    localConfirmCount = 0;
                    hasSentConfirm = false;
                    myReservedNeighbor = null;
                    reservedNeighbors.clear();
                    System.out.println(myAgent.getLocalName() + " received target: " + currentTargetScent);
                }
            } catch (UnreadableException e) {}
        }
    }

    // ---------- 围堵目标 ----------
    private void huntTarget(Location myPos, List<Couple<Location, List<Couple<Observation, String>>>> obs) {
        String myId = myPos.getLocationId();
        List<String> neighbors = myMap.getNeighbors(currentTargetScent);
        if (neighbors.isEmpty()) {
            captureSuccess(currentTargetScent);
            return;
        }

        // 清理过期的预约（超时）
        long now = System.currentTimeMillis();
        List<String> expired = new ArrayList<>();
        for (Map.Entry<String, Reservation> entry : reservedNeighbors.entrySet()) {
            if (now - entry.getValue().timestamp > RESERVATION_TIMEOUT) {
                expired.add(entry.getKey());
            }
        }
        for (String node : expired) {
            reservedNeighbors.remove(node);
            System.out.println(myAgent.getLocalName() + " removed expired reservation for " + node);
        }

        // 实时占据集合：自己 + 其他代理位置
        Set<String> occupied = new HashSet<>();
        occupied.add(myId);
        occupied.addAll(agentPositions.values());

        boolean currentHasScent = false;
        for (Couple<Location, List<Couple<Observation, String>>> locObs : obs) {
            if (locObs.getLeft().getLocationId().equals(currentTargetScent)) {
                currentHasScent = locObs.getRight().stream()
                        .anyMatch(p -> p.getLeft() == Observation.STENCH);
                break;
            }
        }

        if (occupied.containsAll(neighbors) && currentHasScent) {
            localConfirmCount++;
            System.out.println(myAgent.getLocalName() + " local confirm " + localConfirmCount + "/" + LOCAL_CONFIRM_NEEDED +
                    " for target " + currentTargetScent);
            if (localConfirmCount >= LOCAL_CONFIRM_NEEDED && !hasSentConfirm) {
                if (!myAgent.getLocalName().equals(coordinatorName)) {
                    sendConfirmToCoordinator();
                }
                hasSentConfirm = true;
                System.out.println(myAgent.getLocalName() + " sent CONFIRM to coordinator.");
            }
        } else {
            if (localConfirmCount > 0) {
                System.out.println(myAgent.getLocalName() + " local condition lost, resetting confirm.");
                localConfirmCount = 0;
                hasSentConfirm = false;
            }
        }

        // 协调者检查确认
        if (myAgent.getLocalName().equals(coordinatorName)) {
            if (!allAgents.isEmpty() && receivedConfirmations.containsAll(allAgents)) {
                System.out.println(coordinatorName + " received confirmations from all agents: " + receivedConfirmations);
                captureSuccess(currentTargetScent);
                return;
            }
        }

        // 如果没有完全占据，尝试预约并移动到一个空闲邻居
        if (!occupied.containsAll(neighbors)) {
            // 检查当前预约是否有效
            if (myReservedNeighbor != null) {
                // 如果预约的节点已被占据（别人占了）或已过期，释放
                if (occupied.contains(myReservedNeighbor) || !reservedNeighbors.containsKey(myReservedNeighbor)) {
                    System.out.println(myAgent.getLocalName() + " reservation " + myReservedNeighbor + " invalid, releasing.");
                    reservedNeighbors.remove(myReservedNeighbor);
                    myReservedNeighbor = null;
                }
            }

            if (myReservedNeighbor == null) {
                // 选择一个未被占据且未被预约的邻居
                String candidate = neighbors.stream()
                        .filter(n -> !occupied.contains(n) && !reservedNeighbors.containsKey(n) && !blockedPositions.contains(n))
                        .findFirst()
                        .orElse(null);
                if (candidate != null) {
                    // 尝试预约
                    if (reserveNeighbor(candidate)) {
                        myReservedNeighbor = candidate;
                        myReservationTime = System.currentTimeMillis();
                        System.out.println(myAgent.getLocalName() + " reserved neighbor " + candidate);
                    } else {
                        block(500);
                        return;
                    }
                } else {
                    // 没有可预约的邻居，等待
                    block(500);
                    return;
                }
            }

            // 如果已有预约邻居，移动到它
            if (myReservedNeighbor != null) {
                if (myId.equals(myReservedNeighbor)) {
                    // 已经到达，释放预约并标记占据
                    System.out.println(myAgent.getLocalName() + " reached reserved neighbor " + myReservedNeighbor);
                    reservedNeighbors.remove(myReservedNeighbor);
                    myReservedNeighbor = null;
                    // 广播自己的位置（SharePositionBehaviour 会做，但为了及时，可以手动广播一次）
                    return;
                }
                List<String> path = myMap.getShortestPath(myId, myReservedNeighbor);
                if (path != null && !path.isEmpty()) {
                    String nextStep = path.get(0);
                    boolean moved = ((AbstractDedaleAgent) myAgent).moveTo(new GsLocation(nextStep));
                    if (!moved) {
                        System.out.println(myAgent.getLocalName() + " cannot move to " + nextStep + ", releasing reservation.");
                        // 移动失败，释放预约
                        reservedNeighbors.remove(myReservedNeighbor);
                        myReservedNeighbor = null;
                        try { Thread.sleep(200); } catch (InterruptedException e) {}
                    } else {
                        System.out.println(myAgent.getLocalName() + " moved towards " + myReservedNeighbor);
                    }
                } else {
                    // 无路径，释放预约
                    System.out.println(myAgent.getLocalName() + " no path to " + myReservedNeighbor + ", releasing.");
                    reservedNeighbors.remove(myReservedNeighbor);
                    myReservedNeighbor = null;
                }
            }
        }
    }

    private boolean reserveNeighbor(String node) {
        // 发送预约请求（广播）
        ACLMessage msg = new ACLMessage(ACLMessage.REQUEST);
        msg.setProtocol(PROTOCOL_RESERVE);
        msg.setSender(myAgent.getAID());
        for (String name : getAllKnownAgents()) {
            if (!name.equals(myAgent.getLocalName())) {
                msg.addReceiver(new AID(name, AID.ISLOCALNAME));
            }
        }
        try {
            msg.setContentObject(node);
            ((AbstractDedaleAgent) myAgent).sendMessage(msg);
        } catch (IOException e) {}
        // 将节点加入本地预约集合
        reservedNeighbors.put(node, new Reservation(node, System.currentTimeMillis()));
        return true;
    }

    private void receiveReserveMessages() {
        MessageTemplate tmpl = MessageTemplate.and(
                MessageTemplate.MatchProtocol(PROTOCOL_RESERVE),
                MessageTemplate.MatchPerformative(ACLMessage.REQUEST));
        ACLMessage msg = myAgent.receive(tmpl);
        while (msg != null) {
            try {
                String node = (String) msg.getContentObject();
                // 如果节点未被占据且未被自己预约，则加入预约集合
                String myId = ((AbstractDedaleAgent) myAgent).getCurrentPosition().getLocationId();
                Set<String> occupied = new HashSet<>(agentPositions.values());
                occupied.add(myId);
                if (!occupied.contains(node) && !reservedNeighbors.containsKey(node)) {
                    reservedNeighbors.put(node, new Reservation(node, System.currentTimeMillis()));
                    System.out.println(myAgent.getLocalName() + " added reservation for " + node + " by " + msg.getSender().getLocalName());
                }
            } catch (UnreadableException e) {}
            msg = myAgent.receive(tmpl);
        }
    }

    private void sendConfirmToCoordinator() {
        ACLMessage msg = new ACLMessage(ACLMessage.INFORM);
        msg.setProtocol(PROTOCOL_CONFIRM);
        msg.setSender(myAgent.getAID());
        msg.addReceiver(new AID(coordinatorName, AID.ISLOCALNAME));
        try {
            msg.setContentObject(currentTargetScent);
            ((AbstractDedaleAgent) myAgent).sendMessage(msg);
        } catch (IOException e) {}
    }

    private void receiveConfirmations() {
        MessageTemplate tmpl = MessageTemplate.and(
                MessageTemplate.MatchProtocol(PROTOCOL_CONFIRM),
                MessageTemplate.MatchPerformative(ACLMessage.INFORM));
        ACLMessage msg = myAgent.receive(tmpl);
        while (msg != null) {
            try {
                String sender = msg.getSender().getLocalName();
                String target = (String) msg.getContentObject();
                if (target.equals(currentTargetScent)) {
                    receivedConfirmations.add(sender);
                    lastConfirmCheck = System.currentTimeMillis();
                    System.out.println(myAgent.getLocalName() + " received CONFIRM from " + sender);
                }
            } catch (UnreadableException e) {}
            msg = myAgent.receive(tmpl);
        }
    }

    private void captureSuccess(String golemNode) {
        System.out.println(myAgent.getLocalName() + " *** GOLEM CAPTURED at " + golemNode + " ***");
        broadcastCapture(golemNode);
        finished = true;
    }

    private void broadcastCapture(String golemNode) {
        ACLMessage msg = new ACLMessage(ACLMessage.INFORM);
        msg.setProtocol(PROTOCOL_CAPTURE);
        msg.setSender(myAgent.getAID());
        for (String name : getAllKnownAgents()) {
            if (!name.equals(myAgent.getLocalName())) {
                msg.addReceiver(new AID(name, AID.ISLOCALNAME));
            }
        }
        try {
            msg.setContentObject(golemNode);
            ((AbstractDedaleAgent) myAgent).sendMessage(msg);
        } catch (IOException e) {}
        for (int i = 0; i < 2; i++) {
            try { Thread.sleep(100); } catch (InterruptedException e) {}
            ((AbstractDedaleAgent) myAgent).sendMessage(msg);
        }
    }

    private boolean checkCaptureMessage() {
        MessageTemplate tmpl = MessageTemplate.and(
                MessageTemplate.MatchProtocol(PROTOCOL_CAPTURE),
                MessageTemplate.MatchPerformative(ACLMessage.INFORM));
        ACLMessage msg = myAgent.receive(tmpl);
        if (msg != null) {
            System.out.println(myAgent.getLocalName() + " received CAPTURE from " + msg.getSender().getLocalName());
            return true;
        }
        return false;
    }

    private void receiveSharedMap() {
        MessageTemplate tmpl = MessageTemplate.and(
                MessageTemplate.MatchProtocol(PROTOCOL_SHARE),
                MessageTemplate.MatchPerformative(ACLMessage.INFORM));
        ACLMessage msg = myAgent.receive(tmpl);
        if (msg != null) {
            try {
                Object obj = msg.getContentObject();
                if (obj instanceof MapWithScent) {
                    MapWithScent mws = (MapWithScent) obj;
                    boolean changed = myMap.mergeMap(mws.graph, mws.scent);
                    if (changed) shareBehaviour.markUpdate();
                }
            } catch (UnreadableException e) { e.printStackTrace(); }
        }
    }

    private Set<String> getAllKnownAgents() {
        Set<String> agents = new HashSet<>();
        agents.add(myAgent.getLocalName());
        agents.addAll(agentPositions.keySet());
        return agents;
    }

    private Set<String> getAllExplorerAgentNames() {
        Set<String> agents = new HashSet<>();
        DFAgentDescription template = new DFAgentDescription();
        ServiceDescription sd = new ServiceDescription();
        sd.setType("agentExplo");
        template.addServices(sd);
        try {
            DFAgentDescription[] results = DFService.search(myAgent, template);
            for (DFAgentDescription dfd : results) {
                agents.add(dfd.getName().getLocalName());
            }
        } catch (FIPAException e) {
            e.printStackTrace();
        }
        return agents;
    }

    @Override
    public boolean done() {
        return finished;
    }
}