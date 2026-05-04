package eu.su.mas.dedaleEtu.mas.behaviours;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;

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

        if (agent.getMyMap() == null) {
            agent.initiateMyMap();
            agent.setWait(1);
            agent.setGetoutCnt(0);
        }

        // Mark current node as closed (explored)
        agent.myMapAddNewNode(myPosition);
        agent.getMyMap().addNode(myPosition, MapAttribute.closed);

        // Observe surroundings
        List<Couple<Location, List<Couple<Observation, String>>>> lobs =
                ((AbstractDedaleAgent) this.myAgent).observe();
        for (Couple<Location, List<Couple<Observation, String>>> entry : lobs) {
            String nodeId = entry.getLeft().getLocationId();
            if (!myPosition.equals(nodeId)) {
                boolean isNew = agent.myMapAddNewNode(nodeId);
                agent.myMapAddEdge(myPosition, nodeId);
                if (nextNode == null && isNew && !agent.getPosition().contains(nodeId)) {
                    nextNode = nodeId;
                }
            }
        }

        // Check if exploration is complete
        if (agent.getGetoutCnt() >= 10 || !agent.getMyMap().hasOpenNode()) {
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
            // Detect stench for later use (but exploration just records it)
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

            // Update scent map (will be shared via delta)
            if (!nodeStench.isEmpty()) {
                agent.getMyMap().updateScentFromObservation(new java.util.HashSet<>(nodeStench));
            }

            agent.setWumpusCnt(0);

            if (nextNode == null && agent.getMyMap().hasOpenNode()) {
                List<String> path = agent.myMapShortestPathToClosestOpenNode(myPosition, agent.getPosition());
                if (path != null && !path.isEmpty()) nextNode = path.get(0);
            }

            if (nextNode == null) agent.increaseGetoutCnt();
            else agent.setGetoutCnt(0);
        }

        if (agent.getWait() > 0) agent.decreaseWait();
        agent.setLastPosition(myPosition);
        agent.setNextDest(nextNode);
        if (nextNode != null) {
            ((AbstractDedaleAgent) this.myAgent).moveTo(new GsLocation(nextNode));
            System.out.println(agent.getLocalName() + " [EXPLORE] moving to " + nextNode);
        }
        agent.cleanPosition();
        agent.cleanStenchDirection();
        agent.cleanInsideStench();
        agent.cleanNextNodes();
    }
}