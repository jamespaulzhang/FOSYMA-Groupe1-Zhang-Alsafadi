package eu.su.mas.dedaleEtu.mas.behaviours;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import java.util.Set;

import dataStructures.tuple.Couple;
import eu.su.mas.dedale.env.Location;
import eu.su.mas.dedale.env.Observation;
import eu.su.mas.dedale.env.gs.GsLocation;
import eu.su.mas.dedale.mas.AbstractDedaleAgent;
import eu.su.mas.dedaleEtu.mas.agents.dummies.explo.FSMExploAgent;
import eu.su.mas.dedaleEtu.mas.knowledge.MapRepresentation.MapAttribute;
import jade.core.behaviours.OneShotBehaviour;

public class ExplorationBehaviour extends OneShotBehaviour {

    private static final long serialVersionUID = 1L;

    public ExplorationBehaviour(final AbstractDedaleAgent myAgent) {
        super(myAgent);
    }

    @Override
    public void action() {
        FSMExploAgent agent = (FSMExploAgent) this.myAgent;
        Location loc = ((AbstractDedaleAgent) this.myAgent).getCurrentPosition();
        if (loc == null) return;
        String myPosition = loc.getLocationId();
        String lastPosition = agent.getLastPosition();
        String nextNode = null;

        try { Thread.sleep(800); } catch (InterruptedException e) {}

        // Clean expired blacklist entries
        agent.cleanBlockedNodes();

        // Check if previous move failed
        if (agent.getLastFailedNode() != null && myPosition.equals(agent.getLastFailedNodePosition())) {
            agent.addBlockedNode(agent.getLastFailedNode());
            System.out.println(agent.getLocalName() + " [EXPLORE] last move to " + agent.getLastFailedNode() + " failed -> blacklisted.");
        }

        if (agent.getMyMap() == null) {
            agent.initiateMyMap();
            agent.setWait(1);
            agent.setGetoutCnt(0);
        }

        // Mark current node as closed
        agent.myMapAddNewNode(myPosition);
        agent.getMyMap().addNode(myPosition, MapAttribute.closed);

        // Observe surroundings
        List<Couple<Location, List<Couple<Observation, String>>>> lobs =
                ((AbstractDedaleAgent) this.myAgent).observe();
        for (Couple<Location, List<Couple<Observation, String>>> entry : lobs) {
            String nodeId = entry.getLeft().getLocationId();

            // Record all AgentNames (including ImHere)
            for (Couple<Observation, String> obs : entry.getRight()) {
                if (obs.getLeft() == Observation.AGENTNAME) {
                    String seenAgent = obs.getRight();
                    agent.addPosition(nodeId);
                    agent.updateAgentPosition(seenAgent, nodeId);
                    System.out.println(agent.getLocalName() + " [EXPLORE] sees agent " + seenAgent + " at " + nodeId);
                }
            }

            if (!myPosition.equals(nodeId)) {
                boolean isNew = agent.myMapAddNewNode(nodeId);
                agent.myMapAddEdge(myPosition, nodeId);
                if (nextNode == null && isNew && !agent.getPosition().contains(nodeId) && !agent.isNodeBlocked(nodeId)) {
                    nextNode = nodeId;
                }
            }
        }

        // Check if exploration is complete
        if (agent.getGetoutCnt() >= 10 || !agent.getMyMap().hasOpenNode() || allOpenBlocked(agent)) {
            agent.setMode(FSMExploAgent.MODE_HUNT);
            double cc = agent.getMyMap().checkTypeGraph();
            agent.setStyle((Double.isNaN(cc) || cc < 0.05) ? 0 : 1);
            System.out.println(agent.getLocalName() + " switching to HUNT mode. Style: " + (agent.getStyle() == 0 ? "Tree" : "Graph"));
            return;
        }

        // Destination management
        if (myPosition.equals(agent.getDestination())) {
            agent.setDest_wumpusfound(false);
        }

        // Movement decision
        if (lastPosition.equals(myPosition) && agent.getPosition().contains(agent.getNextDest())) {
            String far = null;
            while (far == null || far.equals(myPosition) ||
                    agent.getMyMap().getShortestPath(myPosition, far) == null ||
                    agent.getMyMap().getShortestPath(myPosition, far).contains(agent.getNextDest())) {
                far = agent.getMyMap().getRandomNode();
            }
            List<String> path = agent.getMyMap().getShortestPath(myPosition, far);
            if (path != null && !path.isEmpty()) nextNode = path.get(0);
            if (new Random().nextDouble() >= 0.5) nextNode = agent.getNextDest();
        } else if (agent.isDest_wumpusfound() && agent.getDestination() != null) {
            List<String> path = agent.getMyMap().getShortestPath(myPosition, agent.getDestination());
            if (path != null && !path.isEmpty()) nextNode = path.get(0);
        } else if (lastPosition.equals(myPosition) && !agent.getPosition().contains(agent.getNextDest()) &&
                !myPosition.equals(agent.getNextDest())) {
            agent.increaseWumpusCnt();
            nextNode = agent.getNextDest();
        } else {
            List<String> nodeStench = new ArrayList<>();
            for (Couple<Location, List<Couple<Observation, String>>> entry : lobs) {
                String nodeId = entry.getLeft().getLocationId();
                if (!entry.getRight().isEmpty() && entry.getRight().get(0).getLeft() != null &&
                        entry.getRight().get(0).getLeft().toString().equals("Stench")) {
                    nodeStench.add(nodeId);
                }
            }

            if (nodeStench.size() == 1) agent.setOwnStenchDirection(nodeStench.get(0));
            else if (nodeStench.size() > 1) agent.setOwnInsideStench(myPosition);
            else { agent.setOwnStenchDirection(null); agent.setOwnInsideStench(null); }

            if (!nodeStench.isEmpty()) {
                agent.getMyMap().updateScentFromObservation(new HashSet<>(nodeStench));
            }

            agent.setWumpusCnt(0);

            if (nextNode == null && agent.getMyMap().hasOpenNode()) {
                List<String> path = agent.myMapShortestPathToClosestOpenNode(myPosition, agent.getKnownAgentPositions());
                if (path != null && !path.isEmpty()) nextNode = path.get(0);
            }

            if (nextNode == null) agent.increaseGetoutCnt();
            else agent.setGetoutCnt(0);
        }

        // Avoid blocked/occupied nodes
        if (nextNode != null && (agent.isNodeBlocked(nextNode) || agent.getKnownAgentPositions().contains(nextNode))) {
            System.out.println(agent.getLocalName() + " [EXPLORE] avoids " + nextNode + " (blocked or occupied)");
            nextNode = null; // discard current choice
        }

        // If nextNode is still null and we still need to explore, try again excluding blocked/occupied nodes
        if (nextNode == null && agent.getMyMap().hasOpenNode() && !allOpenBlocked(agent)) {
            // Build a list of open nodes that are not blocked and not occupied
            List<String> candidates = new ArrayList<>();
            for (String open : agent.getMyMap().getOpenNodes()) {
                if (!agent.isNodeBlocked(open) && !agent.getKnownAgentPositions().contains(open)) {
                    candidates.add(open);
                }
            }
            // From these candidates, pick the closest one
            if (!candidates.isEmpty()) {
                // Compute closest candidate manually (not via getShortestPathToClosestOpenNode to avoid exclusion)
                String closest = null;
                int minDist = Integer.MAX_VALUE;
                for (String cand : candidates) {
                    List<String> path = agent.getMyMap().getShortestPath(myPosition, cand);
                    int dist = (path == null) ? Integer.MAX_VALUE : path.size();
                    if (dist < minDist) {
                        minDist = dist;
                        closest = cand;
                    }
                }
                if (closest != null) {
                    List<String> path = agent.getMyMap().getShortestPath(myPosition, closest);
                    if (path != null && !path.isEmpty()) {
                        nextNode = path.get(0);
                    }
                }
            }
        }

        if (agent.getWait() > 0) agent.decreaseWait();
        agent.setLastPosition(myPosition);
        agent.setNextDest(nextNode);
        if (nextNode != null) {
            agent.setLastFailedNode(nextNode, myPosition); // record attempt
            ((AbstractDedaleAgent) this.myAgent).moveTo(new GsLocation(nextNode));
            System.out.println(agent.getLocalName() + " [EXPLORE] moving to " + nextNode);
        }
        agent.cleanPosition();
        agent.cleanStenchDirection();
        agent.cleanInsideStench();
        agent.cleanNextNodes();
    }

    private boolean allOpenBlocked(FSMExploAgent agent) {
        for (String open : agent.getMyMap().getOpenNodes()) {
            if (!agent.isNodeBlocked(open) && !agent.getKnownAgentPositions().contains(open)) {
                return false;
            }
        }
        return true;
    }
}