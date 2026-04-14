package eu.su.mas.dedaleEtu.mas.behaviours;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import eu.su.mas.dedale.env.gs.GsLocation;
import eu.su.mas.dedale.mas.AbstractDedaleAgent;
import eu.su.mas.dedaleEtu.mas.agents.dummies.explo.FSMExploAgent;
import jade.core.behaviours.CyclicBehaviour;

public class BlockerBehaviour extends CyclicBehaviour {

    private static final long serialVersionUID = 1L;
    private FSMExploAgent agent;
    private String targetNode;
    private Set<String> targetGolems;
    private long startTime;
    private static final long MAX_BLOCK_TIME = 60000; // 60 seconds timeout

    public BlockerBehaviour(FSMExploAgent agent) {
        super(agent);
        this.agent = agent;
        this.targetNode = agent.getBlockingNode();
        this.targetGolems = new HashSet<>(agent.getBlockingTargets());
        this.startTime = System.currentTimeMillis();
    }

    @Override
    public void action() {
        String myPos = ((AbstractDedaleAgent) myAgent).getCurrentPosition().getLocationId();

        // If not yet at target node, move towards it
        if (!myPos.equals(targetNode)) {
            List<String> path = agent.getMyMap().getShortestPath(myPos, targetNode);
            if (path != null && !path.isEmpty()) {
                ((AbstractDedaleAgent) myAgent).moveTo(new GsLocation(path.get(0)));
                System.out.println(agent.getLocalName() + " [BLOCKING] moving to " + path.get(0));
            }
            block(800);
            return;
        }

        // At target node, check if all target Golems are captured
        boolean allCaptured = true;
        for (String gid : targetGolems) {
            if (!agent.getCapturedGolems().contains(gid)) {
                allCaptured = false;
                break;
            }
        }

        if (allCaptured) {
            System.out.println(agent.getLocalName() + " [BLOCKING] all targets captured, leaving.");
            agent.clearBlockingTargets();
            agent.setBlockingNode(null);
            agent.setMode(FSMExploAgent.MODE_EXPLORATION);
            myAgent.removeBehaviour(this);
            return;
        }

        // Timeout protection
        if (System.currentTimeMillis() - startTime > MAX_BLOCK_TIME) {
            System.out.println(agent.getLocalName() + " [BLOCKING] timeout, aborting block.");
            agent.setMode(FSMExploAgent.MODE_EXPLORATION);
            myAgent.removeBehaviour(this);
            return;
        }

        block(1000); // Check every second
    }
}