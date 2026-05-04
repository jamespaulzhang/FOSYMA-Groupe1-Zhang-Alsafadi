package eu.su.mas.dedaleEtu.mas.behaviours;

import java.io.IOException;
import java.io.Serializable;
import java.util.*;

import dataStructures.serializableGraph.SerializableSimpleGraph;
import eu.su.mas.dedale.mas.AbstractDedaleAgent;
import eu.su.mas.dedaleEtu.mas.agents.dummies.explo.FSMExploAgent;
import eu.su.mas.dedaleEtu.mas.knowledge.GolemInfo;
import eu.su.mas.dedaleEtu.mas.knowledge.MapDelta;
import eu.su.mas.dedaleEtu.mas.knowledge.MapRepresentation.MapAttribute;
import eu.su.mas.dedaleEtu.mas.knowledge.MapWithScent;
import jade.core.AID;
import jade.core.Agent;
import jade.core.behaviours.OneShotBehaviour;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;
import jade.lang.acl.UnreadableException;

public class SignalBehaviour extends OneShotBehaviour {

    private static final long serialVersionUID = -568863390879327961L;
    private static final String PROTOCOL_CAPTURE = "CAPTURE";
    private static final String PROTOCOL_GOLEM_INFO = "GOLEM-INFO";
    private static final String PROTOCOL_POSITION = "SHARE-POSITION";
    private static final String PROTOCOL_STENCH_DIR = "SHARE-STENCHDIRECTION";
    private static final String PROTOCOL_INSIDE_STENCH = "SHARE-INSIDESTENCH";
    private static final String PROTOCOL_WUMPUS_FOUND = "SHARE-WUMPUSFOUND";
    private static final String PROTOCOL_MAP_DELTA = "SHARE-MAP-DELTA";
    private static final String PROTOCOL_FULL_MAP = "SHARE-MAP-FULL";

    private static final String PROTOCOL_CFP = "CFP-BLOCK";
    private static final String PROTOCOL_PROPOSE = "PROPOSE-BLOCK";
    private static final String PROTOCOL_AWARD = "AWARD-BLOCK";
    private static final String PROTOCOL_AWARD_ACK = "AWARD-ACK";
    private static final String PROTOCOL_INTENT = "INTENT-TO-BLOCK";
    private static final String PROTOCOL_CANCEL = "CANCEL-BLOCK";

    private static final int DEFAULT_TTL = 3;

    public SignalBehaviour(Agent myAgent) {
        super(myAgent);
    }

    @Override
    public void action() {
        FSMExploAgent agent = (FSMExploAgent) this.myAgent;
        String myName = myAgent.getLocalName();
        String myPos = ((AbstractDedaleAgent) myAgent).getCurrentPosition().getLocationId();

        checkCaptureMessage(agent, myName);
        checkCancelMessage(agent);
        checkIntentMessage(agent);

        sharePosition(agent, myName, myPos);
        receivePositionMessages(agent, myName, myPos);

        shareWumpusFound(agent, myName);
        receiveWumpusFound(agent, myName);

        shareStenchDirection(agent, myName, myPos);
        receiveStenchDirection(agent, myName);

        shareInsideStench(agent, myName, myPos);
        receiveInsideStench(agent, myName);

        shareGolemInfo(agent, myName, myPos);
        receiveGolemInfo(agent, myName);

        shareMapDelta(agent, myName, myPos);
        receiveMapDelta(agent, myName);

        handleBlockingCFP(agent);
        handleBlockingAward(agent);
    }

    // ===================== Communication range filtering helpers =====================
    private boolean isReceivable(FSMExploAgent agent, String myPos, AID receiver, boolean allowUnknown) {
        if (receiver.equals(myAgent.getAID())) return false;
        if (agent.getCommunicationRange() <= 0) return true;

        String receiverPos = agent.getAgentPosition(receiver.getLocalName());
        if (receiverPos == null) {
            return allowUnknown;
        }
        return agent.isWithinCommunicationRange(myPos, receiverPos);
    }

    private void addReceiversInRange(ACLMessage msg, FSMExploAgent agent, String myPos) {
        for (AID r : agent.getServices("Explorer")) {
            if (isReceivable(agent, myPos, r, false)) {
                msg.addReceiver(r);
            }
        }
    }

    private void addReceiversInRangeForBroadcast(ACLMessage msg, FSMExploAgent agent, String myPos) {
        for (AID r : agent.getServices("Explorer")) {
            if (r.equals(myAgent.getAID())) continue;
            if (isReceivable(agent, myPos, r, true)) {
                msg.addReceiver(r);
            }
        }
    }

    private void addReceiversInRangeExcept(ACLMessage msg, FSMExploAgent agent, String myPos, AID exclude) {
        for (AID r : agent.getServices("Explorer")) {
            if (r.equals(exclude)) continue;
            if (isReceivable(agent, myPos, r, false)) {
                msg.addReceiver(r);
            }
        }
    }

    private boolean hasReceivers(ACLMessage msg) {
        Iterator<?> it = msg.getAllReceiver();
        return it.hasNext();
    }

    // ===================== Sharing methods =====================
    private void shareMapDelta(FSMExploAgent agent, String myName, String myPos) {
        MapDelta delta = agent.getMyMap().getPendingDelta();
        if (delta == null || delta.isEmpty()) return;

        ACLMessage msg = new ACLMessage(ACLMessage.INFORM);
        msg.setSender(myAgent.getAID());
        msg.setProtocol(PROTOCOL_MAP_DELTA);
        addReceiversInRangeForBroadcast(msg, agent, myPos);
        if (!hasReceivers(msg)) return;

        try {
            msg.setContentObject(delta);
            ((AbstractDedaleAgent) myAgent).sendMessage(msg);
            System.out.println(myName + " [SEND] MAP_DELTA: " + delta.newEdges.size() + " edges");
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void sharePosition(FSMExploAgent agent, String myName, String myPos) {
        String msgId = agent.generatePositionMsgId();
        ACLMessage msg = new ACLMessage(ACLMessage.INFORM);
        msg.setSender(myAgent.getAID());
        msg.setProtocol(PROTOCOL_POSITION);
        msg.setConversationId(msgId);
        msg.addUserDefinedParameter("TTL", String.valueOf(DEFAULT_TTL));
        msg.setContent(myPos);

        addReceiversInRange(msg, agent, myPos);
        if (hasReceivers(msg)) {
            ((AbstractDedaleAgent) myAgent).sendMessage(msg);
            System.out.println(myName + " [SEND] POSITION: " + myPos + " ID=" + msgId);
        }
        agent.addPosition(myPos);
        agent.updateAgentPosition(myName, myPos);
    }

    private void shareStenchDirection(FSMExploAgent agent, String myName, String myPos) {
        if (agent.getOwnStenchDirection() == null) return;
        String content = agent.getOwnStenchDirection();
        String msgId = agent.generateStenchMsgId("DIR");
        ACLMessage msg = new ACLMessage(ACLMessage.INFORM);
        msg.setSender(myAgent.getAID());
        msg.setProtocol(PROTOCOL_STENCH_DIR);
        msg.setConversationId(msgId);
        msg.addUserDefinedParameter("TTL", String.valueOf(DEFAULT_TTL));
        msg.setContent(content);
        addReceiversInRange(msg, agent, myPos);
        if (hasReceivers(msg)) {
            ((AbstractDedaleAgent) myAgent).sendMessage(msg);
            System.out.println(myName + " [SEND] STENCH_DIR: " + content);
        }
    }

    private void shareInsideStench(FSMExploAgent agent, String myName, String myPos) {
        if (agent.getOwnInsideStench() == null) return;
        String content = agent.getOwnInsideStench();
        String msgId = agent.generateStenchMsgId("IN");
        ACLMessage msg = new ACLMessage(ACLMessage.INFORM);
        msg.setSender(myAgent.getAID());
        msg.setProtocol(PROTOCOL_INSIDE_STENCH);
        msg.setConversationId(msgId);
        msg.addUserDefinedParameter("TTL", String.valueOf(DEFAULT_TTL));
        msg.setContent(content);
        addReceiversInRange(msg, agent, myPos);
        if (hasReceivers(msg)) {
            ((AbstractDedaleAgent) myAgent).sendMessage(msg);
            System.out.println(myName + " [SEND] INSIDE_STENCH: " + content);
        }
    }

    private void shareWumpusFound(FSMExploAgent agent, String myName) {
        if (!agent.getPosition().isEmpty() && agent.getWumpusCnt() > 100) {
            ACLMessage msg = new ACLMessage(ACLMessage.INFORM);
            msg.setSender(myAgent.getAID());
            msg.setProtocol(PROTOCOL_WUMPUS_FOUND);
            msg.setContent("WUMPUS");
            addReceiversInRange(msg, agent, ((AbstractDedaleAgent)myAgent).getCurrentPosition().getLocationId());
            if (hasReceivers(msg)) {
                ((AbstractDedaleAgent) myAgent).sendMessage(msg);
                System.out.println(myName + " [SEND] WUMPUS_FOUND");
            }
        }
    }

    private void shareGolemInfo(FSMExploAgent agent, String myName, String myPos) {
        if (agent.getKnownGolems().isEmpty() && agent.getCapturedGolems().isEmpty()) return;

        String msgId = agent.generateGolemInfoMsgId();
        ACLMessage msg = new ACLMessage(ACLMessage.INFORM);
        msg.setSender(myAgent.getAID());
        msg.setProtocol(PROTOCOL_GOLEM_INFO);
        msg.setConversationId(msgId);
        addReceiversInRangeForBroadcast(msg, agent, myPos);
        if (!hasReceivers(msg)) return;

        try {
            Map<String, Object> payload = new HashMap<>();
            List<Map<String, Object>> golemDataList = new ArrayList<>();

            List<GolemInfo> snapshot = new ArrayList<>(agent.getKnownGolems().values());
            for (GolemInfo gi : snapshot) {
                Map<String, Object> giData = new HashMap<>();
                giData.put("id", gi.getId());
                giData.put("pos", gi.getLastKnownPosition());
                giData.put("confirmed", gi.isConfirmed());
                giData.put("timestamp", gi.getTimestamp());
                golemDataList.add(giData);
            }

            payload.put("golems", golemDataList);
            payload.put("captured", new ArrayList<>(agent.getCapturedGolems()));
            msg.setContentObject((Serializable) payload);
            ((AbstractDedaleAgent) myAgent).sendMessage(msg);
            System.out.println(myName + " [SEND] GOLEM_INFO: " + golemDataList + " captured=" + agent.getCapturedGolems());
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    // ===================== Receiving methods =====================
    private void receivePositionMessages(FSMExploAgent agent, String myName, String myPos) {
        MessageTemplate tmpl = MessageTemplate.MatchProtocol(PROTOCOL_POSITION);
        ACLMessage msg;
        while ((msg = myAgent.receive(tmpl)) != null) {
            String msgId = msg.getConversationId();
            if (msgId == null || agent.getReceivedPositionMsgIds().contains(msgId)) continue;
            agent.getReceivedPositionMsgIds().add(msgId);

            String pos = msg.getContent();
            String sender = msg.getSender().getLocalName();
            agent.addPosition(pos);
            agent.updateAgentPosition(sender, pos);
            System.out.println(myName + " [RECV] POSITION from " + sender + ": " + pos + " ID=" + msgId);

            String ttlStr = msg.getUserDefinedParameter("TTL");
            int ttl = (ttlStr == null) ? DEFAULT_TTL : Integer.parseInt(ttlStr);
            if (ttl > 1) {
                ACLMessage relay = new ACLMessage(ACLMessage.INFORM);
                relay.setSender(myAgent.getAID());
                relay.setProtocol(PROTOCOL_POSITION);
                relay.setConversationId(msgId);
                relay.addUserDefinedParameter("TTL", String.valueOf(ttl - 1));
                relay.setContent(pos);
                addReceiversInRangeExcept(relay, agent, myPos, msg.getSender());
                if (hasReceivers(relay)) {
                    ((AbstractDedaleAgent) myAgent).sendMessage(relay);
                    System.out.println(myName + " [RELAY] POSITION from " + sender + " (TTL=" + (ttl-1) + ")");
                }
            }
        }
    }

    private void receiveStenchDirection(FSMExploAgent agent, String myName) {
        MessageTemplate tmpl = MessageTemplate.MatchProtocol(PROTOCOL_STENCH_DIR);
        ACLMessage msg;
        while ((msg = myAgent.receive(tmpl)) != null) {
            String msgId = msg.getConversationId();
            if (msgId == null || agent.getReceivedStenchMsgIds().contains(msgId)) continue;
            agent.getReceivedStenchMsgIds().add(msgId);

            String sd = msg.getContent();
            agent.addStenchDirection(sd);
            System.out.println(myName + " [RECV] STENCH_DIR: " + sd + " from " + msg.getSender().getLocalName());

            String ttlStr = msg.getUserDefinedParameter("TTL");
            int ttl = (ttlStr == null) ? DEFAULT_TTL : Integer.parseInt(ttlStr);
            if (ttl > 1) {
                ACLMessage relay = new ACLMessage(ACLMessage.INFORM);
                relay.setSender(myAgent.getAID());
                relay.setProtocol(PROTOCOL_STENCH_DIR);
                relay.setConversationId(msgId);
                relay.addUserDefinedParameter("TTL", String.valueOf(ttl - 1));
                relay.setContent(sd);
                addReceiversInRangeExcept(relay, agent, ((AbstractDedaleAgent)myAgent).getCurrentPosition().getLocationId(), msg.getSender());
                if (hasReceivers(relay)) {
                    ((AbstractDedaleAgent) myAgent).sendMessage(relay);
                }
            }
        }
    }

    private void receiveInsideStench(FSMExploAgent agent, String myName) {
        MessageTemplate tmpl = MessageTemplate.MatchProtocol(PROTOCOL_INSIDE_STENCH);
        ACLMessage msg;
        while ((msg = myAgent.receive(tmpl)) != null) {
            String msgId = msg.getConversationId();
            if (msgId == null || agent.getReceivedStenchMsgIds().contains(msgId)) continue;
            agent.getReceivedStenchMsgIds().add(msgId);

            String is = msg.getContent();
            agent.addInsideStench(is);
            System.out.println(myName + " [RECV] INSIDE_STENCH: " + is + " from " + msg.getSender().getLocalName());

            String ttlStr = msg.getUserDefinedParameter("TTL");
            int ttl = (ttlStr == null) ? DEFAULT_TTL : Integer.parseInt(ttlStr);
            if (ttl > 1) {
                ACLMessage relay = new ACLMessage(ACLMessage.INFORM);
                relay.setSender(myAgent.getAID());
                relay.setProtocol(PROTOCOL_INSIDE_STENCH);
                relay.setConversationId(msgId);
                relay.addUserDefinedParameter("TTL", String.valueOf(ttl - 1));
                relay.setContent(is);
                addReceiversInRangeExcept(relay, agent, ((AbstractDedaleAgent)myAgent).getCurrentPosition().getLocationId(), msg.getSender());
                if (hasReceivers(relay)) {
                    ((AbstractDedaleAgent) myAgent).sendMessage(relay);
                }
            }
        }
    }

    private void receiveWumpusFound(FSMExploAgent agent, String myName) {
        MessageTemplate tmpl = MessageTemplate.MatchProtocol(PROTOCOL_WUMPUS_FOUND);
        ACLMessage msg;
        while ((msg = myAgent.receive(tmpl)) != null) {
            System.out.println(myName + " [RECV] WUMPUS_FOUND from " + msg.getSender().getLocalName());
            if (agent.getWumpusCnt() < 10) {
                agent.increaseGetoutCnt();
                agent.setDest_wumpusfound(true);
                String myPosNow = ((AbstractDedaleAgent) myAgent).getCurrentPosition().getLocationId();
                String pos = null;
                while (pos == null || pos.equals(myPosNow)) {
                    pos = agent.getMyMap().getRandomNode();
                }
                agent.setDestination(pos);
            }
        }
    }

    private void receiveGolemInfo(FSMExploAgent agent, String myName) {
        MessageTemplate tmpl = MessageTemplate.MatchProtocol(PROTOCOL_GOLEM_INFO);
        ACLMessage msg;
        while ((msg = myAgent.receive(tmpl)) != null) {
            String msgId = msg.getConversationId();
            if (msgId != null && agent.getReceivedGolemInfoMsgIds().contains(msgId)) continue;
            if (msgId != null) agent.getReceivedGolemInfoMsgIds().add(msgId);

            try {
                Map<String, Object> payload = (Map<String, Object>) msg.getContentObject();
                List<Map<String, Object>> golemDataList = (List<Map<String, Object>>) payload.get("golems");
                List<String> captured = (List<String>) payload.get("captured");

                for (String cid : captured) {
                    if (!agent.getCapturedGolems().contains(cid)) {
                        agent.markGolemCaptured(cid);
                    }
                }

                for (Map<String, Object> giData : golemDataList) {
                    String id = (String) giData.get("id");
                    String pos = (String) giData.get("pos");
                    boolean confirmed = (Boolean) giData.get("confirmed");
                    long timestamp = (Long) giData.get("timestamp");

                    agent.addOrUpdateGolem(id, pos, confirmed);
                }

                System.out.println(myName + " [RECV] GOLEM_INFO from " + msg.getSender().getLocalName());
            } catch (UnreadableException e) {
                e.printStackTrace();
            }
        }
    }

    private void checkCaptureMessage(FSMExploAgent agent, String myName) {
        MessageTemplate tmpl = MessageTemplate.and(
                MessageTemplate.MatchProtocol(PROTOCOL_CAPTURE),
                MessageTemplate.MatchPerformative(ACLMessage.INFORM));
        ACLMessage msg = myAgent.receive(tmpl);
        if (msg != null) {
            try {
                String golemId = (String) msg.getContentObject();
                System.out.println(myName + " [RECV] CAPTURE: " + golemId);
                agent.markGolemCaptured(golemId);
            } catch (UnreadableException e) {
                e.printStackTrace();
            }
        }
    }

    private void checkCancelMessage(FSMExploAgent agent) {
        MessageTemplate tmpl = MessageTemplate.MatchProtocol(PROTOCOL_CANCEL);
        ACLMessage msg = myAgent.receive(tmpl);
        if (msg != null) {
            String golemId = msg.getContent();
            if (agent.getMode() == FSMExploAgent.MODE_BLOCKING && agent.getBlockingTargets().contains(golemId)) {
                System.out.println(agent.getLocalName() + " [CANCEL] received for " + golemId + ", removing from targets.");
                agent.getBlockingTargets().remove(golemId);
                if (agent.getBlockingTargets().isEmpty()) {
                    agent.setBlockingNode(null);
                    agent.setMode(FSMExploAgent.MODE_HUNT);
                }
            }
        }
    }

    private void checkIntentMessage(FSMExploAgent agent) {
        MessageTemplate tmpl = MessageTemplate.MatchProtocol(PROTOCOL_INTENT);
        ACLMessage msg;
        while ((msg = myAgent.receive(tmpl)) != null) {
            String content = msg.getContent();
            String[] parts = content.split(":");
            if (parts.length == 2) {
                String golemId = parts[0];
                String agentName = parts[1];
                agent.activeIntents.computeIfAbsent(golemId,
                        k -> Collections.synchronizedSet(new HashSet<>())).add(agentName);
                System.out.println(agent.getLocalName() + " [INTENT] received from " + agentName + " for " + golemId);
            }
        }
    }

    private void handleBlockingCFP(FSMExploAgent agent) {
        MessageTemplate tmpl = MessageTemplate.MatchProtocol(PROTOCOL_CFP);
        ACLMessage cfp;
        while ((cfp = myAgent.receive(tmpl)) != null) {
            String content = cfp.getContent();
            String[] parts = content.split(":");
            if (parts.length < 2) continue;
            String golemId = parts[0];
            String[] neighbors = parts[1].split(",");
            String myPos = ((AbstractDedaleAgent) agent).getCurrentPosition().getLocationId();

            Map<String, Integer> costs = new HashMap<>();
            for (String node : neighbors) {
                if (node.equals(myPos)) continue;
                List<String> path = agent.getMyMap().getShortestPath(myPos, node);
                if (path != null) {
                    costs.put(node, path.size());
                }
            }

            ACLMessage propose = cfp.createReply();
            propose.setPerformative(ACLMessage.PROPOSE);
            propose.setProtocol(PROTOCOL_PROPOSE);
            propose.setSender(agent.getAID());

            StringBuilder sb = new StringBuilder(golemId + ":");
            if (!costs.isEmpty()) {
                for (Map.Entry<String, Integer> e : costs.entrySet()) {
                    sb.append(e.getKey()).append("=").append(e.getValue()).append(",");
                }
                sb.deleteCharAt(sb.length() - 1);
            } else {
                sb.append("none");
            }
            propose.setContent(sb.toString());
            ((AbstractDedaleAgent) myAgent).sendMessage(propose);
            System.out.println(agent.getLocalName() + " [PROPOSE] for " + golemId + ": " + sb.toString());
        }
    }

    private void handleBlockingAward(FSMExploAgent agent) {
        MessageTemplate tmpl = MessageTemplate.MatchProtocol(PROTOCOL_AWARD);
        ACLMessage award;
        while ((award = myAgent.receive(tmpl)) != null) {
            String content = award.getContent();
            String[] parts = content.split(":");
            if (parts.length < 2) continue;
            String golemId = parts[0];
            String[] assignments = parts[1].split(",");

            String managerName = award.getUserDefinedParameter("manager");
            if (managerName != null) {
                agent.setBlockingManagerAID(new AID(managerName, AID.ISLOCALNAME));
            }

            for (String assign : assignments) {
                String[] pair = assign.split("=");
                if (pair.length == 2) {
                    String node = pair[0];
                    String agentName = pair[1];
                    if (agentName.equals(agent.getLocalName())) {
                        agent.setBlockingNode(node);
                        agent.addBlockingTarget(golemId);
                        agent.setMode(FSMExploAgent.MODE_BLOCKING);
                        System.out.println(agent.getLocalName() + " [AWARD] assigned to block " + node + " for Golem " + golemId);

                        ACLMessage ack = award.createReply();
                        ack.setPerformative(ACLMessage.INFORM);
                        ack.setProtocol(PROTOCOL_AWARD_ACK);
                        ack.setContent("ACK");
                        ((AbstractDedaleAgent) myAgent).sendMessage(ack);
                        break;
                    }
                }
            }
        }
    }

    private void receiveMapDelta(FSMExploAgent agent, String myName) {
        MessageTemplate tmpl = MessageTemplate.MatchProtocol(PROTOCOL_MAP_DELTA);
        ACLMessage msg;
        while ((msg = myAgent.receive(tmpl)) != null) {
            try {
                MapDelta delta = (MapDelta) msg.getContentObject();
                boolean changed = agent.getMyMap().applyDelta(delta);
                if (changed) {
                    System.out.println(myName + " [RECV] MAP_DELTA from " + msg.getSender().getLocalName());
                }
            } catch (UnreadableException e) {
                e.printStackTrace();
            }
        }
    }
}