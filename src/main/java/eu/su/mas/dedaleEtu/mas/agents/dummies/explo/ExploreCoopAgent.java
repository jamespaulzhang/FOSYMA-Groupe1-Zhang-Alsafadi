package eu.su.mas.dedaleEtu.mas.agents.dummies.explo;

import java.util.ArrayList;
import java.util.List;
import eu.su.mas.dedale.mas.AbstractDedaleAgent;
import eu.su.mas.dedale.mas.agent.behaviours.platformManagment.StartMyBehaviours;
import eu.su.mas.dedaleEtu.mas.behaviours.DecentralizedHuntBehaviour;
import eu.su.mas.dedaleEtu.mas.behaviours.ExploCoopBehaviour;
import eu.su.mas.dedaleEtu.mas.behaviours.ShareMapBehaviour;
import eu.su.mas.dedaleEtu.mas.behaviours.SharePositionBehaviour;
import eu.su.mas.dedaleEtu.mas.knowledge.MapRepresentation;
import jade.core.behaviours.Behaviour;

public class ExploreCoopAgent extends AbstractDedaleAgent {
    private static final long serialVersionUID = -7969469610241668140L;
    private MapRepresentation myMap;

    @Override
    protected void setup() {
        super.setup();
        myMap = new MapRepresentation(getLocalName());
        ShareMapBehaviour shareMap = new ShareMapBehaviour(this, 3000, myMap);
        DecentralizedHuntBehaviour huntBehaviour = new DecentralizedHuntBehaviour(this, myMap);
        SharePositionBehaviour sharePos = new SharePositionBehaviour(this, 2000, huntBehaviour);
        ExploCoopBehaviour explo = new ExploCoopBehaviour(this, myMap, shareMap, huntBehaviour);

        List<Behaviour> behaviours = new ArrayList<>();
        behaviours.add(shareMap);
        behaviours.add(sharePos);
        behaviours.add(explo);
        behaviours.add(huntBehaviour);

        addBehaviour(new StartMyBehaviours(this, behaviours));
        System.out.println("Agent " + getLocalName() + " started with full support.");
    }

    @Override
    protected void takeDown() {
        try { super.takeDown(); } catch (NullPointerException e) {}
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