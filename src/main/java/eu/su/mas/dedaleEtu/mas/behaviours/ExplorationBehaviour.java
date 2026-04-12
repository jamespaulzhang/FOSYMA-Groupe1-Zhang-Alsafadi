package eu.su.mas.dedaleEtu.mas.behaviours;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;

import dataStructures.tuple.Couple;
import eu.su.mas.dedale.env.Location;
import eu.su.mas.dedale.env.Observation;
import eu.su.mas.dedale.env.gs.GsLocation;
import eu.su.mas.dedale.mas.AbstractDedaleAgent;
import eu.su.mas.dedaleEtu.mas.agents.dummies.explo.FSMExploAgent;
import eu.su.mas.dedaleEtu.mas.knowledge.MapRepresentation.MapAttribute;
import jade.core.behaviours.OneShotBehaviour;

public class ExplorationBehaviour extends OneShotBehaviour {

    private static final long serialVersionUID = 1L;

    public ExplorationBehaviour(final AbstractDedaleAgent myAgent) {
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

        // 确保地图已初始化
        if (agent.getMyMap() == null) {
            agent.initiateMyMap();
            agent.setWait(1);
            agent.setGetoutCnt(0);
        }

        // 首先将当前位置添加到地图中（如果尚未添加）
        boolean nodeAdded = agent.myMapAddNewNode(myPosition);
        if (nodeAdded) {
            // 如果是新节点，将其标记为 closed（已访问）
            agent.getMyMap().addNode(myPosition, MapAttribute.closed);
        } else {
            // 如果节点已存在，确保它是 closed（可能之前从别人那里收到）
            agent.getMyMap().addNode(myPosition, MapAttribute.closed);
        }

        // 观察周围，更新邻居信息
        List<Couple<Location, List<Couple<Observation, String>>>> lobs =
                ((AbstractDedaleAgent) this.myAgent).observe();
        Iterator<Couple<Location, List<Couple<Observation, String>>>> iter = lobs.iterator();
        while (iter.hasNext()) {
            String nodeId = iter.next().getLeft().getLocationId();
            if (!myPosition.equals(nodeId)) {
                boolean isNew = agent.myMapAddNewNode(nodeId);
                agent.myMapAddEdge(myPosition, nodeId);
                // 记录第一个未访问的邻居作为候选 nextNode
                if (nextNode == null && isNew && !agent.getPosition().contains(nodeId)) {
                    nextNode = nodeId;
                }
            }
        }

        // 现在地图至少包含了当前位置及其邻居，可以安全判断 hasOpenNode()
        // 检查是否应切换到狩猎模式
        if (agent.getGetoutCnt() >= 10 || !agent.getMyMap().hasOpenNode()) {
            agent.setMode(FSMExploAgent.MODE_HUNT);
            double cc = agent.getMyMap().checkTypeGraph();
            agent.setStyle((Double.isNaN(cc) || cc < 0.05) ? 0 : 1);
            System.out.println(agent.getLocalName() + " switching to HUNT mode. Style: " + (agent.getStyle() == 0 ? "Tree" : "Graph"));
            return;
        }

        // 重置 Wumpus 标记
        if (myPosition.equals(agent.getDestination())) {
            agent.setDest_wumpusfound(false);
        }

        // 处理阻塞情况
        if (lastPosition.equals(myPosition) && agent.getPosition().contains(agent.getNextDest())) {
            String far = null;
            while (far == null || far.equals(myPosition) ||
                    agent.getMyMap().getShortestPath(myPosition, far) == null ||
                    agent.getMyMap().getShortestPath(myPosition, far).contains(agent.getNextDest())) {
                far = agent.getMyMap().getRandomNode();
            }
            nextNode = agent.getMyMap().getShortestPath(myPosition, far).get(0);
            if (new Random().nextDouble() >= 0.5) {
                nextNode = agent.getNextDest();
            }
        } else if (agent.isDest_wumpusfound() && agent.getDestination() != null) {
            List<String> path = agent.getMyMap().getShortestPath(myPosition, agent.getDestination());
            if (path != null && !path.isEmpty()) nextNode = path.get(0);
        } else if (lastPosition.equals(myPosition) && !agent.getPosition().contains(agent.getNextDest()) &&
                !myPosition.equals(agent.getNextDest())) {
            agent.increaseWumpusCnt();
            nextNode = agent.getNextDest();
        } else {
            // 正常探索：检测气味并继续
            List<String> nodeStench = new ArrayList<>();
            Iterator<Couple<Location, List<Couple<Observation, String>>>> iter2 = lobs.iterator();
            while (iter2.hasNext()) {
                Couple<Location, List<Couple<Observation, String>>> entry = iter2.next();
                String nodeId = entry.getLeft().getLocationId();
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

            agent.setWumpusCnt(0);

            if (nextNode == null && agent.getMyMap().hasOpenNode()) {
                List<String> path = agent.myMapShortestPathToClosestOpenNode(myPosition, agent.getPosition());
                if (path != null && !path.isEmpty()) nextNode = path.get(0);
            }

            if (nextNode == null) {
                agent.increaseGetoutCnt();
            } else {
                agent.setGetoutCnt(0);
            }
        }

        if (agent.getWait() > 0) agent.decreaseWait();
        agent.setLastPosition(myPosition);
        agent.setNextDest(nextNode);
        if (nextNode != null) {
            ((AbstractDedaleAgent) this.myAgent).moveTo(new GsLocation(nextNode));
        }
        agent.cleanPosition();
        agent.cleanStenchDirection();
        agent.cleanInsideStench();
        agent.cleanNextNodes();
    }
}