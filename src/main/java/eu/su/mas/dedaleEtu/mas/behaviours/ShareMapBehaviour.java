package eu.su.mas.dedaleEtu.mas.behaviours;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import eu.su.mas.dedale.mas.AbstractDedaleAgent;
import eu.su.mas.dedaleEtu.mas.knowledge.MapRepresentation;
import eu.su.mas.dedaleEtu.mas.knowledge.MapWithScent;
import jade.core.AID;
import jade.core.behaviours.TickerBehaviour;
import jade.domain.DFService;
import jade.domain.FIPAAgentManagement.DFAgentDescription;
import jade.domain.FIPAAgentManagement.ServiceDescription;
import jade.domain.FIPAException;
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

        // 获取所有探索者代理的 AID（通过 DF）
        List<AID> allExplorers = getAllExplorerAgents();
        if (allExplorers.isEmpty()) return;

        MapWithScent mws = new MapWithScent(myMap.getSerializableGraph(), myMap.getSerializableScent());
        ACLMessage msg = new ACLMessage(ACLMessage.INFORM);
        msg.setProtocol("SHARE-TOPO");
        msg.setSender(myAgent.getAID());

        // 添加所有其他探索者作为接收者
        for (AID aid : allExplorers) {
            if (!aid.equals(myAgent.getAID())) {
                msg.addReceiver(aid);
            }
        }

        // 如果没有接收者，直接返回
        if (!msg.getAllReceiver().hasNext()) return;

        try {
            msg.setContentObject(mws);
            ((AbstractDedaleAgent) myAgent).sendMessage(msg);
            // 打印日志时不要调用 msg.getReceiverCount()，改为手动计数
            int receiverCount = 0;
            var it = msg.getAllReceiver();
            while (it.hasNext()) { it.next(); receiverCount++; }
            System.out.println(myAgent.getLocalName() + " sent map to " + receiverCount + " agents (" +
                               mws.graph.getAllNodes().size() + " nodes, " + mws.scent.size() + " scent nodes)");
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * 通过 DF 服务获取所有类型为 "agentExplo" 的代理 AID。
     */
    private List<AID> getAllExplorerAgents() {
        List<AID> agents = new ArrayList<>();
        DFAgentDescription template = new DFAgentDescription();
        ServiceDescription sd = new ServiceDescription();
        sd.setType("agentExplo");   // 必须与 JSON 中的 agentType 一致
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

    public void markUpdate() {
        this.hasUpdate = true;
    }
}