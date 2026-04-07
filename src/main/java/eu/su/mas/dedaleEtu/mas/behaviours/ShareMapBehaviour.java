package eu.su.mas.dedaleEtu.mas.behaviours;

import java.io.IOException;
import java.util.List;
import java.util.stream.Collectors;

import dataStructures.tuple.Couple;
import eu.su.mas.dedale.env.Location;
import eu.su.mas.dedale.env.Observation;
import eu.su.mas.dedale.mas.AbstractDedaleAgent;
import eu.su.mas.dedaleEtu.mas.knowledge.MapRepresentation;
import eu.su.mas.dedaleEtu.mas.knowledge.MapWithScent;
import jade.core.AID;
import jade.core.behaviours.TickerBehaviour;
import jade.lang.acl.ACLMessage;

public class ShareMapBehaviour extends TickerBehaviour {

    private static final long serialVersionUID = 1L;
    private final MapRepresentation myMap;
    private int lastHash = 0;
    private boolean hasUpdate = false;

    public ShareMapBehaviour(AbstractDedaleAgent a, long period, MapRepresentation myMap) {
        super(a, period);
        this.myMap = myMap;
    }

    @Override
    protected void onTick() {
        int curHash = myMap.getSerializableGraph().hashCode();
        if (curHash == lastHash && !hasUpdate) return;
        lastHash = curHash;
        hasUpdate = false;

        List<String> neighbours = getReachableAgents();
        if (neighbours.isEmpty()) return;

        MapWithScent mws = new MapWithScent(myMap.getSerializableGraph(), myMap.getSerializableScent());
        ACLMessage msg = new ACLMessage(ACLMessage.INFORM);
        msg.setProtocol("SHARE-TOPO");
        msg.setSender(myAgent.getAID());
        neighbours.forEach(name -> msg.addReceiver(new AID(name, AID.ISLOCALNAME)));

        try {
            msg.setContentObject(mws);
            ((AbstractDedaleAgent) myAgent).sendMessage(msg);
            System.out.println(myAgent.getLocalName() + " sent map to " + neighbours +
                               " (" + mws.graph.getAllNodes().size() + " nodes, " +
                               mws.scent.size() + " scent nodes)");
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private List<String> getReachableAgents() {
        AbstractDedaleAgent agent = (AbstractDedaleAgent) myAgent;
        Location pos = agent.getCurrentPosition();
        if (pos == null) return List.of();

        List<Couple<Location, List<Couple<Observation, String>>>> observations = agent.observe();
        if (observations.size() <= 1) return List.of();

        return observations.subList(1, observations.size()).stream()
                .flatMap(c -> c.getRight().stream())
                .filter(p -> p.getLeft() == Observation.AGENTNAME)
                .map(Couple::getRight)
                .filter(name -> name.startsWith("Explo"))
                .distinct()
                .collect(Collectors.toList());
    }

    public void markUpdate() {
        this.hasUpdate = true;
    }
}