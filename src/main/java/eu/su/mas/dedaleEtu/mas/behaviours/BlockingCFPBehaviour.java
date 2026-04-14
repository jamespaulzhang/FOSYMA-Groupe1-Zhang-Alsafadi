package eu.su.mas.dedaleEtu.mas.behaviours;

import eu.su.mas.dedale.mas.AbstractDedaleAgent;
import eu.su.mas.dedaleEtu.mas.agents.dummies.explo.FSMExploAgent;
import jade.core.AID;
import jade.core.behaviours.OneShotBehaviour;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;

import java.util.*;

public class BlockingCFPBehaviour extends OneShotBehaviour {

    private static final long serialVersionUID = 1L;
    private final FSMExploAgent agent;
    private final String golemId;
    private final String golemPos;
    private final long startTime;
    private Map<String, List<Proposal>> proposals = new HashMap<>();
    private Set<AID> responders = new HashSet<>();

    private static class Proposal {
        AID bidder;
        int cost;
        Proposal(AID b, int c) { bidder = b; cost = c; }
        int cost() { return cost; }
    }

    public BlockingCFPBehaviour(FSMExploAgent agent, String golemId, String golemPos) {
        super(agent);
        this.agent = agent;
        this.golemId = golemId;
        this.golemPos = golemPos;
        this.startTime = System.currentTimeMillis();
    }

    @Override
    public void action() {
        // 1. Send CFP
        sendCFP();

        // 2. Collect proposals until timeout
        long elapsed = System.currentTimeMillis() - startTime;
        while (elapsed < FSMExploAgent.CFP_TIMEOUT) {
            ACLMessage msg = myAgent.receive(MessageTemplate.MatchProtocol("PROPOSE-BLOCK"));
            if (msg != null) {
                processProposal(msg);
            } else {
                block(100);
            }
            elapsed = System.currentTimeMillis() - startTime;
        }

        // 3. Decide assignments
        Map<String, String> assignments = decideAssignments();

        // 4. Send Award
        sendAward(assignments);

        // 5. Apply self assignment if any
        String myAssignment = assignments.get(agent.getLocalName());
        if (myAssignment != null) {
            agent.setBlockingNode(myAssignment);
            agent.addBlockingTarget(golemId);
            agent.setMode(FSMExploAgent.MODE_BLOCKING);
            System.out.println(agent.getLocalName() + " [CFP] Switching to BLOCKING mode, target node: " + myAssignment);
        }

        // 6. Reset manager flag
        agent.isManager = false;
        agent.activeCFPGolemId = null;
    }

    private void sendCFP() {
        ACLMessage cfp = new ACLMessage(ACLMessage.CFP);
        cfp.setProtocol("CFP-BLOCK");
        cfp.setSender(agent.getAID());

        List<String> neighbors = agent.getMyMap().getNeighbors(golemPos);
        String content = golemId + ":" + String.join(",", neighbors);
        cfp.setContent(content);

        for (AID aid : agent.getServices("Explorer")) {
            if (!aid.equals(agent.getAID())) {
                cfp.addReceiver(aid);
            }
        }
        ((AbstractDedaleAgent) myAgent).sendMessage(cfp);
        System.out.println(agent.getLocalName() + " [CFP] sent for Golem " + golemId + " neighbors: " + neighbors);
    }

    private void processProposal(ACLMessage msg) {
        String content = msg.getContent();
        String[] parts = content.split(":");
        if (parts.length < 3) return;
        String gid = parts[0];
        if (!gid.equals(golemId)) return;

        AID bidder = msg.getSender();
        responders.add(bidder);

        String[] nodeCostPairs = parts[2].split(",");
        for (String pair : nodeCostPairs) {
            String[] nc = pair.split("=");
            if (nc.length == 2) {
                String node = nc[0];
                int cost = Integer.parseInt(nc[1]);
                proposals.computeIfAbsent(node, k -> new ArrayList<>())
                        .add(new Proposal(bidder, cost));
            }
        }
        System.out.println(agent.getLocalName() + " [CFP] received proposal from " + bidder.getLocalName());
    }

    private Map<String, String> decideAssignments() {
        Map<String, String> assignments = new HashMap<>();
        
        for (Map.Entry<String, List<Proposal>> entry : proposals.entrySet()) {
            String node = entry.getKey();
            Proposal best = entry.getValue().stream()
                    .min(Comparator.comparingInt(Proposal::cost)
                            .thenComparing(p -> p.bidder.getLocalName()))
                    .orElse(null);
            if (best != null) {
                assignments.put(node, best.bidder.getLocalName());
            }
        }

        if (assignments.isEmpty()) {
            System.out.println(agent.getLocalName() + " [CFP] No proposals received, assigning self to a neighbor.");
            String myPos = ((AbstractDedaleAgent) myAgent).getCurrentPosition().getLocationId();
            List<String> neighbors = agent.getMyMap().getNeighbors(golemPos);
            
            String bestNode = null;
            int minDist = Integer.MAX_VALUE;
            for (String nb : neighbors) {
                if (nb.equals(myPos)) continue;
                List<String> path = agent.getMyMap().getShortestPath(myPos, nb);
                if (path != null && path.size() < minDist) {
                    minDist = path.size();
                    bestNode = nb;
                }
            }
            
            if (bestNode != null) {
                assignments.put(bestNode, agent.getLocalName());
                System.out.println(agent.getLocalName() + " [CFP] Self-assigned to block " + bestNode);
            }
        }

        return assignments;
    }

    private void sendAward(Map<String, String> assignments) {
        ACLMessage award = new ACLMessage(ACLMessage.INFORM);
        award.setProtocol("AWARD-BLOCK");
        award.setSender(agent.getAID());

        StringBuilder sb = new StringBuilder(golemId + ":");
        for (Map.Entry<String, String> e : assignments.entrySet()) {
            sb.append(e.getKey()).append("=").append(e.getValue()).append(",");
        }
        if (sb.charAt(sb.length() - 1) == ',') sb.deleteCharAt(sb.length() - 1);
        award.setContent(sb.toString());

        for (AID responder : responders) {
            award.addReceiver(responder);
        }
        if (!assignments.isEmpty()) {
            ((AbstractDedaleAgent) myAgent).sendMessage(award);
            System.out.println(agent.getLocalName() + " [AWARD] sent: " + award.getContent());
        }
    }
}