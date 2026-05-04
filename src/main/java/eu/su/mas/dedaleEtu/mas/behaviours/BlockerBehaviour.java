package eu.su.mas.dedaleEtu.mas.behaviours;

import eu.su.mas.dedale.env.gs.GsLocation;
import eu.su.mas.dedale.mas.AbstractDedaleAgent;
import eu.su.mas.dedaleEtu.mas.agents.dummies.explo.FSMExploAgent;
import jade.core.AID;
import jade.core.behaviours.TickerBehaviour;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;
import jade.lang.acl.UnreadableException;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class BlockerBehaviour extends TickerBehaviour {

    private static final long serialVersionUID = 1L;
    private final FSMExploAgent agent;
    private final String targetNode;
    private final Set<String> targetGolems;
    private final long startTime;
    private static final long MAX_BLOCK_TIME = 120000;
    private boolean readySent = false;
    private AID managerAID;

    public BlockerBehaviour(FSMExploAgent agent) {
        super(agent, 1000);
        this.agent = agent;
        this.targetNode = agent.getBlockingNode();
        this.targetGolems = new HashSet<>(agent.getBlockingTargets()); // make local copy
        this.startTime = System.currentTimeMillis();
        this.managerAID = agent.getBlockingManagerAID();
    }

    @Override
    protected void onTick() {
        // Check for cancels (only for specific golem ids)
        MessageTemplate cancelTmpl = MessageTemplate.MatchProtocol("CANCEL-BLOCK");
        ACLMessage cancelMsg;
        while ((cancelMsg = myAgent.receive(cancelTmpl)) != null) {
            String gid = cancelMsg.getContent();
            if (targetGolems.remove(gid)) {
                System.out.println(agent.getLocalName() + " [BLOCKING] received CANCEL for " + gid);
            }
        }

        // If no more golems to block, finish
        if (targetGolems.isEmpty()) {
            System.out.println(agent.getLocalName() + " [BLOCKING] all targets cancelled, aborting.");
            finishBlocking();
            return;
        }

        processIncomingMessages();

        String myPos = ((AbstractDedaleAgent) myAgent).getCurrentPosition().getLocationId();
        if (!myPos.equals(targetNode)) {
            List<String> path = agent.getMyMap().getShortestPath(myPos, targetNode);
            if (path != null && !path.isEmpty()) {
                ((AbstractDedaleAgent) myAgent).moveTo(new GsLocation(path.get(0)));
                System.out.println(agent.getLocalName() + " [BLOCKING] moving to " + path.get(0));
            }
            return;
        }

        if (!readySent) {
            sendReadyMessage();
            readySent = true;
        }

        boolean allCaptured = targetGolems.stream().allMatch(gid -> agent.getCapturedGolems().contains(gid));
        if (allCaptured || System.currentTimeMillis() - startTime > MAX_BLOCK_TIME) {
            finishBlocking();
            return;
        }

        System.out.println(agent.getLocalName() + " [BLOCKING] holding " + targetNode);
    }

    private void sendReadyMessage() {
        if (managerAID == null) {
            System.err.println(agent.getLocalName() + " [BLOCKING] No manager AID set, cannot send READY");
            return;
        }
        ACLMessage ready = new ACLMessage(ACLMessage.INFORM);
        ready.setProtocol("BLOCK-READY");
        ready.setSender(myAgent.getAID());
        ready.addReceiver(managerAID);
        ready.setContent(agent.getLocalName() + ":" + targetNode);
        ((AbstractDedaleAgent) myAgent).sendMessage(ready);
        System.out.println(agent.getLocalName() + " [BLOCKING] READY sent to manager " + managerAID.getLocalName());
    }

    private void processIncomingMessages() {
        MessageTemplate captureTmpl = MessageTemplate.and(
                MessageTemplate.MatchProtocol("CAPTURE"),
                MessageTemplate.MatchPerformative(ACLMessage.INFORM));
        ACLMessage msg;
        while ((msg = myAgent.receive(captureTmpl)) != null) {
            try {
                String golemId = (String) msg.getContentObject();
                if (targetGolems.contains(golemId)) {
                    agent.markGolemCaptured(golemId);
                    targetGolems.remove(golemId);
                    System.out.println(agent.getLocalName() + " [BLOCKING] received CAPTURE: " + golemId);
                }
            } catch (UnreadableException e) {}
        }
    }

    private void finishBlocking() {
        // Only clear blocking targets that this behaviour owns
        agent.getBlockingTargets().removeAll(targetGolems);
        if (agent.getBlockingTargets().isEmpty()) {
            agent.setBlockingNode(null);
            agent.setBlockingManagerAID(null);
            agent.setMode(FSMExploAgent.MODE_HUNT);
        }
        myAgent.removeBehaviour(this);
    }
}