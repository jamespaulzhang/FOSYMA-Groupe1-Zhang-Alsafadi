package eu.su.mas.dedaleEtu.mas.behaviours;

import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import dataStructures.serializableGraph.SerializableSimpleGraph;
import eu.su.mas.dedale.mas.AbstractDedaleAgent;
import eu.su.mas.dedaleEtu.mas.agents.dummies.explo.FSMExploAgent;
import eu.su.mas.dedaleEtu.mas.knowledge.GolemInfo;
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
    private static final String PROTOCOL_MAP = "SHARE-MAP";

    private static final int DEFAULT_TTL = 3;  // limit hops

    public SignalBehaviour(Agent myAgent) {
        super(myAgent);
    }

    @Override
    public void action() {
        FSMExploAgent agent = (FSMExploAgent) this.myAgent;
        String myName = myAgent.getLocalName();
        String myPos = ((AbstractDedaleAgent) myAgent).getCurrentPosition().getLocationId();

        // --- 1. Capture messages (no TTL, but broadcast with range check) ---
        checkCaptureMessage(agent, myName);

        // --- 2. Position sharing (with ID + TTL) ---
        sharePosition(agent, myName, myPos);
        receivePositionMessages(agent, myName, myPos);

        // --- 3. Wumpus found (simple broadcast, TTL not critical) ---
        shareWumpusFound(agent, myName);
        receiveWumpusFound(agent, myName);

        // --- 4. Stench direction (ID + TTL) ---
        shareStenchDirection(agent, myName, myPos);
        receiveStenchDirection(agent, myName);

        // --- 5. Inside stench (ID + TTL) ---
        shareInsideStench(agent, myName, myPos);
        receiveInsideStench(agent, myName);

        // --- 6. Golem info (ID + anti-entropy) ---
        shareGolemInfo(agent, myName, myPos);
        receiveGolemInfo(agent, myName);

        // --- 7. Map sharing (only in exploration, hash-based, no TTL) ---
        shareMap(agent, myName);
        receiveMap(agent, myName);
    }

    // ======================= POSITION =========================
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
    }

    private void receivePositionMessages(FSMExploAgent agent, String myName, String myPos) {
        MessageTemplate tmpl = MessageTemplate.MatchProtocol(PROTOCOL_POSITION);
        ACLMessage msg;
        while ((msg = myAgent.receive(tmpl)) != null) {
            String msgId = msg.getConversationId();
            if (msgId == null || agent.getReceivedPositionMsgIds().contains(msgId)) {
                continue;
            }
            agent.getReceivedPositionMsgIds().add(msgId);

            String pos = msg.getContent();
            String sender = msg.getSender().getLocalName();
            agent.addPosition(pos);
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

    // ======================= STENCH DIRECTION =========================
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

    // ======================= INSIDE STENCH =========================
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

    // ======================= WUMPUS FOUND =========================
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

    // ======================= GOLEM INFO =========================
    private void shareGolemInfo(FSMExploAgent agent, String myName, String myPos) {
        if (agent.getKnownGolems().isEmpty() && agent.getCapturedGolems().isEmpty()) return;
        String msgId = agent.generateGolemInfoMsgId();
        ACLMessage msg = new ACLMessage(ACLMessage.INFORM);
        msg.setSender(myAgent.getAID());
        msg.setProtocol(PROTOCOL_GOLEM_INFO);
        msg.setConversationId(msgId);
        addReceiversInRange(msg, agent, myPos);
        if (!hasReceivers(msg)) return;

        try {
            Map<String, Object> payload = new HashMap<>();
            Map<String, String> simpleMap = new HashMap<>();
            for (GolemInfo gi : agent.getKnownGolems().values()) {
                simpleMap.put(gi.getId(), gi.getLastKnownPosition());
            }
            payload.put("golems", simpleMap);
            payload.put("captured", new ArrayList<>(agent.getCapturedGolems()));
            msg.setContentObject((Serializable) payload);
            ((AbstractDedaleAgent) myAgent).sendMessage(msg);
            System.out.println(myName + " [SEND] GOLEM_INFO: " + simpleMap + " captured=" + agent.getCapturedGolems());
        } catch (IOException e) {
            e.printStackTrace();
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
                Map<String, String> map = (Map<String, String>) payload.get("golems");
                List<String> captured = (List<String>) payload.get("captured");
                for (Map.Entry<String, String> e : map.entrySet()) {
                    agent.addOrUpdateGolem(e.getKey(), e.getValue(), false);
                }
                for (String cid : captured) {
                    if (!agent.getCapturedGolems().contains(cid)) {
                        agent.markGolemCaptured(cid);
                    }
                }
                System.out.println(myName + " [RECV] GOLEM_INFO from " + msg.getSender().getLocalName());
            } catch (UnreadableException e) {
                e.printStackTrace();
            }
        }
    }

    // ======================= MAP SHARING =========================
    private void shareMap(FSMExploAgent agent, String myName) {
        if (agent.getMode() != FSMExploAgent.MODE_EXPLORATION) return;
        int currentHash = agent.getMyMap().getContentHash();
        if (currentHash == agent.getLastMapHash()) return;
        agent.setLastMapHash(currentHash);

        ACLMessage msg = new ACLMessage(ACLMessage.INFORM);
        msg.setSender(myAgent.getAID());
        msg.setProtocol(PROTOCOL_MAP);
        addReceiversInRange(msg, agent, ((AbstractDedaleAgent)myAgent).getCurrentPosition().getLocationId());
        if (!hasReceivers(msg)) return;

        try {
            MapWithScent mws = new MapWithScent(agent.getMyMapSerial(), agent.getMyMap().getSerializableScent());
            msg.setContentObject(mws);
            ((AbstractDedaleAgent) myAgent).sendMessage(msg);
            System.out.println(myName + " [SEND] MAP (hash=" + currentHash + ")");
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void receiveMap(FSMExploAgent agent, String myName) {
        if (agent.getMode() != FSMExploAgent.MODE_EXPLORATION) return;
        MessageTemplate tmpl = MessageTemplate.MatchProtocol(PROTOCOL_MAP);
        ACLMessage msg;
        while ((msg = myAgent.receive(tmpl)) != null) {
            try {
                Object obj = msg.getContentObject();
                if (obj instanceof MapWithScent) {
                    MapWithScent mws = (MapWithScent) obj;
                    agent.getMyMap().mergeMap(mws.graph, mws.scent);
                    System.out.println(myName + " [RECV] MAP from " + msg.getSender().getLocalName());
                } else if (obj instanceof SerializableSimpleGraph) {
                    @SuppressWarnings("unchecked")
                    SerializableSimpleGraph<String, MapAttribute> sg = (SerializableSimpleGraph<String, MapAttribute>) obj;
                    agent.getMyMap().mergeMap(sg);
                }
            } catch (UnreadableException e) {
                e.printStackTrace();
            }
        }
    }

    // ======================= CAPTURE =========================
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

    // ======================= HELPER METHODS =========================
    private void addReceiversInRange(ACLMessage msg, FSMExploAgent agent, String myPos) {
        for (AID r : agent.getServices("Explorer")) {
            if (!r.equals(myAgent.getAID())) {
                msg.addReceiver(r);
            }
        }
    }

    private void addReceiversInRangeExcept(ACLMessage msg, FSMExploAgent agent, String myPos, AID exclude) {
        for (AID r : agent.getServices("Explorer")) {
            if (!r.equals(myAgent.getAID()) && !r.equals(exclude)) {
                msg.addReceiver(r);
            }
        }
    }

    private boolean hasReceivers(ACLMessage msg) {
        Iterator<?> it = msg.getAllReceiver();
        return it.hasNext();
    }
}