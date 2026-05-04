package eu.su.mas.dedaleEtu.mas.behaviours;

import java.util.*;
import dataStructures.tuple.Couple;
import eu.su.mas.dedale.env.Location;
import eu.su.mas.dedale.env.Observation;
import eu.su.mas.dedale.env.gs.GsLocation;
import eu.su.mas.dedale.mas.AbstractDedaleAgent;
import eu.su.mas.dedaleEtu.mas.agents.dummies.explo.FSMExploAgent;
import eu.su.mas.dedaleEtu.mas.knowledge.GolemInfo;
import jade.core.AID;
import jade.core.behaviours.OneShotBehaviour;
import jade.lang.acl.ACLMessage;

public class HuntBehaviour extends OneShotBehaviour {
    private static final long serialVersionUID = 1L;

    public HuntBehaviour(final AbstractDedaleAgent myAgent) { super(myAgent); }

    @Override
    public void action() {
        FSMExploAgent agent = (FSMExploAgent) this.myAgent;
        Location loc = ((AbstractDedaleAgent) this.myAgent).getCurrentPosition();
        if (loc == null) return;
        String myPosition = loc.getLocationId();
        String lastPosition = agent.getLastPosition();
        String nextNode = null;

        try { Thread.sleep(200); } catch (InterruptedException e) {}

        agent.cleanBlockedNodes();
        agent.cleanupMessageCaches();

        if (agent.getLastFailedNode() != null && myPosition.equals(agent.getLastFailedNodePosition())) {
            agent.addBlockedNode(agent.getLastFailedNode());
            System.out.println(agent.getLocalName() + " [HUNT] last move to " + agent.getLastFailedNode() + " failed -> blacklisted.");
        }

        List<Couple<Location, List<Couple<Observation, String>>>> lobs =
                ((AbstractDedaleAgent) this.myAgent).observe();

        List<String> nodeStench = new ArrayList<>();
        for (Couple<Location, List<Couple<Observation, String>>> entry : lobs) {
            String nodeId = entry.getLeft().getLocationId();
            agent.addNextNodes(nodeId);
            for (Couple<Observation, String> obs : entry.getRight()) {
                if (obs.getLeft() == Observation.AGENTNAME) {
                    String seenAgent = obs.getRight();
                    agent.addPosition(nodeId);
                    agent.updateAgentPosition(seenAgent, nodeId);
                }
                if (obs.getLeft() == Observation.STENCH) nodeStench.add(nodeId);
            }
        }

        if (nodeStench.size() == 1) agent.setOwnStenchDirection(nodeStench.get(0));
        else if (nodeStench.size() > 1) agent.setOwnInsideStench(myPosition);
        else { agent.setOwnStenchDirection(null); agent.setOwnInsideStench(null); }
        if (!nodeStench.isEmpty()) agent.getMyMap().updateScentFromObservation(new HashSet<>(nodeStench));

        // Detect visible Golems
        Map<String, String> spotted = detectAllGolems(lobs, agent);
        for (Map.Entry<String, String> e : spotted.entrySet()) agent.addOrUpdateGolem(e.getValue(), e.getKey(), true);

        // Intent-based leader election for each Golem we are close to
        for (GolemInfo golem : new ArrayList<>(agent.getKnownGolems().values())) {
            if (agent.getCapturedGolems().contains(golem.getId())) continue;
            String golemPos = golem.getLastKnownPosition();
            List<String> neighbors = agent.getMyMap().getNeighbors(myPosition);
            if (myPosition.equals(golemPos) || neighbors.contains(golemPos)) {
                if (!agent.isManager && agent.activeCFPGolemId == null) {
                    boolean elected = tryBecomeManager(agent, golem.getId(), golemPos);
                    if (elected) {
                        agent.isManager = true;
                        agent.activeCFPGolemId = golem.getId();
                        agent.cfpStartTime = System.currentTimeMillis();
                        agent.addBehaviour(new BlockingCFPBehaviour(agent, golem.getId(), golemPos));
                        return;
                    }
                }
            }
        }

        // Target selection
        if (agent.getCurrentTargetGolemId() == null ||
                !agent.getKnownGolems().containsKey(agent.getCurrentTargetGolemId())) {
            GolemInfo best = agent.selectBestTarget(myPosition);
            if (best != null) agent.setCurrentTargetGolemId(best.getId());
        }

        if (myPosition.equals(agent.getDestination())) {
            agent.setDestination(null); agent.setDestinationAlea(false);
            agent.setDestinationStench(false); agent.setDestinationInsideStench(false);
            agent.setDestinationInterblocage(false);
        }

        // Movement logic (kept unchanged for the most part)
        if (lastPosition.equals(myPosition) && agent.getPosition().contains(agent.getNextDest())) {
            if (!nodeStench.isEmpty() && agent.getStyle() == 1) {
                for (String nb : nodeStench) {
                    if (!agent.getPosition().contains(nb) && !nb.equals(myPosition)) {
                        nextNode = nb;
                        break;
                    }
                }
            }
            if (nextNode == null) {
                String far = null;
                while (far == null || far.equals(myPosition) ||
                        agent.getMyMap().getShortestPath(myPosition, far) == null ||
                        agent.getMyMap().getShortestPath(myPosition, far).contains(agent.getNextDest())) {
                    far = agent.getMyMap().getRandomNode();
                }
                List<String> path = agent.getMyMap().getShortestPath(myPosition, far);
                if (path != null && !path.isEmpty()) nextNode = path.get(0);
                if (new Random().nextDouble() >= 0.5) nextNode = agent.getNextDest();
            }
        } else if (agent.isDest_wumpusfound() && agent.getDestination() != null) {
            List<String> path = agent.getMyMap().getShortestPath(myPosition, agent.getDestination());
            if (path != null && !path.isEmpty()) nextNode = path.get(0);
        } else if (lastPosition.equals(myPosition) && !agent.getPosition().contains(agent.getNextDest()) &&
                !myPosition.equals(agent.getNextDest())) {
            agent.increaseWumpusCnt();
            nextNode = agent.getNextDest();
        } else {
            agent.setWumpusCnt(0);
            if (nodeStench.size() == 1 && agent.getStyle() == 1) {
                if (!nodeStench.get(0).equals(myPosition)) nextNode = nodeStench.get(0);
            }
            if (nextNode == null && nodeStench.size() > 1 && agent.getStyle() == 1) {
                Collections.shuffle(nodeStench);
                for (String nb : nodeStench) {
                    if (!agent.getPosition().contains(nb) && !nb.equals(myPosition)) {
                        nextNode = nb;
                        break;
                    }
                }
            }
            if (nextNode == null && agent.getStyle() == 1 && !nodeStench.isEmpty()) {
                String strongestScentNode = agent.getMyMap().getStrongestScentNode();
                if (strongestScentNode != null && !strongestScentNode.equals(myPosition)) {
                    List<String> path = agent.getMyMap().getShortestPath(myPosition, strongestScentNode);
                    if (path != null && !path.isEmpty()) nextNode = path.get(0);
                }
            }
            if (nextNode == null && agent.getDestination() != null && !agent.getDestinationAlea()) {
                List<String> path = agent.getMyMap().getShortestPath(myPosition, agent.getDestination());
                if (path != null && !path.isEmpty()) nextNode = path.get(0);
            }
            if (nextNode == null && !agent.getStenchDirection().isEmpty() && agent.getStyle() == 0) {
                for (String dest : agent.getStenchDirection()) {
                    if (!dest.equals(myPosition)) {
                        agent.setDestination(dest);
                        agent.setDestinationStench(true);
                        agent.setDestinationInsideStench(false);
                        agent.setDestinationAlea(false);
                        List<String> path = agent.getMyMap().getShortestPath(myPosition, dest);
                        if (path != null && !path.isEmpty()) {
                            nextNode = path.get(0);
                            break;
                        }
                    }
                }
            }
            if (nextNode == null && !agent.getInsideStench().isEmpty() && agent.getStyle() == 0) {
                List<String> inside = new ArrayList<>(agent.getInsideStench());
                Collections.shuffle(inside);
                for (String dest : inside) {
                    if (!dest.equals(myPosition)) {
                        agent.setDestination(dest);
                        agent.setDestinationInsideStench(true);
                        agent.setDestinationStench(false);
                        agent.setDestinationAlea(false);
                        List<String> path = agent.getMyMap().getShortestPath(myPosition, dest);
                        if (path != null && !path.isEmpty()) {
                            nextNode = path.get(0);
                            break;
                        }
                    }
                }
            }
            if (nextNode == null) {
                String rando = null;
                while (rando == null || rando.equals(myPosition)) {
                    if (agent.getStyle() == 0) rando = agent.getMyMap().getRandomOneNode();
                    else rando = agent.getMyMap().getRandomNode();
                }
                agent.setDestination(rando);
                agent.setDestinationAlea(true);
                agent.setDestinationStench(false);
                agent.setDestinationInsideStench(false);
                List<String> path = agent.getMyMap().getShortestPath(myPosition, rando);
                if (path != null && !path.isEmpty()) nextNode = path.get(0);
            }
        }

        if (nextNode == null && agent.getCurrentTargetGolemId() != null) {
            GolemInfo target = agent.getKnownGolems().get(agent.getCurrentTargetGolemId());
            if (target != null) {
                List<String> path = agent.getMyMap().getShortestPath(myPosition, target.getLastKnownPosition());
                if (path != null && !path.isEmpty()) nextNode = path.get(0);
            }
        }

        if (nextNode != null && (agent.isNodeBlocked(nextNode) || agent.getKnownAgentPositions().contains(nextNode))) {
            System.out.println(agent.getLocalName() + " [HUNT] avoids " + nextNode);
            nextNode = null;
        }
        if (nextNode == null && agent.getMyMap().hasOpenNode()) {
            String safe = agent.getNextSafeOpenNode(myPosition);
            if (safe != null) nextNode = safe;
        }

        agent.setLastPosition(myPosition);
        agent.setNextDest(nextNode);
        if (nextNode != null) {
            agent.setLastFailedNode(nextNode, myPosition);
            ((AbstractDedaleAgent) this.myAgent).moveTo(new GsLocation(nextNode));
            System.out.println(agent.getLocalName() + " [HUNT] moving to " + nextNode);
        }
        agent.cleanPosition(); agent.cleanStenchDirection(); agent.cleanInsideStench(); agent.cleanNextNodes();
    }

    private boolean tryBecomeManager(FSMExploAgent agent, String golemId, String golemPos) {
        Set<String> intents = agent.activeIntents.get(golemId);
        if (intents != null) {
            for (String name : intents) {
                if (name.compareTo(agent.getLocalName()) < 0) {
                    System.out.println(agent.getLocalName() + " [INTENT] saw higher priority intent from " + name + ", deferring.");
                    return false;
                }
            }
        }

        ACLMessage intent = new ACLMessage(ACLMessage.INFORM);
        intent.setSender(agent.getAID());
        intent.setProtocol("INTENT-TO-BLOCK");
        intent.setContent(golemId + ":" + agent.getLocalName());
        for (AID aid : agent.getServices("Explorer")) {
            if (!aid.equals(agent.getAID())) intent.addReceiver(aid);
        }
        ((AbstractDedaleAgent) myAgent).sendMessage(intent);
        System.out.println(agent.getLocalName() + " [INTENT] sent for " + golemId);
        agent.activeIntents.computeIfAbsent(golemId,
                k -> Collections.synchronizedSet(new HashSet<>())).add(agent.getLocalName());

        try { Thread.sleep(300); } catch (InterruptedException ignored) {}

        Set<String> updated = agent.activeIntents.get(golemId);
        if (updated != null) {
            for (String name : updated) {
                if (name.compareTo(agent.getLocalName()) < 0) {
                    System.out.println(agent.getLocalName() + " [INTENT] losing to " + name);
                    updated.remove(agent.getLocalName());
                    return false;
                }
            }
        }

        agent.activeIntents.remove(golemId);
        System.out.println(agent.getLocalName() + " [INTENT] elected manager for " + golemId);
        return true;
    }

    private Map<String, String> detectAllGolems(
            List<Couple<Location, List<Couple<Observation, String>>>> obs, FSMExploAgent agent) {
        Map<String, String> result = new HashMap<>();
        for (Couple<Location, List<Couple<Observation, String>>> entry : obs) {
            String nodeId = entry.getLeft().getLocationId();
            for (Couple<Observation, String> p : entry.getRight()) {
                if (p.getLeft() == Observation.AGENTNAME &&
                        (p.getRight().toLowerCase().contains("wumpus") ||
                         p.getRight().toLowerCase().contains("golem"))) {
                    result.put(nodeId, p.getRight());
                }
            }
        }
        return result;
    }
}