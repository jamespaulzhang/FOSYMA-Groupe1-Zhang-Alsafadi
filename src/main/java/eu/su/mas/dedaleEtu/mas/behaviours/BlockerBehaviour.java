package eu.su.mas.dedaleEtu.mas.behaviours;

import eu.su.mas.dedale.mas.AbstractDedaleAgent;
import eu.su.mas.dedaleEtu.mas.agents.dummies.explo.FSMExploAgent;
import jade.core.behaviours.OneShotBehaviour;

public class BlockerBehaviour extends OneShotBehaviour {

    private static final long serialVersionUID = 1L;

    public BlockerBehaviour(AbstractDedaleAgent myAgent) {
        super(myAgent);
    }

    @Override
    public void action() {
        FSMExploAgent agent = (FSMExploAgent) this.myAgent;
        String myPos = ((AbstractDedaleAgent) myAgent).getCurrentPosition().getLocationId();
        System.out.println(agent.getLocalName() + " [BLOCKING] holding position at " + myPos);

        boolean allCaptured = true;
        for (String golemId : agent.getBlockingTargets()) {
            if (!agent.getCapturedGolems().contains(golemId)) {
                allCaptured = false;
                break;
            }
        }
        if (allCaptured) {
            agent.clearBlockingTargets();
            agent.setBlockingNode(null);
            agent.setMode(FSMExploAgent.MODE_EXPLORATION);
            System.out.println(agent.getLocalName() + " [BLOCKING] all targets captured, returning to exploration.");
            return;
        }

        block(1000);
    }
}