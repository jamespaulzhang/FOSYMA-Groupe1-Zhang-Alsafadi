package eu.su.mas.dedaleEtu.mas.behaviours;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Random;

import dataStructures.tuple.Couple;
import eu.su.mas.dedale.env.Location;
import eu.su.mas.dedale.env.Observation;
import eu.su.mas.dedale.env.gs.GsLocation;
import eu.su.mas.dedale.mas.AbstractDedaleAgent;
import eu.su.mas.dedaleEtu.mas.agents.dummies.explo.FSMExploAgent;
import jade.core.AID;
import jade.core.behaviours.OneShotBehaviour;
import jade.lang.acl.ACLMessage;
import java.io.IOException;

public class HuntBehaviour extends OneShotBehaviour {

    private static final long serialVersionUID = 1L;
    private static final String PROTOCOL_CAPTURE = "CAPTURE";

    public HuntBehaviour(final AbstractDedaleAgent myAgent) {
        super(myAgent);
    }

    @Override
    public void action() {
        FSMExploAgent agent = (FSMExploAgent) this.myAgent;
        Location loc = ((AbstractDedaleAgent) this.myAgent).getCurrentPosition();
        if (loc == null) return;
        String myPosition = loc.getLocationId();
        String lastPosition = agent.getLastPosition();
        String nextNode = null;

        try { Thread.sleep(800); } catch (InterruptedException e) {}

        // 观察
        List<Couple<Location, List<Couple<Observation, String>>>> lobs =
                ((AbstractDedaleAgent) this.myAgent).observe();

        // 检测气味
        List<String> nodeStench = new ArrayList<>();
        Iterator<Couple<Location, List<Couple<Observation, String>>>> iter = lobs.iterator();
        while (iter.hasNext()) {
            Couple<Location, List<Couple<Observation, String>>> entry = iter.next();
            String nodeId = entry.getLeft().getLocationId();
            agent.addNextNodes(nodeId);
            if (!entry.getRight().isEmpty() && entry.getRight().get(0).getLeft() != null) {
                if (entry.getRight().get(0).getLeft().toString().equals("Stench")) {
                    nodeStench.add(nodeId);
                }
            }
        }

        if (nodeStench.size() == 1) {
            agent.setOwnStenchDirection(nodeStench.get(0));
        } else if (nodeStench.size() > 1) {
            agent.setOwnInsideStench(myPosition);
        } else {
            agent.setOwnStenchDirection(null);
            agent.setOwnInsideStench(null);
        }

        // 直接观察 Golem
        String golemPos = detectGolemDirectly(lobs, agent);
        if (golemPos != null) {
            agent.setDestination(golemPos);
            if (isGolemSurrounded(golemPos, agent)) {
                captureSuccess(golemPos, agent);
                return;
            }
        }

        // 重置目的地标记
        if (myPosition.equals(agent.getDestination())) {
            agent.setDestination(null);
            agent.setDestinationAlea(false);
            agent.setDestinationStench(false);
            agent.setDestinationInsideStench(false);
            agent.setDestinationInterblocage(false);
        }

        // 处理阻塞
        if (lastPosition.equals(myPosition) && agent.getPosition().contains(agent.getNextDest())) {
            if (!nodeStench.isEmpty() && agent.getStyle() == 1) {
                for (String nb : nodeStench) {
                    if (!agent.getPosition().contains(nb) && !nb.equals(myPosition)) {
                        nextNode = nb;
                        break;
                    }
                }
            }
            if (nextNode == null) {
                String far = null;
                while (far == null || far.equals(myPosition) ||
                        agent.getMyMap().getShortestPath(myPosition, far) == null ||
                        agent.getMyMap().getShortestPath(myPosition, far).contains(agent.getNextDest())) {
                    far = agent.getMyMap().getRandomNode();
                }
                List<String> path = agent.getMyMap().getShortestPath(myPosition, far);
                if (path != null && !path.isEmpty()) nextNode = path.get(0);
                if (new Random().nextDouble() >= 0.5) nextNode = agent.getNextDest();
            }
        } else if (agent.isDest_wumpusfound() && agent.getDestination() != null) {
            List<String> path = agent.getMyMap().getShortestPath(myPosition, agent.getDestination());
            if (path != null && !path.isEmpty()) nextNode = path.get(0);
        } else if (lastPosition.equals(myPosition) && !agent.getPosition().contains(agent.getNextDest()) &&
                !myPosition.equals(agent.getNextDest())) {
            agent.increaseWumpusCnt();
            nextNode = agent.getNextDest();
        } else {
            agent.setWumpusCnt(0);

            // 优先级1: 本地气味方向
            if (nodeStench.size() == 1 && agent.getStyle() == 1) {
                if (!nodeStench.get(0).equals(myPosition)) {
                    nextNode = nodeStench.get(0);
                }
            }
            // 优先级2: 身处气味中
            if (nextNode == null && nodeStench.size() > 1 && agent.getStyle() == 1) {
                Collections.shuffle(nodeStench);
                for (String nb : nodeStench) {
                    if (!agent.getPosition().contains(nb) && !nb.equals(myPosition)) {
                        nextNode = nb;
                        break;
                    }
                }
            }
            // 优先级3: 已有目的地（非随机）
            if (nextNode == null && agent.getDestination() != null && !agent.getDestinationAlea()) {
                List<String> path = agent.getMyMap().getShortestPath(myPosition, agent.getDestination());
                if (path != null && !path.isEmpty()) nextNode = path.get(0);
            }
            // 优先级4: 收到的气味方向（树状图）
            if (nextNode == null && !agent.getStenchDirection().isEmpty() && agent.getStyle() == 0) {
                for (String dest : agent.getStenchDirection()) {
                    if (!dest.equals(myPosition)) {
                        agent.setDestination(dest);
                        agent.setDestinationStench(true);
                        agent.setDestinationInsideStench(false);
                        agent.setDestinationAlea(false);
                        List<String> path = agent.getMyMap().getShortestPath(myPosition, dest);
                        if (path != null && !path.isEmpty()) {
                            nextNode = path.get(0);
                            break;
                        }
                    }
                }
            }
            // 优先级5: 收到的内部气味（树状图）
            if (nextNode == null && !agent.getInsideStench().isEmpty() && agent.getStyle() == 0) {
                List<String> inside = new ArrayList<>(agent.getInsideStench());
                Collections.shuffle(inside);
                for (String dest : inside) {
                    if (!dest.equals(myPosition)) {
                        agent.setDestination(dest);
                        agent.setDestinationInsideStench(true);
                        agent.setDestinationStench(false);
                        agent.setDestinationAlea(false);
                        List<String> path = agent.getMyMap().getShortestPath(myPosition, dest);
                        if (path != null && !path.isEmpty()) {
                            nextNode = path.get(0);
                            break;
                        }
                    }
                }
            }
            // 优先级6: 随机游荡
            if (nextNode == null) {
                String rando = null;
                while (rando == null || rando.equals(myPosition)) {
                    if (agent.getStyle() == 0) {
                        rando = agent.getMyMap().getRandomOneNode();
                    } else {
                        rando = agent.getMyMap().getRandomNode();
                    }
                }
                agent.setDestination(rando);
                agent.setDestinationAlea(true);
                agent.setDestinationStench(false);
                agent.setDestinationInsideStench(false);
                List<String> path = agent.getMyMap().getShortestPath(myPosition, rando);
                if (path != null && !path.isEmpty()) nextNode = path.get(0);
            }
        }

        agent.setLastPosition(myPosition);
        agent.setNextDest(nextNode);
        if (nextNode != null) {
            ((AbstractDedaleAgent) this.myAgent).moveTo(new GsLocation(nextNode));
            System.out.println(agent.getLocalName() + " [HUNT] moving to " + nextNode);
        }
        agent.cleanPosition();
        agent.cleanStenchDirection();
        agent.cleanInsideStench();
        agent.cleanNextNodes();
    }

    // ---------- 辅助方法 ----------
    private String detectGolemDirectly(List<Couple<Location, List<Couple<Observation, String>>>> obs, FSMExploAgent agent) {
        for (int i = 1; i < obs.size(); i++) {
            String nodeId = obs.get(i).getLeft().getLocationId();
            boolean hasGolem = obs.get(i).getRight().stream()
                    .anyMatch(p -> p.getLeft() == Observation.AGENTNAME && "Golem".equals(p.getRight()));
            if (hasGolem) {
                System.out.println(agent.getLocalName() + " [HUNT] Directly spotted Golem at " + nodeId);
                return nodeId;
            }
        }
        return null;
    }

    private boolean isGolemSurrounded(String golemNode, FSMExploAgent agent) {
        List<String> neighbours = agent.getMyMap().getNeighbors(golemNode);
        if (neighbours.isEmpty()) return true;
        for (String nb : neighbours) {
            if (!agent.getPosition().contains(nb) && !nb.equals(((AbstractDedaleAgent) myAgent).getCurrentPosition().getLocationId())) {
                return false;
            }
        }
        return true;
    }

    private void captureSuccess(String golemNode, FSMExploAgent agent) {
        System.out.println(agent.getLocalName() + " [HUNT] *** GOLEM CAPTURED at " + golemNode + " ***");
        agent.setMode(FSMExploAgent.MODE_CAPTURED);
        broadcastCapture(golemNode, agent);
    }

    private void broadcastCapture(String golemNode, FSMExploAgent agent) {
        ACLMessage msg = new ACLMessage(ACLMessage.INFORM);
        msg.setProtocol(PROTOCOL_CAPTURE);
        msg.setSender(myAgent.getAID());
        for (AID aid : agent.getServices("Explorer")) {
            if (!aid.equals(myAgent.getAID())) {
                msg.addReceiver(aid);
            }
        }
        try {
            msg.setContentObject(golemNode);
            ((AbstractDedaleAgent) myAgent).sendMessage(msg);
            System.out.println(agent.getLocalName() + " [SEND] CAPTURE: " + golemNode);
        } catch (IOException e) {}
        for (int i = 0; i < 2; i++) {
            try { Thread.sleep(100); } catch (InterruptedException e) {}
            ((AbstractDedaleAgent) myAgent).sendMessage(msg);
        }
    }
}