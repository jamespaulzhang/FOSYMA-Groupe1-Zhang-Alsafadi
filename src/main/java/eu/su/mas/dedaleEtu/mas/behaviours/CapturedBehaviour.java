package eu.su.mas.dedaleEtu.mas.behaviours;

import eu.su.mas.dedaleEtu.mas.agents.dummies.explo.FSMExploAgent;
import jade.core.behaviours.OneShotBehaviour;

public class CapturedBehaviour extends OneShotBehaviour {
    private static final long serialVersionUID = 1L;

    public CapturedBehaviour(FSMExploAgent agent) {
        super(agent);
    }

    @Override
    public void action() {
        FSMExploAgent agent = (FSMExploAgent) this.myAgent;
        System.out.println("==============================================");
        System.out.println(agent.getLocalName() + " [MISSION COMPLETE] All Golems captured! Map fully explored!");
        System.out.println("==============================================");
    }
}