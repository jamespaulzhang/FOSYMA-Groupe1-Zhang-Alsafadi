package eu.su.mas.dedaleEtu.mas.behaviours;

import eu.su.mas.dedale.mas.AbstractDedaleAgent;
import eu.su.mas.dedaleEtu.mas.agents.dummies.explo.FSMExploAgent;
import jade.core.AID;
import jade.core.behaviours.TickerBehaviour;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;

import java.io.IOException;
import java.util.*;

public class BlockingCFPBehaviour extends TickerBehaviour {

    private static final long serialVersionUID = 1L;
    private final FSMExploAgent agent;
    private final String golemId;
    private final String golemPos;

    private enum Phase { INIT, COLLECT_PROPOSALS, DECIDE, WAIT_READY, DONE }
    private Phase currentPhase = Phase.INIT;
    private long phaseStartTime;

    private Map<String, List<Proposal>> proposals = new HashMap<>();
    private Set<AID> responders = new HashSet<>();
    private Map<String, String> assignments = null;
    private Map<String, Boolean> blockerReady = Collections.synchronizedMap(new HashMap<>());
    private static final long READY_TIMEOUT = 8000;

    private static class Proposal {
        AID bidder;
        int cost;
        Proposal(AID b, int c) { bidder = b; cost = c; }
    }

    public BlockingCFPBehaviour(FSMExploAgent agent, String golemId, String golemPos) {
        super(agent, 200);
        this.agent = agent;
        this.golemId = golemId;
        this.golemPos = golemPos;
        this.phaseStartTime = System.currentTimeMillis();
    }

    @Override
    protected void onTick() {
        switch (currentPhase) {
            case INIT:
                sendCFP();
                currentPhase = Phase.COLLECT_PROPOSALS;
                phaseStartTime = System.currentTimeMillis();
                break;

            case COLLECT_PROPOSALS:
                collectProposals();
                if (System.currentTimeMillis() - phaseStartTime > 3000) {
                    currentPhase = Phase.DECIDE;
                }
                break;

            case DECIDE:
                assignments = decideAssignments();
                List<String> neighbors = agent.getMyMap().getNeighbors(golemPos);
                if (assignments == null || assignments.isEmpty() || assignments.size() < neighbors.size()) {
                    System.out.println(agent.getLocalName() + " [CFP] incomplete coverage (" +
                            (assignments == null ? 0 : assignments.size()) + "/" + neighbors.size() + "), cancelling.");
                    cancelAndFinish();
                } else {
                    sendAward(assignments);
                    currentPhase = Phase.WAIT_READY;
                    phaseStartTime = System.currentTimeMillis();
                }
                break;

            case WAIT_READY:
                collectReadyMessages();
                boolean allReady = blockerReady.values().stream().allMatch(v -> v);
                boolean timeout = System.currentTimeMillis() - phaseStartTime > READY_TIMEOUT;
                if (allReady || timeout) {
                    if (allReady) {
                        System.out.println(agent.getLocalName() + " [CFP] all blockers ready, capturing " + golemId);
                    } else {
                        System.out.println(agent.getLocalName() + " [CFP] READY timeout, capturing anyway.");
                    }
                    captureGolem();
                    finish(true);
                }
                break;

            case DONE:
                stop();
                break;
        }
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

    private void collectProposals() {
        MessageTemplate tmpl = MessageTemplate.MatchProtocol("PROPOSE-BLOCK");
        ACLMessage msg;
        while ((msg = myAgent.receive(tmpl)) != null) {
            processProposal(msg);
        }
    }

    private void processProposal(ACLMessage msg) {
        String content = msg.getContent();
        String[] parts = content.split(":");
        if (parts.length < 3) return;
        String gid = parts[0];
        if (!gid.equals(golemId)) return;

        AID bidder = msg.getSender();
        responders.add(bidder);

        if ("none".equals(parts[2])) {
            System.out.println(agent.getLocalName() + " [CFP] received empty proposal from " + bidder.getLocalName());
            return;
        }

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

        Set<String> availableAgents = new HashSet<>();
        for (AID r : responders) availableAgents.add(r.getLocalName());
        availableAgents.add(agent.getLocalName());

        for (String node : neighbors) {
            List<Proposal> bids = proposals.getOrDefault(node, new ArrayList<>());
            Proposal best = null;
            int bestCost = Integer.MAX_VALUE;
            AID bestBidder = null;
            for (Proposal p : bids) {
                if (!assignments.containsValue(p.bidder.getLocalName()) && p.cost < bestCost) {
                    best = p;
                    bestCost = p.cost;
                    bestBidder = p.bidder;
                }
            }
            if (best != null) {
                assignments.put(node, bestBidder.getLocalName());
            }
        }

        for (String node : neighbors) {
            if (!assignments.containsKey(node) && !assignments.containsValue(agent.getLocalName())) {
                assignments.put(node, agent.getLocalName());
            }
        }

        System.out.println(agent.getLocalName() + " [CFP] Partial assignment: " + assignments.size() + "/" + neighbors.size() + " neighbors blocked.");
        return assignments;
    }

    private void sendAward(Map<String, String> assignments) {
        if (assignments.isEmpty()) return;

        ACLMessage award = new ACLMessage(ACLMessage.INFORM);
        award.setProtocol("AWARD-BLOCK");
        award.setSender(agent.getAID());
        award.addUserDefinedParameter("manager", agent.getLocalName());

        StringBuilder sb = new StringBuilder(golemId + ":");
        for (Map.Entry<String, String> e : assignments.entrySet()) {
            sb.append(e.getKey()).append("=").append(e.getValue()).append(",");
            String agentName = e.getValue();
            if (!agentName.equals(agent.getLocalName())) {
                blockerReady.put(agentName, false);
                agent.expectedAcks.put(agentName, false);
                agent.assignedBlockers.put(agentName, e.getKey());
            } else {
                blockerReady.put(agent.getLocalName(), false);
            }
        }
        if (sb.charAt(sb.length() - 1) == ',') sb.deleteCharAt(sb.length() - 1);
        award.setContent(sb.toString());

        for (AID responder : responders) award.addReceiver(responder);
        ((AbstractDedaleAgent) myAgent).sendMessage(award);
        System.out.println(agent.getLocalName() + " [AWARD] sent: " + award.getContent() + " manager=" + agent.getLocalName());
    }

    private void cancelAndFinish() {
        if (assignments != null) {
            for (String agentName : assignments.values()) {
                if (agentName.equals(agent.getLocalName())) continue;
                ACLMessage cancel = new ACLMessage(ACLMessage.INFORM);
                cancel.setProtocol("CANCEL-BLOCK");
                cancel.setContent(golemId);
                for (AID aid : agent.getServices("Explorer")) {
                    if (aid.getLocalName().equals(agentName)) cancel.addReceiver(aid);
                }
                if (cancel.getAllReceiver().hasNext()) ((AbstractDedaleAgent) myAgent).sendMessage(cancel);
            }
        }
        if (assignments != null && assignments.containsValue(agent.getLocalName())) {
            agent.getBlockingTargets().remove(golemId);
            if (agent.getBlockingTargets().isEmpty()) {
                agent.setBlockingNode(null);
                if (agent.getMode() == FSMExploAgent.MODE_BLOCKING) agent.setMode(FSMExploAgent.MODE_HUNT);
            }
        }
        finish(false);
    }

    private void collectReadyMessages() {
        MessageTemplate readyTmpl = MessageTemplate.MatchProtocol("BLOCK-READY");
        ACLMessage msg;
        while ((msg = myAgent.receive(readyTmpl)) != null) {
            String sender = msg.getSender().getLocalName();
            if (blockerReady.containsKey(sender)) {
                blockerReady.put(sender, true);
                System.out.println(agent.getLocalName() + " [CFP] received READY from " + sender);
            }
        }
    }

    private void captureGolem() {
        System.out.println(agent.getLocalName() + " [CFP] *** CAPTURE " + golemId + " ***");
        agent.markGolemCaptured(golemId);
        broadcastCapture(golemId);
    }

    private void broadcastCapture(String golemId) {
        ACLMessage msg = new ACLMessage(ACLMessage.INFORM);
        msg.setProtocol("CAPTURE");
        msg.setSender(myAgent.getAID());
        for (AID aid : agent.getServices("Explorer")) {
            if (!aid.equals(myAgent.getAID())) {
                msg.addReceiver(aid);
            }
        }
        try {
            msg.setContentObject(golemId);
            ((AbstractDedaleAgent) myAgent).sendMessage(msg);
            System.out.println(agent.getLocalName() + " [SEND] CAPTURE broadcast: " + golemId);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void finish(boolean success) {
        if (assignments != null) {
            String myAssignment = assignments.get(agent.getLocalName());
            if (myAssignment != null) {
                agent.setBlockingNode(myAssignment);
                agent.addBlockingTarget(golemId);
                agent.setBlockingManagerAID(agent.getAID());
                agent.setMode(FSMExploAgent.MODE_BLOCKING);
                System.out.println(agent.getLocalName() + " [CFP] Switching to BLOCKING mode, target node: " + myAssignment);
            }
        }
        agent.isManager = false;
        agent.activeCFPGolemId = null;
        agent.expectedAcks.clear();
        agent.assignedBlockers.clear();
        System.out.println(agent.getLocalName() + " [CFP] finished (success=" + success + ")");
        stop();
    }
}