package eu.su.mas.dedaleEtu.mas.behaviours;

import eu.su.mas.dedale.env.Location;
import eu.su.mas.dedale.mas.AbstractDedaleAgent;
import jade.core.AID;
import jade.core.behaviours.TickerBehaviour;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;
import jade.lang.acl.UnreadableException;

/**
 * Periodically shares its own position and receives positions from other agents,
 * updating the position table in the hunt behaviour.
 */
public class SharePositionBehaviour extends TickerBehaviour {
    private static final long serialVersionUID = 1L;
    
    private DecentralizedHuntBehaviour huntBehaviour;
    
    // Constructor explicitly receives the huntBehaviour reference
    public SharePositionBehaviour(AbstractDedaleAgent agent, long period, DecentralizedHuntBehaviour huntBehaviour) {
        super(agent, period);
        this.huntBehaviour = huntBehaviour;
    }
    
    @Override
    protected void onTick() {
        // 1. Send own position
        Location myPos = ((AbstractDedaleAgent) myAgent).getCurrentPosition();
        if (myPos != null) {
            ACLMessage msg = new ACLMessage(ACLMessage.INFORM);
            msg.setProtocol("SHARE-POSITION");
            msg.setSender(myAgent.getAID());
            // Broadcast to all reachable agents (obtained via observation)
            var observations = ((AbstractDedaleAgent) myAgent).observe();
            for (int i = 1; i < observations.size(); i++) {
                observations.get(i).getRight().stream()
                    .filter(p -> p.getLeft() == eu.su.mas.dedale.env.Observation.AGENTNAME)
                    .forEach(p -> msg.addReceiver(new AID(p.getRight(), AID.ISLOCALNAME)));
            }
            // If no receivers, send to self (harmless)
            if (msg.getAllReceiver().hasNext()) {
                try {
                    msg.setContentObject(myPos.getLocationId());
                    ((AbstractDedaleAgent) myAgent).sendMessage(msg);
                } catch (Exception e) { e.printStackTrace(); }
            }
        }
        
        // 2. Receive positions from other agents
        MessageTemplate tmpl = MessageTemplate.and(
                MessageTemplate.MatchProtocol("SHARE-POSITION"),
                MessageTemplate.MatchPerformative(ACLMessage.INFORM));
        ACLMessage received = myAgent.receive(tmpl);
        while (received != null) {
            try {
                String sender = received.getSender().getLocalName();
                String position = (String) received.getContentObject();
                if (huntBehaviour != null) {
                    huntBehaviour.updateAgentPosition(sender, position);
                }
            } catch (UnreadableException e) { e.printStackTrace(); }
            received = myAgent.receive(tmpl);
        }
    }
}