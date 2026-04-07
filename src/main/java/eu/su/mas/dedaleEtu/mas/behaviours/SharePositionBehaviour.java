package eu.su.mas.dedaleEtu.mas.behaviours;

import eu.su.mas.dedale.env.Location;
import eu.su.mas.dedale.mas.AbstractDedaleAgent;
import jade.core.AID;
import jade.core.behaviours.TickerBehaviour;
import jade.domain.DFService;
import jade.domain.FIPAAgentManagement.DFAgentDescription;
import jade.domain.FIPAAgentManagement.ServiceDescription;
import jade.domain.FIPAException;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;
import jade.lang.acl.UnreadableException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 定期广播自己的位置到所有探索者（通过 DF 获取），并接收其他代理的位置，维护全局位置表。
 */
public class SharePositionBehaviour extends TickerBehaviour {
    private static final long serialVersionUID = 1L;

    private final Map<String, String> agentPositions;

    public SharePositionBehaviour(AbstractDedaleAgent agent, long period, Map<String, String> agentPositions) {
        super(agent, period);
        this.agentPositions = agentPositions;
    }

    @Override
    protected void onTick() {
        // 1. 获取自己的位置
        Location myPos = ((AbstractDedaleAgent) myAgent).getCurrentPosition();
        if (myPos == null) return;

        // 2. 通过 DF 获取所有探索者 agent 的 AID
        List<AID> allExplorers = getAllExplorerAgents();
        if (allExplorers.isEmpty()) return;

        // 3. 发送自己的位置给所有探索者
        ACLMessage msg = new ACLMessage(ACLMessage.INFORM);
        msg.setProtocol("SHARE-POSITION");
        msg.setSender(myAgent.getAID());
        for (AID aid : allExplorers) {
            if (!aid.equals(myAgent.getAID())) {
                msg.addReceiver(aid);
            }
        }
        try {
            msg.setContentObject(myPos.getLocationId());
            ((AbstractDedaleAgent) myAgent).sendMessage(msg);
        } catch (Exception e) {
            e.printStackTrace();
        }

        // 4. 接收其他代理的位置消息
        MessageTemplate tmpl = MessageTemplate.and(
                MessageTemplate.MatchProtocol("SHARE-POSITION"),
                MessageTemplate.MatchPerformative(ACLMessage.INFORM));
        ACLMessage received = myAgent.receive(tmpl);
        while (received != null) {
            try {
                String sender = received.getSender().getLocalName();
                String position = (String) received.getContentObject();
                agentPositions.put(sender, position);
            } catch (UnreadableException e) {
                e.printStackTrace();
            }
            received = myAgent.receive(tmpl);
        }
    }

    private List<AID> getAllExplorerAgents() {
        List<AID> agents = new ArrayList<>();
        DFAgentDescription template = new DFAgentDescription();
        ServiceDescription sd = new ServiceDescription();
        sd.setType("agentExplo");
        template.addServices(sd);
        try {
            DFAgentDescription[] results = DFService.search(myAgent, template);
            for (DFAgentDescription dfd : results) {
                agents.add(dfd.getName());
            }
        } catch (FIPAException e) {
            e.printStackTrace();
        }
        return agents;
    }
}