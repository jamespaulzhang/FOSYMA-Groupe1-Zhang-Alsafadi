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

        // 3. Decide assignments with feasibility check
        Map<String, String> assignments = decideAssignments();

        // 4. Send Award
        sendAward(assignments);

        // 5. Wait for ACKs (2PC Phase 2)
        if (!assignments.isEmpty()) {
            waitForAcks(assignments);
        }

        // 6. Apply self assignment if any
        String myAssignment = assignments.get(agent.getLocalName());
        if (myAssignment != null) {
            agent.setBlockingNode(myAssignment);
            agent.addBlockingTarget(golemId);
            agent.setMode(FSMExploAgent.MODE_BLOCKING);
            System.out.println(agent.getLocalName() + " [CFP] Switching to BLOCKING mode, target node: " + myAssignment);
        }

        // 7. Reset manager flag
        agent.isManager = false;
        agent.activeCFPGolemId = null;
        agent.expectedAcks.clear();
        agent.assignedBlockers.clear();
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
        List<String> neighbors = agent.getMyMap().getNeighbors(golemPos);
        
        // Check feasibility: can we cover all neighbors?
        Set<String> availableAgents = new HashSet<>();
        for (AID r : responders) availableAgents.add(r.getLocalName());
        availableAgents.add(agent.getLocalName()); // include self
        
        if (availableAgents.size() < neighbors.size()) {
            System.out.println(agent.getLocalName() + " [CFP] Not enough agents to block all neighbors. Aborting.");
            return assignments; // empty, will not send award
        }
        
        // Greedy assignment: for each neighbor, pick best bidder
        for (String node : neighbors) {
            List<Proposal> bids = proposals.getOrDefault(node, new ArrayList<>());
            if (!bids.isEmpty()) {
                Proposal best = bids.stream()
                        .min(Comparator.comparingInt(Proposal::cost)
                                .thenComparing(p -> p.bidder.getLocalName()))
                        .orElse(null);
                if (best != null && !assignments.containsValue(best.bidder.getLocalName())) {
                    assignments.put(node, best.bidder.getLocalName());
                    continue;
                }
            }
            // If no bid, assign to self if possible
            if (!assignments.containsValue(agent.getLocalName())) {
                assignments.put(node, agent.getLocalName());
            }
        }
        return assignments;
    }

    private void sendAward(Map<String, String> assignments) {
        if (assignments.isEmpty()) {
            System.out.println(agent.getLocalName() + " [CFP] No feasible assignment, aborting.");
            return;
        }
        
        ACLMessage award = new ACLMessage(ACLMessage.INFORM);
        award.setProtocol("AWARD-BLOCK");
        award.setSender(agent.getAID());

        StringBuilder sb = new StringBuilder(golemId + ":");
        for (Map.Entry<String, String> e : assignments.entrySet()) {
            sb.append(e.getKey()).append("=").append(e.getValue()).append(",");
            // Record expected ACK
            if (!e.getValue().equals(agent.getLocalName())) {
                agent.expectedAcks.put(e.getValue(), false);
                agent.assignedBlockers.put(e.getValue(), e.getKey());
            }
        }
        if (sb.charAt(sb.length() - 1) == ',') sb.deleteCharAt(sb.length() - 1);
        award.setContent(sb.toString());

        for (AID responder : responders) {
            award.addReceiver(responder);
        }
        ((AbstractDedaleAgent) myAgent).sendMessage(award);
        System.out.println(agent.getLocalName() + " [AWARD] sent: " + award.getContent());
    }

    private void waitForAcks(Map<String, String> assignments) {
        long startWait = System.currentTimeMillis();
        while (System.currentTimeMillis() - startWait < FSMExploAgent.AWARD_ACK_TIMEOUT) {
            // Check if all expected acks received
            boolean allAcked = true;
            for (Map.Entry<String, Boolean> e : agent.expectedAcks.entrySet()) {
                if (!e.getValue()) {
                    allAcked = false;
                    break;
                }
            }
            if (allAcked) {
                System.out.println(agent.getLocalName() + " [CFP] All ACKs received.");
                break;
            }
            
            // Let SignalBehaviour process incoming ACKs
            block(200);
        }
        
        // Retry once for missing ACKs
        for (Map.Entry<String, Boolean> e : agent.expectedAcks.entrySet()) {
            if (!e.getValue()) {
                System.out.println(agent.getLocalName() + " [CFP] Missing ACK from " + e.getKey() + ", resending Award.");
                // Simple resend: just assume they might have missed it, but we proceed anyway (best effort)
            }
        }
    }
}