package eu.su.mas.dedaleEtu.mas.behaviours;

import java.io.IOException;
import java.util.*;

import dataStructures.tuple.Couple;
import eu.su.mas.dedale.env.Location;
import eu.su.mas.dedale.env.Observation;
import eu.su.mas.dedale.mas.AbstractDedaleAgent;
import eu.su.mas.dedaleEtu.mas.knowledge.MapRepresentation;
import jade.core.AID;
import jade.core.behaviours.SimpleBehaviour;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;
import jade.lang.acl.UnreadableException;

public class DecentralizedHuntBehaviour extends SimpleBehaviour {
    private static final long serialVersionUID = 1L;

    private final AbstractDedaleAgent agent;
    private final MapRepresentation map;
    private boolean finished = false;
    private boolean explorationDone = false;
    private boolean mapReady = false;
    private int expectedMapSize = -1;
    private static final int MIN_MAP_SIZE = 10;

    private String currentTarget = null;
    private String myTargetNeighbor = null;
    private Set<String> occupiedNeighbors = new HashSet<>();
    private int stableBlockCount = 0;
    private static final int STABLE_THRESHOLD = 2;

    private long lastOccupancyBroadcast = 0;
    private static final long BROADCAST_INTERVAL = 2000;
    private long lastTargetBroadcast = 0;
    private static final long TARGET_BROADCAST_INTERVAL = 5000;

    private static final String PROTOCOL_TARGET = "HUNT-TARGET";
    private static final String PROTOCOL_OCCUPIED = "OCCUPIED";
    private static final String PROTOCOL_CAPTURE = "CAPTURE";
    private static final String PROTOCOL_AGENT_INFO = "AGENT-INFO";

    private static final String COORDINATOR_NAME = "Explo1";
    private Set<String> knownAgents = new HashSet<>();
    
    private Map<String, String> agentPositions = new HashMap<>();
    private Set<String> blockedPositions = new HashSet<>();

    public DecentralizedHuntBehaviour(AbstractDedaleAgent agent, MapRepresentation map) {
        super(agent);
        this.agent = agent;
        this.map = map;
        knownAgents.add(agent.getLocalName());
    }
    
    public void updateAgentPosition(String agentName, String position) {
        agentPositions.put(agentName, position);
    }
    
    public void setExplorationDone(boolean done) {
        this.explorationDone = done;
        System.out.println(agent.getLocalName() + " hunt: explorationDone=" + done);
    }

    @Override
    public void action() {
        if (finished) return;

        if (!mapReady && explorationDone) {
            mapReady = true;
            System.out.println(agent.getLocalName() + " map ready (exploration done)");
            broadcastAgentInfo();
            decideAndBroadcastTarget();
            lastTargetBroadcast = System.currentTimeMillis();
        }
        if (!mapReady) {
            block(500);
            return;
        }

        handleMessages();

        boolean amCoordinator = agent.getLocalName().equals(COORDINATOR_NAME);
        if (amCoordinator && mapReady && explorationDone) {
            long now = System.currentTimeMillis();
            if (now - lastTargetBroadcast >= TARGET_BROADCAST_INTERVAL) {
                decideAndBroadcastTarget();
                lastTargetBroadcast = now;
            }
        }

        if (currentTarget == null) {
            randomWalk("no target");
            block(500);
            return;
        }

        if (isTargetBlocked()) {
            stableBlockCount++;
            if (stableBlockCount >= STABLE_THRESHOLD) {
                broadcastCapture();
                finished = true;
                System.out.println(agent.getLocalName() + " - GOLEM BLOCKED at " + currentTarget);
                return;
            }
        } else {
            stableBlockCount = 0;
        }

        if (myTargetNeighbor == null) {
            String neighbor = selectFreeNeighbor();
            if (neighbor != null) {
                myTargetNeighbor = neighbor;
                System.out.println(agent.getLocalName() + " moving to occupy " + neighbor + " for target " + currentTarget);
            } else {
                randomWalk("no free neighbor for target " + currentTarget);
                block(500);
                return;
            }
        }

        if (myTargetNeighbor != null) {
            moveToTarget();
            Location pos = agent.getCurrentPosition();
            if (pos != null && pos.getLocationId().equals(myTargetNeighbor)) {
                broadcastOccupied(myTargetNeighbor);
                occupiedNeighbors.add(myTargetNeighbor);
                myTargetNeighbor = null;
                System.out.println(agent.getLocalName() + " occupied " + pos.getLocationId());
            }
        }

        broadcastMyPosition();
        block(300);
    }

    private void randomWalk(String reason) {
        Location pos = agent.getCurrentPosition();
        if (pos == null) return;
        List<String> neighbors = map.getNeighbors(pos.getLocationId());
        if (neighbors != null && !neighbors.isEmpty()) {
            List<String> free = new ArrayList<>();
            for (String nb : neighbors) {
                if (!blockedPositions.contains(nb)) free.add(nb);
            }
            if (free.isEmpty()) free = neighbors;
            String randomNeighbor = free.get(new Random().nextInt(free.size()));
            System.out.println(agent.getLocalName() + " random walk (" + reason + ") from " + pos.getLocationId() + " to " + randomNeighbor);
            if (!agent.moveTo(new eu.su.mas.dedale.env.gs.GsLocation(randomNeighbor))) {
                blockedPositions.add(randomNeighbor);
            }
        }
    }

    private void broadcastAgentInfo() {
        ACLMessage msg = new ACLMessage(ACLMessage.INFORM);
        msg.setProtocol(PROTOCOL_AGENT_INFO);
        msg.setSender(agent.getAID());
        for (String a : knownAgents) {
            if (!a.equals(agent.getLocalName()))
                msg.addReceiver(new AID(a, AID.ISLOCALNAME));
        }
        try {
            msg.setContentObject(agent.getLocalName());
            agent.sendMessage(msg);
        } catch (IOException e) {}
    }

    private void decideAndBroadcastTarget() {
        List<String> scentNodes = map.getWumpusScentNodes();
        System.out.println(agent.getLocalName() + " coordinator: scentNodes = " + scentNodes);
        
        String newTarget = null;
        
        if (scentNodes != null && !scentNodes.isEmpty()) {
            newTarget = Collections.min(scentNodes);
            System.out.println(agent.getLocalName() + " (coordinator) selected target from scent: " + newTarget);
        } else {
            var allNodes = map.getSerializableGraph().getAllNodes();
            if (allNodes != null && !allNodes.isEmpty()) {
                List<String> nodeIds = new ArrayList<>();
                for (var node : allNodes) {
                    nodeIds.add(node.getNodeId());
                }
                newTarget = Collections.min(nodeIds);
                System.out.println(agent.getLocalName() + " (coordinator) WARNING: no scent, forcing target to node " + newTarget);
            } else {
                System.out.println(agent.getLocalName() + " (coordinator) map empty, cannot select target");
                return;
            }
        }
        
        if (newTarget.equals(currentTarget)) return;
        currentTarget = newTarget;
        System.out.println(agent.getLocalName() + " (coordinator) final target Golem = " + currentTarget);
        
        ACLMessage msg = new ACLMessage(ACLMessage.INFORM);
        msg.setProtocol(PROTOCOL_TARGET);
        msg.setSender(agent.getAID());
        for (String a : knownAgents) {
            if (!a.equals(agent.getLocalName()))
                msg.addReceiver(new AID(a, AID.ISLOCALNAME));
        }
        try {
            msg.setContentObject(currentTarget);
            agent.sendMessage(msg);
        } catch (IOException e) {}
        
        myTargetNeighbor = null;
        occupiedNeighbors.clear();
        stableBlockCount = 0;
    }

    private void handleMessages() {
        MessageTemplate infoTmpl = MessageTemplate.and(
                MessageTemplate.MatchProtocol(PROTOCOL_AGENT_INFO),
                MessageTemplate.MatchPerformative(ACLMessage.INFORM));
        ACLMessage infoMsg = agent.receive(infoTmpl);
        while (infoMsg != null) {
            try {
                String agentName = (String) infoMsg.getContentObject();
                knownAgents.add(agentName);
            } catch (UnreadableException e) {}
            infoMsg = agent.receive(infoTmpl);
        }

        MessageTemplate targetTmpl = MessageTemplate.and(
                MessageTemplate.MatchProtocol(PROTOCOL_TARGET),
                MessageTemplate.MatchPerformative(ACLMessage.INFORM));
        ACLMessage targetMsg = agent.receive(targetTmpl);
        if (targetMsg != null) {
            try {
                String newTarget = (String) targetMsg.getContentObject();
                if (!newTarget.equals(currentTarget)) {
                    System.out.println(agent.getLocalName() + " received new target: " + newTarget);
                    currentTarget = newTarget;
                    myTargetNeighbor = null;
                    occupiedNeighbors.clear();
                    stableBlockCount = 0;
                }
            } catch (UnreadableException e) {}
        }

        MessageTemplate occTmpl = MessageTemplate.and(
                MessageTemplate.MatchProtocol(PROTOCOL_OCCUPIED),
                MessageTemplate.MatchPerformative(ACLMessage.INFORM));
        ACLMessage occMsg = agent.receive(occTmpl);
        while (occMsg != null) {
            try {
                String node = (String) occMsg.getContentObject();
                occupiedNeighbors.add(node);
            } catch (UnreadableException e) {}
            occMsg = agent.receive(occTmpl);
        }

        MessageTemplate capTmpl = MessageTemplate.and(
                MessageTemplate.MatchProtocol(PROTOCOL_CAPTURE),
                MessageTemplate.MatchPerformative(ACLMessage.INFORM));
        ACLMessage capMsg = agent.receive(capTmpl);
        if (capMsg != null) {
            finished = true;
            System.out.println(agent.getLocalName() + " stopped by capture message");
        }
    }

    private boolean isTargetBlocked() {
        if (currentTarget == null) return false;
        List<String> neighbors = map.getNeighbors(currentTarget);
        if (neighbors.isEmpty()) return false;
        for (String nb : neighbors) {
            if (!isNodeOccupied(nb)) return false;
        }
        return true;
    }

    private boolean isNodeOccupied(String node) {
        Location myPos = agent.getCurrentPosition();
        if (myPos != null && myPos.getLocationId().equals(node)) return true;
        if (occupiedNeighbors.contains(node)) return true;
        if (agentPositions.containsValue(node)) return true;
        List<Couple<Location, List<Couple<Observation, String>>>> obs = agent.observe();
        for (int i = 1; i < obs.size(); i++) {
            if (obs.get(i).getLeft().getLocationId().equals(node)) {
                boolean hasAgent = obs.get(i).getRight().stream()
                        .anyMatch(p -> p.getLeft() == Observation.AGENTNAME);
                if (hasAgent) return true;
            }
        }
        return false;
    }

    private String selectFreeNeighbor() {
        if (currentTarget == null) return null;
        List<String> neighbors = map.getNeighbors(currentTarget);
        List<String> free = new ArrayList<>();
        for (String nb : neighbors) {
            if (!isNodeOccupied(nb) && !blockedPositions.contains(nb)) {
                free.add(nb);
            }
        }
        if (free.isEmpty()) return null;
        Location pos = agent.getCurrentPosition();
        if (pos == null) return free.get(0);
        String myId = pos.getLocationId();
        return free.stream()
                .min(Comparator.comparingInt(n -> {
                    List<String> path = map.getShortestPath(myId, n);
                    return path == null ? Integer.MAX_VALUE : path.size();
                }))
                .orElse(free.get(0));
    }

    private void moveToTarget() {
        Location pos = agent.getCurrentPosition();
        if (pos == null) return;
        String myId = pos.getLocationId();
        if (myId.equals(myTargetNeighbor)) return;
        List<String> path = map.getShortestPath(myId, myTargetNeighbor);
        if (path != null && !path.isEmpty()) {
            String next = path.get(0);
            if (!agent.moveTo(new eu.su.mas.dedale.env.gs.GsLocation(next))) {
                blockedPositions.add(next);
                myTargetNeighbor = null;
            }
        }
    }

    private void broadcastOccupied(String node) {
        ACLMessage msg = new ACLMessage(ACLMessage.INFORM);
        msg.setProtocol(PROTOCOL_OCCUPIED);
        msg.setSender(agent.getAID());
        for (String a : knownAgents) {
            if (!a.equals(agent.getLocalName()))
                msg.addReceiver(new AID(a, AID.ISLOCALNAME));
        }
        try {
            msg.setContentObject(node);
            agent.sendMessage(msg);
        } catch (IOException e) {}
    }

    private void broadcastMyPosition() {
        long now = System.currentTimeMillis();
        if (now - lastOccupancyBroadcast < BROADCAST_INTERVAL) return;
        lastOccupancyBroadcast = now;
        Location pos = agent.getCurrentPosition();
        if (pos != null && !occupiedNeighbors.contains(pos.getLocationId())) {
            broadcastOccupied(pos.getLocationId());
            occupiedNeighbors.add(pos.getLocationId());
        }
    }

    private void broadcastCapture() {
        ACLMessage msg = new ACLMessage(ACLMessage.INFORM);
        msg.setProtocol(PROTOCOL_CAPTURE);
        msg.setSender(agent.getAID());
        for (String a : knownAgents) {
            if (!a.equals(agent.getLocalName()))
                msg.addReceiver(new AID(a, AID.ISLOCALNAME));
        }
        try {
            msg.setContentObject(currentTarget);
            agent.sendMessage(msg);
        } catch (IOException e) {}
        System.out.println(agent.getLocalName() + " broadcast CAPTURE at " + currentTarget);
    }

    @Override
    public boolean done() {
        return finished;
    }
}