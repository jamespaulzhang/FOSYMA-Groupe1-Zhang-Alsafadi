package eu.su.mas.dedaleEtu.mas.behaviours;

import jade.core.Agent;
import jade.core.behaviours.SimpleBehaviour;
import jade.domain.DFService;
import jade.domain.FIPAException;
import jade.domain.FIPAAgentManagement.DFAgentDescription;
import jade.domain.FIPAAgentManagement.ServiceDescription;

public class YellowSetupBehaviour extends SimpleBehaviour {

    private static final long serialVersionUID = -674588737131272988L;
    private boolean finished = false;
    private String behaviour;

    public YellowSetupBehaviour(Agent myAgent, String behaviour) {
        super(myAgent);
        this.behaviour = behaviour;
    }

    @Override
    public void action() {
        DFAgentDescription dfd = new DFAgentDescription();
        dfd.setName(myAgent.getAID());
        ServiceDescription sd = new ServiceDescription();
        sd.setType(behaviour);
        sd.setName(myAgent.getLocalName());
        dfd.addServices(sd);
        try {
            DFService.register(myAgent, dfd);
        } catch (FIPAException e) {
            e.printStackTrace();
        }
        finished = true;
    }

    @Override
    public boolean done() {
        return finished;
    }
}