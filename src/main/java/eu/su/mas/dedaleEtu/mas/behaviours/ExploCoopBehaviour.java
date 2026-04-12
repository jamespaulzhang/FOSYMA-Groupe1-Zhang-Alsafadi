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
    private static final String PROTOCOL_GOLEM_POS = "GOLEM-POS";
    private static final String PROTOCOL_CAPTURE = "CAPTURE";

    private boolean finished = false;
    private MapRepresentation myMap;
    private ShareMapBehaviour shareBehaviour;

    private boolean hunting = false;
    private String currentTarget = null;
    private Set<String> blockedPositions = new HashSet<>();

    private final String coordinatorName = "Explo1";
    private long lastTargetUpdate = 0;
    private static final long TARGET_UPDATE_INTERVAL = 5000;

    private Random random = new Random();

    public ExploCoopBehaviour(final AbstractDedaleAgent myagent,
                              MapRepresentation myMap,
                              ShareMapBehaviour shareBehaviour,
                              Map<String, String> agentPositions) {
        super(myagent);
        this.myMap = myMap;
        this.shareBehaviour = shareBehaviour;
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

        try { Thread.sleep(300); } catch (InterruptedException e) {}

        updateTopologyAndScent(myPos, observations);
        receiveSharedMap();
        receiveTargetMessage();
        receiveGolemPositionMessage();

        if (myMap.hasOpenNode()) {
            explore(myPos, observations);
            return;
        }

        if (!hunting) {
            hunting = true;
            System.out.println(myAgent.getLocalName() + " exploration done, starting hunt.");
            if (myAgent.getLocalName().equals(coordinatorName)) {
                selectAndBroadcastTarget();
                lastTargetUpdate = System.currentTimeMillis();
            }
        }

        // 每个 Agent 定期观察相邻节点，直接发现 Golem
        String golemPos = detectGolemDirectly(observations);
        if (golemPos != null && (currentTarget == null || !currentTarget.equals(golemPos))) {
            broadcastGolemPosition(golemPos);
            if (myAgent.getLocalName().equals(coordinatorName)) {
                currentTarget = golemPos;
                broadcastTarget(currentTarget);
            }
        }

        if (currentTarget == null) {
            block(500);
            return;
        }

        // 协调者定期重新选择目标
        if (myAgent.getLocalName().equals(coordinatorName)) {
            long now = System.currentTimeMillis();
            if (now - lastTargetUpdate >= TARGET_UPDATE_INTERVAL) {
                selectAndBroadcastTarget();
                lastTargetUpdate = now;
            }
        }

        // 捕获判定：只有通过实时观察确认所有邻居都被占据才宣布
        if (isTargetBlockedByObservation(observations)) {
            captureSuccess(currentTarget);
            return;
        }

        huntTarget(myPos, observations);
    }

    // ---------- 拓扑和气味更新 ----------
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

    // ---------- 直接观察 Golem ----------
    private String detectGolemDirectly(List<Couple<Location, List<Couple<Observation, String>>>> obs) {
        for (int i = 1; i < obs.size(); i++) {
            String nodeId = obs.get(i).getLeft().getLocationId();
            boolean hasGolem = obs.get(i).getRight().stream()
                    .anyMatch(p -> p.getLeft() == Observation.AGENTNAME && "Golem".equals(p.getRight()));
            if (hasGolem) {
                System.out.println(myAgent.getLocalName() + " directly spotted Golem at " + nodeId);
                return nodeId;
            }
        }
        return null;
    }

    private void broadcastGolemPosition(String golemNode) {
        ACLMessage msg = new ACLMessage(ACLMessage.INFORM);
        msg.setProtocol(PROTOCOL_GOLEM_POS);
        msg.setSender(myAgent.getAID());
        for (AID aid : getAllExplorerAgents()) {
            if (!aid.equals(myAgent.getAID())) {
                msg.addReceiver(aid);
            }
        }
        try {
            msg.setContentObject(golemNode);
            ((AbstractDedaleAgent) myAgent).sendMessage(msg);
        } catch (IOException e) {}
        System.out.println(myAgent.getLocalName() + " broadcast Golem position: " + golemNode);
    }

    private void receiveGolemPositionMessage() {
        MessageTemplate tmpl = MessageTemplate.and(
                MessageTemplate.MatchProtocol(PROTOCOL_GOLEM_POS),
                MessageTemplate.MatchPerformative(ACLMessage.INFORM));
        ACLMessage msg = myAgent.receive(tmpl);
        while (msg != null) {
            try {
                String golemPos = (String) msg.getContentObject();
                if (currentTarget == null || !currentTarget.equals(golemPos)) {
                    currentTarget = golemPos;
                    System.out.println(myAgent.getLocalName() + " learned Golem position: " + golemPos);
                    blockedPositions.clear();
                }
            } catch (UnreadableException e) {}
            msg = myAgent.receive(tmpl);
        }
    }

    // ---------- 目标选择 ----------
    private String inferGolemFromScent() {
        List<String> scentNodes = myMap.getWumpusScentNodes();
        if (scentNodes.isEmpty()) return null;
        Set<String> candidates = null;
        for (String scent : scentNodes) {
            Set<String> neighbors = new HashSet<>(myMap.getNeighbors(scent));
            if (candidates == null) {
                candidates = neighbors;
            } else {
                candidates.retainAll(neighbors);
            }
        }
        if (candidates != null && !candidates.isEmpty()) {
            // 选择度数最低的候选（更容易封锁）
            return candidates.stream()
                    .min(Comparator.comparingInt(n -> myMap.getNeighbors(n).size()))
                    .orElse(candidates.iterator().next());
        }
        return null;
    }

    private void selectAndBroadcastTarget() {
        String newTarget = inferGolemFromScent();
        if (newTarget == null) {
            // 后备：选择气味节点中邻居最少的
            List<String> scentNodes = myMap.getWumpusScentNodes();
            if (!scentNodes.isEmpty()) {
                newTarget = scentNodes.stream()
                        .min(Comparator.comparingInt(n -> myMap.getNeighbors(n).size()))
                        .orElse(scentNodes.get(0));
                System.out.println(coordinatorName + " fallback: using scent node " + newTarget);
            }
        }
        if (newTarget == null || newTarget.equals(currentTarget)) return;
        currentTarget = newTarget;
        System.out.println(coordinatorName + " broadcast target: " + currentTarget +
                " with " + myMap.getNeighbors(currentTarget).size() + " neighbours");
        broadcastTarget(currentTarget);
        blockedPositions.clear();
    }

    private void broadcastTarget(String target) {
        ACLMessage msg = new ACLMessage(ACLMessage.INFORM);
        msg.setProtocol(PROTOCOL_TARGET);
        msg.setSender(myAgent.getAID());
        for (AID aid : getAllExplorerAgents()) {
            if (!aid.equals(myAgent.getAID())) {
                msg.addReceiver(aid);
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
                if (!newTarget.equals(currentTarget)) {
                    currentTarget = newTarget;
                    System.out.println(myAgent.getLocalName() + " received target: " + currentTarget);
                    blockedPositions.clear();
                }
            } catch (UnreadableException e) {}
        }
    }

    // ---------- 围堵 ----------
    private void huntTarget(Location myPos, List<Couple<Location, List<Couple<Observation, String>>>> obs) {
        String myId = myPos.getLocationId();
        List<String> neighbours = myMap.getNeighbors(currentTarget);
        if (neighbours.isEmpty()) {
            captureSuccess(currentTarget);
            return;
        }

        // 实时观察哪些邻居已经被其他 Agent 占据
        Set<String> occupiedNeighbours = new HashSet<>();
        for (int i = 1; i < obs.size(); i++) {
            String nodeId = obs.get(i).getLeft().getLocationId();
            if (neighbours.contains(nodeId)) {
                boolean hasAgent = obs.get(i).getRight().stream()
                        .anyMatch(p -> p.getLeft() == Observation.AGENTNAME);
                if (hasAgent) {
                    occupiedNeighbours.add(nodeId);
                }
            }
        }
        if (neighbours.contains(myId)) occupiedNeighbours.add(myId);

        // 选择一个未被占据的邻居
        String targetNeighbour = null;
        for (String nb : neighbours) {
            if (!occupiedNeighbours.contains(nb) && !blockedPositions.contains(nb)) {
                targetNeighbour = nb;
                break;
            }
        }
        if (targetNeighbour == null) {
            // 所有邻居都被占据，等待捕获
            block(500);
            return;
        }
        if (myId.equals(targetNeighbour)) return;

        List<String> path = myMap.getShortestPath(myId, targetNeighbour);
        if (path != null && !path.isEmpty()) {
            String next = path.get(0);
            boolean moved = ((AbstractDedaleAgent) myAgent).moveTo(new GsLocation(next));
            if (!moved) {
                System.out.println(myAgent.getLocalName() + " cannot move to " + next + ", blocked");
                blockedPositions.add(next);
                // 尝试其他邻居
                for (String alt : neighbours) {
                    if (!occupiedNeighbours.contains(alt) && !blockedPositions.contains(alt) && !alt.equals(targetNeighbour)) {
                        targetNeighbour = alt;
                        path = myMap.getShortestPath(myId, targetNeighbour);
                        if (path != null && !path.isEmpty()) {
                            next = path.get(0);
                            moved = ((AbstractDedaleAgent) myAgent).moveTo(new GsLocation(next));
                            if (moved) {
                                System.out.println(myAgent.getLocalName() + " detoured to " + targetNeighbour + " via " + next);
                                break;
                            }
                        }
                    }
                }
                if (!moved) {
                    block(500);
                }
            } else {
                System.out.println(myAgent.getLocalName() + " moving to " + targetNeighbour + " via " + next);
            }
        } else {
            System.out.println(myAgent.getLocalName() + " no path to " + targetNeighbour);
        }
    }

    // ---------- 捕获判定（基于实时观察，不依赖广播） ----------
    private boolean isTargetBlockedByObservation(List<Couple<Location, List<Couple<Observation, String>>>> obs) {
        List<String> neighbours = myMap.getNeighbors(currentTarget);
        if (neighbours.isEmpty()) return false;
        Set<String> occupied = new HashSet<>();
        for (int i = 1; i < obs.size(); i++) {
            String nodeId = obs.get(i).getLeft().getLocationId();
            if (neighbours.contains(nodeId)) {
                boolean hasAgent = obs.get(i).getRight().stream()
                        .anyMatch(p -> p.getLeft() == Observation.AGENTNAME);
                if (hasAgent) occupied.add(nodeId);
            }
        }
        String myPos = ((AbstractDedaleAgent) myAgent).getCurrentPosition().getLocationId();
        if (neighbours.contains(myPos)) occupied.add(myPos);
        // 关键：不依赖广播，只依赖实时观察
        return occupied.containsAll(neighbours);
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
        for (AID aid : getAllExplorerAgents()) {
            if (!aid.equals(myAgent.getAID())) {
                msg.addReceiver(aid);
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
                    if (changed && shareBehaviour != null) shareBehaviour.markUpdate();
                }
            } catch (UnreadableException e) { e.printStackTrace(); }
        }
    }

    private List<AID> getAllExplorerAgents() {
        List<AID> agents = new ArrayList<>();
        DFAgentDescription template = new DFAgentDescription();
        ServiceDescription sd = new ServiceDescription();
        sd.setType("agentExplo");
        template.addServices(sd);
        try {
            DFAgentDescription[] results = DFService.search(myAgent, template);
            for (DFAgentDescription dfd : results) {
                agents.add(dfd.getName());
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