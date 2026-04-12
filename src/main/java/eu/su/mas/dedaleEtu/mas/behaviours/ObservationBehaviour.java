package eu.su.mas.dedaleEtu.mas.behaviours;

import java.util.ArrayList;
import java.util.Collections;
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

public class ObservationBehaviour extends OneShotBehaviour {

    private static final long serialVersionUID = 8567689731496787661L;

    public ObservationBehaviour(final AbstractDedaleAgent myAgent) {
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

        // Small delay for visualization
        try { Thread.sleep(800); } catch (InterruptedException e) {}

        if (agent.getMode() == 0) {
            // ========== EXPLORATION MODE ==========
            if (agent.getMyMap() == null) {
                agent.initiateMyMap();
                agent.setWait(1);
                agent.setGetoutCnt(0);
            }

            // Check if stuck too long
            if (agent.getGetoutCnt() >= 10) {
                agent.setMode(1);
                agent.setStyle(agent.getMyMap().checkTypeGraph() < 0.05 ? 0 : 1);
                return;
            }

            // Reset Wumpus flag if arrived
            if (myPosition.equals(agent.getDestination())) {
                agent.setDest_wumpusfound(false);
            }

            // Handle being blocked by another agent
            if (lastPosition.equals(myPosition) && agent.getPosition().contains(agent.getNextDest())) {
                String far = null;
                while (far == null || far.equals(myPosition) ||
                        agent.getMyMap().getShortestPath(myPosition, far) == null ||
                        agent.getMyMap().getShortestPath(myPosition, far).contains(agent.getNextDest())) {
                    far = agent.getMyMap().getRandomNode();
                }
                nextNode = agent.getMyMap().getShortestPath(myPosition, far).get(0);
                if (new Random().nextDouble() >= 0.5) {
                    nextNode = agent.getNextDest();
                }
            } else if (agent.isDest_wumpusfound() && agent.getDestination() != null) {
                List<String> path = agent.getMyMap().getShortestPath(myPosition, agent.getDestination());
                if (path != null && !path.isEmpty()) nextNode = path.get(0);
            } else if (lastPosition.equals(myPosition) && !agent.getPosition().contains(agent.getNextDest()) &&
                    !myPosition.equals(agent.getNextDest())) {
                agent.increaseWumpusCnt();
                nextNode = agent.getNextDest();
            } else {
                // Observe and update map
                List<Couple<Location, List<Couple<Observation, String>>>> lobs =
                        ((AbstractDedaleAgent) this.myAgent).observe();
                agent.getMyMap().addNode(myPosition, MapAttribute.closed);

                Iterator<Couple<Location, List<Couple<Observation, String>>>> iter = lobs.iterator();
                while (iter.hasNext()) {
                    String nodeId = iter.next().getLeft().getLocationId();
                    boolean isNew = agent.myMapAddNewNode(nodeId);
                    if (!myPosition.equals(nodeId)) {
                        agent.myMapAddEdge(myPosition, nodeId);
                        if (nextNode == null && isNew && !agent.getPosition().contains(nodeId)) {
                            nextNode = nodeId;
                        }
                    }
                }

                // Detect stench
                List<String> nodeStench = new ArrayList<>();
                Iterator<Couple<Location, List<Couple<Observation, String>>>> iter2 = lobs.iterator();
                while (iter2.hasNext()) {
                    Couple<Location, List<Couple<Observation, String>>> entry = iter2.next();
                    String nodeId = entry.getLeft().getLocationId();
                    agent.addOpenNextNodes(nodeId);
                    if (!entry.getRight().isEmpty() && entry.getRight().get(0).getLeft() != null) {
                        if (entry.getRight().get(0).getLeft().toString().equals("Stench")) {
                            nodeStench.add(nodeId);
                        }
                    }
                }

                if (nodeStench.size() == 1) {
                    agent.setOwnStenchDirection(nodeStench.get(0));
                } else if (nodeStench.size() > 1) {
                    agent.setOwnInsideStench(myPosition);
                } else {
                    agent.setOwnStenchDirection(null);
                    agent.setOwnInsideStench(null);
                }

                agent.setWumpusCnt(0);

                if (!agent.getMyMap().hasOpenNode()) {
                    agent.setMode(1);
                    agent.setStyle(agent.getMyMap().checkTypeGraph() < 0.05 ? 0 : 1);
                    return;
                }

                if (nextNode == null && agent.getMyMap().hasOpenNode()) {
                    List<String> path = agent.myMapShortestPathToClosestOpenNode(myPosition, agent.getPosition());
                    if (path != null && !path.isEmpty()) nextNode = path.get(0);
                }
            }

            if (agent.getWait() > 0) agent.decreaseWait();
            agent.setLastPosition(myPosition);
            agent.setNextDest(nextNode);
            if (nextNode != null) {
                ((AbstractDedaleAgent) this.myAgent).moveTo(new GsLocation(nextNode));
            }
            agent.cleanPosition();
            agent.cleanStenchDirection();
            agent.cleanInsideStench();
            agent.cleanNextNodes();

        } else {
            // ========== HUNT MODE ==========
            // Observe
            List<Couple<Location, List<Couple<Observation, String>>>> lobs =
                    ((AbstractDedaleAgent) this.myAgent).observe();

            // Detect stench
            List<String> nodeStench = new ArrayList<>();
            Iterator<Couple<Location, List<Couple<Observation, String>>>> iter = lobs.iterator();
            while (iter.hasNext()) {
                Couple<Location, List<Couple<Observation, String>>> entry = iter.next();
                String nodeId = entry.getLeft().getLocationId();
                agent.addNextNodes(nodeId);
                if (!entry.getRight().isEmpty() && entry.getRight().get(0).getLeft() != null) {
                    if (entry.getRight().get(0).getLeft().toString().equals("Stench")) {
                        nodeStench.add(nodeId);
                    }
                }
            }

            if (nodeStench.size() == 1) {
                agent.setOwnStenchDirection(nodeStench.get(0));
            } else if (nodeStench.size() > 1) {
                agent.setOwnInsideStench(myPosition);
            } else {
                agent.setOwnStenchDirection(null);
                agent.setOwnInsideStench(null);
            }

            // Reset destination if reached
            if (myPosition.equals(agent.getDestination())) {
                agent.setDestination(null);
                agent.setDestinationAlea(false);
                agent.setDestinationStench(false);
                agent.setDestinationInsideStench(false);
                agent.setDestinationInterblocage(false);
            }

            // Blocked by another agent
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
                // Priority 1: local stench direction
                if (nodeStench.size() == 1 && agent.getStyle() == 1) {
                    if (!nodeStench.get(0).equals(myPosition)) {
                        nextNode = nodeStench.get(0);
                    }
                }
                // Priority 2: inside stench
                if (nextNode == null && nodeStench.size() > 1 && agent.getStyle() == 1) {
                    Collections.shuffle(nodeStench);
                    for (String nb : nodeStench) {
                        if (!agent.getPosition().contains(nb) && !nb.equals(myPosition)) {
                            nextNode = nb;
                            break;
                        }
                    }
                }
                // Priority 3: follow existing destination (stench/inside)
                if (nextNode == null && agent.getDestination() != null && !agent.getDestinationAlea()) {
                    List<String> path = agent.getMyMap().getShortestPath(myPosition, agent.getDestination());
                    if (path != null && !path.isEmpty()) nextNode = path.get(0);
                }
                // Priority 4: received stench direction (tree style)
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
                // Priority 5: received inside stench (tree style)
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
                // Priority 6: random wander
                if (nextNode == null) {
                    String rando = null;
                    while (rando == null || rando.equals(myPosition)) {
                        if (agent.getStyle() == 0) {
                            rando = agent.getMyMap().getRandomOneNode();
                        } else {
                            rando = agent.getMyMap().getRandomNode();
                        }
                    }
                    agent.setDestination(rando);
                    agent.setDestinationAlea(true);
                    agent.setDestinationStench(false);
                    agent.setDestinationInsideStench(false);
                    List<String> path = agent.getMyMap().getShortestPath(myPosition, rando);
                    if (path != null && !path.isEmpty()) nextNode = path.get(0);
                }
            }

            agent.setLastPosition(myPosition);
            agent.setNextDest(nextNode);
            if (nextNode != null) {
                ((AbstractDedaleAgent) this.myAgent).moveTo(new GsLocation(nextNode));
            }
            agent.cleanPosition();
            agent.cleanStenchDirection();
            agent.cleanInsideStench();
            agent.cleanNextNodes();
        }
    }
}