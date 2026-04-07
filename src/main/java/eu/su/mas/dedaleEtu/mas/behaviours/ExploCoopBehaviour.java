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
import jade.core.AID;
import jade.core.behaviours.SimpleBehaviour;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;
import jade.lang.acl.UnreadableException;

public class ExploCoopBehaviour extends SimpleBehaviour {
    private static final long serialVersionUID = 8567689731496787661L;
    
    private static final String PROTOCOL_SHARE = "SHARE-TOPO";
    private boolean finished = false;
    private MapRepresentation myMap;
    private ShareMapBehaviour shareBehaviour;
    private DecentralizedHuntBehaviour huntBehaviour;
    private boolean notifiedHunt = false;
    
    private Set<String> blockedPositions = new HashSet<>();

    public ExploCoopBehaviour(final AbstractDedaleAgent myagent,
                              MapRepresentation myMap,
                              ShareMapBehaviour shareBehaviour,
                              DecentralizedHuntBehaviour huntBehaviour) {
        super(myagent);
        this.myMap = myMap;
        this.shareBehaviour = shareBehaviour;
        this.huntBehaviour = huntBehaviour;
    }
    
    @Override
    public void action() {
        if (finished) return;
        
        if (myMap == null) {
            myMap = new MapRepresentation(myAgent.getLocalName());
        }
        myMap.showGUI();
        
        Location myPos = ((AbstractDedaleAgent) myAgent).getCurrentPosition();
        if (myPos == null) return;
        
        List<Couple<Location, List<Couple<Observation, String>>>> observations =
                ((AbstractDedaleAgent) myAgent).observe();
        
        updateTopology(myPos, observations);
        updateScent(observations);
        handleSharedMap();
        
        if (myMap.hasOpenNode()) {
            explore(myPos, observations);
        } else {
            finished = true;
            if (!notifiedHunt && huntBehaviour != null) {
                try { Thread.sleep(2000); } catch (InterruptedException e) {}
                huntBehaviour.setExplorationDone(true);
                notifiedHunt = true;
                System.out.println(myAgent.getLocalName() + " exploration finished, hunt notified.");
            }
        }
    }
    
    private void updateTopology(Location myPos, List<Couple<Location, List<Couple<Observation, String>>>> obs) {
        String myId = myPos.getLocationId();
        myMap.addNode(myId, MapAttribute.closed);
        shareBehaviour.markUpdate();
        for (int i = 1; i < obs.size(); i++) {
            String nbId = obs.get(i).getLeft().getLocationId();
            boolean isNew = myMap.addNewNode(nbId);
            myMap.addEdge(myId, nbId);
            if (isNew) shareBehaviour.markUpdate();
        }
    }
    
    private void updateScent(List<Couple<Location, List<Couple<Observation, String>>>> obs) {
        Set<String> fresh = new HashSet<>();
        for (int i = 1; i < obs.size(); i++) {
            boolean has = obs.get(i).getRight().stream()
                    .anyMatch(p -> p.getLeft() == Observation.STENCH);
            if (has) {
                String node = obs.get(i).getLeft().getLocationId();
                fresh.add(node);
                System.out.println(myAgent.getLocalName() + " detected STENCH at " + node);
            }
        }
        System.out.println(myAgent.getLocalName() + " fresh scent nodes: " + fresh);
        myMap.updateScentFromObservation(fresh);
        if (!fresh.isEmpty()) shareBehaviour.markUpdate();
    }
    
    private void handleSharedMap() {
        MessageTemplate tmpl = MessageTemplate.and(
                MessageTemplate.MatchProtocol(PROTOCOL_SHARE),
                MessageTemplate.MatchPerformative(ACLMessage.INFORM));
        ACLMessage msg = myAgent.receive(tmpl);
        if (msg != null) {
            try {
                Object obj = msg.getContentObject();
                if (obj instanceof eu.su.mas.dedaleEtu.mas.knowledge.MapWithScent) {
                    eu.su.mas.dedaleEtu.mas.knowledge.MapWithScent mws = 
                        (eu.su.mas.dedaleEtu.mas.knowledge.MapWithScent) obj;
                    boolean changed = myMap.mergeMap(mws.graph, mws.scent);
                    if (changed) shareBehaviour.markUpdate();
                }
            } catch (UnreadableException e) { e.printStackTrace(); }
        }
    }
    
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
            List<String> path = getShortestPathAvoidingBlocked(myPos.getLocationId());
            if (path != null && !path.isEmpty()) next = path.get(0);
        }
        if (next != null) {
            if (!((AbstractDedaleAgent) myAgent).moveTo(new GsLocation(next))) {
                blockedPositions.add(next);
                Location freeMove = getFreeMove(obs);
                if (freeMove != null) {
                    ((AbstractDedaleAgent) myAgent).moveTo(freeMove);
                }
            }
        } else {
            Location freeMove = getFreeMove(obs);
            if (freeMove != null) ((AbstractDedaleAgent) myAgent).moveTo(freeMove);
        }
    }
    
    private List<String> getShortestPathAvoidingBlocked(String from) {
        List<String> open = myMap.getOpenNodes();
        List<String> available = new ArrayList<>();
        for (String node : open) {
            if (!blockedPositions.contains(node)) available.add(node);
        }
        if (available.isEmpty()) return null;
        String closest = null;
        int minDist = Integer.MAX_VALUE;
        for (String target : available) {
            List<String> path = myMap.getShortestPath(from, target);
            if (path != null && path.size() < minDist) {
                minDist = path.size();
                closest = target;
            }
        }
        if (closest == null) return null;
        return myMap.getShortestPath(from, closest);
    }
    
    private Location getFreeMove(List<Couple<Location, List<Couple<Observation, String>>>> obs) {
        List<Location> free = new ArrayList<>();
        for (int i = 1; i < obs.size(); i++) {
            String nodeId = obs.get(i).getLeft().getLocationId();
            if (!blockedPositions.contains(nodeId)) {
                free.add(obs.get(i).getLeft());
            }
        }
        if (free.isEmpty()) return null;
        return free.get(new Random().nextInt(free.size()));
    }
    
    @Override
    public boolean done() {
        return finished;
    }
}