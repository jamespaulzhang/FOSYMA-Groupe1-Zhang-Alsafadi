package eu.su.mas.dedaleEtu.mas.agents.dummies.explo;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import eu.su.mas.dedale.mas.AbstractDedaleAgent;
import eu.su.mas.dedale.mas.agent.behaviours.platformManagment.StartMyBehaviours;
import eu.su.mas.dedaleEtu.mas.behaviours.ExploCoopBehaviour;
import eu.su.mas.dedaleEtu.mas.behaviours.ShareMapBehaviour;
import eu.su.mas.dedaleEtu.mas.behaviours.SharePositionBehaviour;
import eu.su.mas.dedaleEtu.mas.knowledge.MapRepresentation;
import jade.core.behaviours.Behaviour;
import jade.domain.DFService;
import jade.domain.FIPAAgentManagement.DFAgentDescription;
import jade.domain.FIPAAgentManagement.ServiceDescription;
import jade.domain.FIPAException;

public class ExploreCoopAgent extends AbstractDedaleAgent {

    private static final long serialVersionUID = -7969469610241668140L;

    private MapRepresentation myMap;
    private Map<String, String> agentPositions = new ConcurrentHashMap<>();

    @Override
    protected void setup() {
        super.setup();

        myMap = new MapRepresentation(getLocalName());

        // 创建共享地图、位置共享和猎杀行为
        ShareMapBehaviour shareMap = new ShareMapBehaviour(this, 3000, myMap);
        SharePositionBehaviour sharePos = new SharePositionBehaviour(this, 2000, agentPositions);
        ExploCoopBehaviour explo = new ExploCoopBehaviour(this, myMap, shareMap, agentPositions);

        List<Behaviour> behaviours = new ArrayList<>();
        behaviours.add(shareMap);
        behaviours.add(sharePos);
        behaviours.add(explo);

        addBehaviour(new StartMyBehaviours(this, behaviours));

        // 向 DF 注册自己，以便其他代理能通过服务类型找到我
        registerWithDF();

        System.out.println("Agent " + getLocalName() + " started with exploration + hunt + position sharing.");
    }

    /**
     * 向 Directory Facilitator (DF) 注册，声明服务类型为 "agentExplo"
     */
    private void registerWithDF() {
        DFAgentDescription dfd = new DFAgentDescription();
        dfd.setName(getAID());
        ServiceDescription sd = new ServiceDescription();
        sd.setType("agentExplo");
        sd.setName(getLocalName());
        dfd.addServices(sd);
        try {
            DFService.register(this, dfd);
            System.out.println(getLocalName() + " registered to DF with type agentExplo");
        } catch (FIPAException e) {
            System.err.println(getLocalName() + " DF registration failed: " + e.getMessage());
        }
    }

    @Override
    protected void takeDown() {
        // 从 DF 注销
        try {
            DFService.deregister(this);
        } catch (FIPAException e) {
            // ignore
        }
        try {
            super.takeDown();
        } catch (NullPointerException e) {
            System.err.println("Agent " + getLocalName() + " died before environment setup, skipping cleanup.");
        }
    }

    @Override
    protected void beforeMove() {
        super.beforeMove();
        if (myMap != null) myMap.prepareMigration();
    }

    @Override
    protected void afterMove() {
        super.afterMove();
        if (myMap != null) myMap.loadSavedData();
    }
}