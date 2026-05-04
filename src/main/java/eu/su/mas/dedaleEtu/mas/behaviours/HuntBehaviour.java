package eu.su.mas.dedaleEtu.mas.behaviours;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;

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
import java.io.IOException;

public class HuntBehaviour extends OneShotBehaviour {

    private static final long serialVersionUID = 1L;
    private static final String PROTOCOL_CAPTURE = "CAPTURE";

    private int surroundedCount = 0;
    private String lastCheckedGolemId = null;
    private static final int REQUIRED_SURROUNDED_COUNT = 3;

    public HuntBehaviour(final AbstractDedaleAgent myAgent) {
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

        // Nettoyer la liste noire expirée
        agent.cleanBlockedNodes();

        // Vérifier si le déplacement précédent a échoué
        if (agent.getLastFailedNode() != null && myPosition.equals(agent.getLastFailedNodePosition())) {
            agent.addBlockedNode(agent.getLastFailedNode());
            System.out.println(agent.getLocalName() + " [HUNT] last move to " + agent.getLastFailedNode() + " failed -> blacklisted.");
        }

        List<Couple<Location, List<Couple<Observation, String>>>> lobs =
                ((AbstractDedaleAgent) this.myAgent).observe();

        List<String> nodeStench = new ArrayList<>();
        Iterator<Couple<Location, List<Couple<Observation, String>>>> iter = lobs.iterator();
        while (iter.hasNext()) {
            Couple<Location, List<Couple<Observation, String>>> entry = iter.next();
            String nodeId = entry.getLeft().getLocationId();
            agent.addNextNodes(nodeId);

            for (Couple<Observation, String> obs : entry.getRight()) {
                if (obs.getLeft() == Observation.AGENTNAME) {
                    String seenAgent = obs.getRight();
                    agent.addPosition(nodeId);
                    agent.updateAgentPosition(seenAgent, nodeId);
                    System.out.println(agent.getLocalName() + " [HUNT] sees agent " + seenAgent + " at " + nodeId);
                }
                if (obs.getLeft() == Observation.STENCH) {
                    nodeStench.add(nodeId);
                }
            }
        }

        // Met à jour les propres observations d'odeur
        if (nodeStench.size() == 1) {
            agent.setOwnStenchDirection(nodeStench.get(0));
        } else if (nodeStench.size() > 1) {
            agent.setOwnInsideStench(myPosition);
        } else {
            agent.setOwnStenchDirection(null);
            agent.setOwnInsideStench(null);
        }

        if (!nodeStench.isEmpty()) {
            agent.getMyMap().updateScentFromObservation(new java.util.HashSet<>(nodeStench));
        }

        // Détecter les Golems visibles
        Map<String, String> spottedGolems = detectAllGolems(lobs, agent);
        for (Map.Entry<String, String> e : spottedGolems.entrySet()) {
            agent.addOrUpdateGolem(e.getValue(), e.getKey(), true);
        }

        // Détection d'encerclement
        for (GolemInfo golem : agent.getKnownGolems().values()) {
            if (agent.getCapturedGolems().contains(golem.getId())) continue;
            String pos = golem.getLastKnownPosition();
            if (myPosition.equals(pos) || agent.getMyMap().getNeighbors(myPosition).contains(pos)) {
                if (isGolemSurrounded(pos, agent)) {
                    if (lastCheckedGolemId == null || !lastCheckedGolemId.equals(golem.getId())) {
                        surroundedCount = 0;
                        lastCheckedGolemId = golem.getId();
                    }
                    surroundedCount++;
                    if (surroundedCount >= REQUIRED_SURROUNDED_COUNT) {
                        captureGolem(golem.getId(), agent);
                        surroundedCount = 0;
                        lastCheckedGolemId = null;
                        return;
                    }
                } else {
                    surroundedCount = 0;
                    lastCheckedGolemId = null;
                }
            }
        }

        // Chasse style graphe : points d'articulation
        if (agent.getStyle() == 1) {
            for (GolemInfo golem : agent.getKnownGolems().values()) {
                if (agent.getCapturedGolems().contains(golem.getId())) continue;
                Set<String> articulationPoints = agent.getMyMap().getArticulationPoints();
                String golemPos = golem.getLastKnownPosition();
                for (String ap : articulationPoints) {
                    if (ap.equals(myPosition)) {
                        agent.setBlockingNode(ap);
                        agent.addBlockingTarget(golem.getId());
                        agent.setMode(FSMExploAgent.MODE_BLOCKING);
                        System.out.println(agent.getLocalName() + " switching to BLOCKING mode on AP: " + ap);
                        return;
                    } else if (agent.getMyMap().getShortestPath(golemPos, ap) != null &&
                               agent.getMyMap().getShortestPath(golemPos, ap).size() <= 3) {
                        List<String> path = agent.getMyMap().getShortestPath(myPosition, ap);
                        if (path != null && !path.isEmpty()) {
                            nextNode = path.get(0);
                            agent.setBlockingNode(ap);
                            agent.addBlockingTarget(golem.getId());
                        }
                    }
                }
            }
        }

        // Adjacent à un Golem -> lancer CFP
        for (GolemInfo golem : agent.getKnownGolems().values()) {
            if (agent.getCapturedGolems().contains(golem.getId())) continue;
            String golemPos = golem.getLastKnownPosition();
            List<String> neighbors = agent.getMyMap().getNeighbors(myPosition);
            if (myPosition.equals(golemPos) || neighbors.contains(golemPos)) {
                if (!agent.isManager && agent.activeCFPGolemId == null) {
                    System.out.println(agent.getLocalName() + " [HUNT] adjacent to Golem " + golem.getId() + ", starting CFP");
                    agent.isManager = true;
                    agent.activeCFPGolemId = golem.getId();
                    agent.cfpStartTime = System.currentTimeMillis();
                    agent.addBehaviour(new BlockingCFPBehaviour(agent, golem.getId(), golemPos));
                    return;
                }
            }
        }

        // Sélection de cible
        if (agent.getCurrentTargetGolemId() == null ||
                !agent.getKnownGolems().containsKey(agent.getCurrentTargetGolemId())) {
            GolemInfo best = agent.selectBestTarget(myPosition);
            if (best != null) {
                agent.setCurrentTargetGolemId(best.getId());
            }
        }

        // Gestion de destination
        if (myPosition.equals(agent.getDestination())) {
            agent.setDestination(null);
            agent.setDestinationAlea(false);
            agent.setDestinationStench(false);
            agent.setDestinationInsideStench(false);
            agent.setDestinationInterblocage(false);
        }

        // Décision de mouvement (originale, conservée intégralement)
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
                if (!nodeStench.get(0).equals(myPosition)) {
                    nextNode = nodeStench.get(0);
                }
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

        // Fallback: move towards current target Golem
        if (nextNode == null && agent.getCurrentTargetGolemId() != null) {
            GolemInfo target = agent.getKnownGolems().get(agent.getCurrentTargetGolemId());
            if (target != null) {
                List<String> path = agent.getMyMap().getShortestPath(myPosition, target.getLastKnownPosition());
                if (path != null && !path.isEmpty()) nextNode = path.get(0);
            }
        }

        // ---- Éviter les sommets bloqués ou occupés ----
        if (nextNode != null && (agent.isNodeBlocked(nextNode) || agent.getKnownAgentPositions().contains(nextNode))) {
            System.out.println(agent.getLocalName() + " [HUNT] avoids " + nextNode + " (blocked or occupied)");
            nextNode = null; // discard current choice
        }

        // ---- Si nextNode est toujours nul, essayer de trouver une alternative ----
        if (nextNode == null && agent.getMyMap().hasOpenNode()) {
            // Construire une liste de nœuds ouverts non bloqués et non occupés
            List<String> candidates = new ArrayList<>();
            for (String open : agent.getMyMap().getOpenNodes()) {
                if (!agent.isNodeBlocked(open) && !agent.getKnownAgentPositions().contains(open)) {
                    candidates.add(open);
                }
            }
            // On pourrait aussi envisager des nœuds fermés non occupés pour continuer la chasse
            // mais pour l'instant, on utilise la même logique que l'exploration.
            if (!candidates.isEmpty()) {
                // Choisir le plus proche parmi ces candidats
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
                        System.out.println(agent.getLocalName() + " [HUNT] redirected to " + nextNode);
                    }
                }
            }
        }
        // ------------------------------------------------------------

        agent.setLastPosition(myPosition);
        agent.setNextDest(nextNode);
        if (nextNode != null) {
            agent.setLastFailedNode(nextNode, myPosition);
            ((AbstractDedaleAgent) this.myAgent).moveTo(new GsLocation(nextNode));
            System.out.println(agent.getLocalName() + " [HUNT] moving to " + nextNode);
        }
        agent.cleanPosition();
        agent.cleanStenchDirection();
        agent.cleanInsideStench();
        agent.cleanNextNodes();
    }

    private Map<String, String> detectAllGolems(List<Couple<Location, List<Couple<Observation, String>>>> obs, FSMExploAgent agent) {
        Map<String, String> result = new HashMap<>();
        for (int i = 0; i < obs.size(); i++) {
            String nodeId = obs.get(i).getLeft().getLocationId();
            Optional<String> golemName = obs.get(i).getRight().stream()
                    .filter(p -> p.getLeft() == Observation.AGENTNAME &&
                            p.getRight() != null &&
                            (p.getRight().toLowerCase().contains("wumpus") || 
                             p.getRight().toLowerCase().contains("golem")))
                    .map(Couple::getRight)
                    .findFirst();
            if (golemName.isPresent()) {
                String id = golemName.get();
                result.put(nodeId, id);
                System.out.println(agent.getLocalName() + " [HUNT] Directly spotted Golem/Wumpus at " + nodeId + " -> " + id);
            }
        }
        return result;
    }

    private boolean isGolemSurrounded(String golemNode, FSMExploAgent agent) {
        List<String> neighbours = agent.getMyMap().getNeighbors(golemNode);
        if (neighbours.isEmpty()) return true;
        Set<String> occupied = agent.getKnownAgentPositions();
        String myPos = ((AbstractDedaleAgent) myAgent).getCurrentPosition().getLocationId();
        occupied.add(myPos);
        occupied.remove(golemNode);
        for (String nb : neighbours) {
            if (!occupied.contains(nb)) {
                return false;
            }
        }
        return true;
    }

    private void captureGolem(String golemId, FSMExploAgent agent) {
        System.out.println(agent.getLocalName() + " [HUNT] *** GOLEM CAPTURED: " + golemId + " ***");
        agent.markGolemCaptured(golemId);
        broadcastCapture(golemId, agent);
    }

    private void broadcastCapture(String golemId, FSMExploAgent agent) {
        ACLMessage msg = new ACLMessage(ACLMessage.INFORM);
        msg.setProtocol(PROTOCOL_CAPTURE);
        msg.setSender(myAgent.getAID());
        for (AID aid : agent.getServices("Explorer")) {
            if (!aid.equals(myAgent.getAID())) {
                msg.addReceiver(aid);
            }
        }
        try {
            msg.setContentObject(golemId);
            ((AbstractDedaleAgent) myAgent).sendMessage(msg);
            System.out.println(agent.getLocalName() + " [SEND] CAPTURE: " + golemId);
        } catch (IOException e) {}
        try { Thread.sleep(100); } catch (InterruptedException e) {}
        ((AbstractDedaleAgent) myAgent).sendMessage(msg);
    }
}