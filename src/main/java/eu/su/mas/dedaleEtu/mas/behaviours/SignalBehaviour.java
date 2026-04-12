package eu.su.mas.dedaleEtu.mas.behaviours;

import java.io.IOException;
import java.util.Iterator;

import dataStructures.serializableGraph.SerializableSimpleGraph;
import eu.su.mas.dedale.mas.AbstractDedaleAgent;
import eu.su.mas.dedaleEtu.mas.agents.dummies.explo.FSMExploAgent;
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

    public SignalBehaviour(Agent myAgent) {
        super(myAgent);
    }

    @Override
    public void action() {
        FSMExploAgent agent = (FSMExploAgent) this.myAgent;
        String myName = myAgent.getLocalName();
        AID[] receivers;
        boolean inboxEmpty;

        // 检查捕获消息（若收到则终止）
        if (checkCaptureMessage(agent, myName)) {
            agent.setMode(FSMExploAgent.MODE_CAPTURED);
            return;
        }

        // 1. 发送我的位置
        ACLMessage msgPos = new ACLMessage(ACLMessage.INFORM);
        msgPos.setSender(myAgent.getAID());
        msgPos.setProtocol("SHARE-POSITION");
        receivers = agent.getServices("Explorer");
        for (AID r : receivers) {
            if (!r.equals(myAgent.getAID())) msgPos.addReceiver(r);
        }
        String myPosStr = ((AbstractDedaleAgent) myAgent).getCurrentPosition().getLocationId();
        msgPos.setContent(myPosStr);
        ((AbstractDedaleAgent) myAgent).sendMessage(msgPos);
        System.out.println(myName + " [SEND] POSITION: " + myPosStr);

        // 2. 接收位置
        inboxEmpty = false;
        do {
            MessageTemplate tmpl = MessageTemplate.MatchProtocol("SHARE-POSITION");
            ACLMessage msg = myAgent.receive(tmpl);
            if (msg != null) {
                String pos = msg.getContent();
                String sender = msg.getSender().getLocalName();
                if (pos != null) agent.addPosition(pos);
                System.out.println(myName + " [RECV] POSITION from " + sender + ": " + pos);
            } else inboxEmpty = true;
        } while (!inboxEmpty);

        // 3. 发送 Wumpus 发现 (如果卡住)
        if (!agent.getPosition().isEmpty() && agent.getWumpusCnt() > 100) {
            ACLMessage msgW = new ACLMessage(ACLMessage.INFORM);
            msgW.setSender(myAgent.getAID());
            msgW.setProtocol("SHARE-WUMPUSFOUND");
            receivers = agent.getServices("Explorer");
            for (AID r : receivers) if (!r.equals(myAgent.getAID())) msgW.addReceiver(r);
            msgW.setContent("WUMPUS");
            ((AbstractDedaleAgent) myAgent).sendMessage(msgW);
            System.out.println(myName + " [SEND] WUMPUS_FOUND (stuck)");
        }

        // 4. 接收 Wumpus 发现
        inboxEmpty = false;
        do {
            MessageTemplate tmpl = MessageTemplate.MatchProtocol("SHARE-WUMPUSFOUND");
            ACLMessage msg = myAgent.receive(tmpl);
            if (msg != null) {
                String sender = msg.getSender().getLocalName();
                System.out.println(myName + " [RECV] WUMPUS_FOUND from " + sender);
                if (agent.getWumpusCnt() < 10) {
                    agent.increaseGetoutCnt();
                    agent.setDest_wumpusfound(true);
                    String myPos = ((AbstractDedaleAgent) myAgent).getCurrentPosition().getLocationId();
                    String pos = null;
                    while (pos == null || pos.equals(myPos)) {
                        pos = agent.getMyMap().getRandomNode();
                    }
                    agent.setDestination(pos);
                }
            } else inboxEmpty = true;
        } while (!inboxEmpty);

        // 5. 发送气味方向
        if (agent.getOwnStenchDirection() != null) {
            ACLMessage msgSD = new ACLMessage(ACLMessage.INFORM);
            msgSD.setSender(myAgent.getAID());
            msgSD.setProtocol("SHARE-STENCHDIRECTION");
            receivers = agent.getServices("Explorer");
            for (AID r : receivers) if (!r.equals(myAgent.getAID())) msgSD.addReceiver(r);
            msgSD.setContent(agent.getOwnStenchDirection());
            ((AbstractDedaleAgent) myAgent).sendMessage(msgSD);
            System.out.println(myName + " [SEND] STENCH_DIRECTION: " + agent.getOwnStenchDirection());
        }

        // 6. 发送内部气味
        if (agent.getOwnInsideStench() != null) {
            ACLMessage msgIS = new ACLMessage(ACLMessage.INFORM);
            msgIS.setSender(myAgent.getAID());
            msgIS.setProtocol("SHARE-INSIDESTENCH");
            receivers = agent.getServices("Explorer");
            for (AID r : receivers) if (!r.equals(myAgent.getAID())) msgIS.addReceiver(r);
            msgIS.setContent(agent.getOwnInsideStench());
            ((AbstractDedaleAgent) myAgent).sendMessage(msgIS);
            System.out.println(myName + " [SEND] INSIDE_STENCH: " + agent.getOwnInsideStench());
        }

        // 7. 接收气味方向
        inboxEmpty = false;
        do {
            MessageTemplate tmpl = MessageTemplate.MatchProtocol("SHARE-STENCHDIRECTION");
            ACLMessage msg = myAgent.receive(tmpl);
            if (msg != null) {
                String sd = msg.getContent();
                String sender = msg.getSender().getLocalName();
                if (sd != null) agent.addStenchDirection(sd);
                System.out.println(myName + " [RECV] STENCH_DIRECTION from " + sender + ": " + sd);
            } else inboxEmpty = true;
        } while (!inboxEmpty);

        // 8. 接收内部气味
        inboxEmpty = false;
        do {
            MessageTemplate tmpl = MessageTemplate.MatchProtocol("SHARE-INSIDESTENCH");
            ACLMessage msg = myAgent.receive(tmpl);
            if (msg != null) {
                String is = msg.getContent();
                String sender = msg.getSender().getLocalName();
                if (is != null) agent.addInsideStench(is);
                System.out.println(myName + " [RECV] INSIDE_STENCH from " + sender + ": " + is);
            } else inboxEmpty = true;
        } while (!inboxEmpty);

        // 9. 按需共享地图（仅探索模式，且内容变化时）
        if (agent.getMode() == FSMExploAgent.MODE_EXPLORATION) {
            int currentHash = agent.getMyMap().getContentHash();
            if (currentHash != agent.getLastMapHash()) {
                agent.setLastMapHash(currentHash);
                ACLMessage msgMap = new ACLMessage(ACLMessage.INFORM);
                msgMap.setSender(myAgent.getAID());
                msgMap.setProtocol("SHARE-MAP");
                receivers = agent.getServices("Explorer");
                for (AID r : receivers) if (!r.equals(myAgent.getAID())) msgMap.addReceiver(r);
                try {
                    MapWithScent mws = new MapWithScent(
                        agent.getMyMapSerial(),
                        agent.getMyMap().getSerializableScent()
                    );
                    msgMap.setContentObject(mws);
                    ((AbstractDedaleAgent) myAgent).sendMessage(msgMap);
                    int recvCount = 0;
                    Iterator<?> it = msgMap.getAllReceiver();
                    while (it.hasNext()) { it.next(); recvCount++; }
                    System.out.println(myName + " [SEND] MAP (hash=" + currentHash + ") to " + recvCount +
                            " agents. Nodes: " + mws.graph.getAllNodes().size() + ", Scent: " + mws.scent.size());
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }

        // 10. 接收并合并地图（仅探索模式）
        if (agent.getMode() == FSMExploAgent.MODE_EXPLORATION) {
            inboxEmpty = false;
            do {
                MessageTemplate tmpl = MessageTemplate.MatchProtocol("SHARE-MAP");
                ACLMessage msg = myAgent.receive(tmpl);
                if (msg != null) {
                    String sender = msg.getSender().getLocalName();
                    try {
                        Object obj = msg.getContentObject();
                        if (obj instanceof MapWithScent) {
                            MapWithScent mws = (MapWithScent) obj;
                            agent.getMyMap().mergeMap(mws.graph, mws.scent);
                            System.out.println(myName + " [RECV] MAP from " + sender +
                                    ". Nodes: " + mws.graph.getAllNodes().size() + ", Scent: " + mws.scent.size());
                        } else if (obj instanceof SerializableSimpleGraph) {
                            @SuppressWarnings("unchecked")
                            SerializableSimpleGraph<String, MapAttribute> sg =
                                (SerializableSimpleGraph<String, MapAttribute>) obj;
                            agent.getMyMap().mergeMap(sg);
                            System.out.println(myName + " [RECV] MAP (legacy) from " + sender);
                        }
                    } catch (UnreadableException e) {
                        e.printStackTrace();
                    }
                } else inboxEmpty = true;
            } while (!inboxEmpty);
        }
    }

    private boolean checkCaptureMessage(FSMExploAgent agent, String myName) {
        MessageTemplate tmpl = MessageTemplate.and(
                MessageTemplate.MatchProtocol(PROTOCOL_CAPTURE),
                MessageTemplate.MatchPerformative(ACLMessage.INFORM));
        ACLMessage msg = myAgent.receive(tmpl);
        if (msg != null) {
            System.out.println(myName + " [RECV] CAPTURE from " + msg.getSender().getLocalName());
            return true;
        }
        return false;
    }
}