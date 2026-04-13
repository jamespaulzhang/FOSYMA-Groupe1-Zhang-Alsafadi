package eu.su.mas.dedaleEtu.mas.knowledge;

import java.io.Serializable;

public class GolemInfo implements Serializable {
    private static final long serialVersionUID = 1L;
    private final String id;
    private String lastKnownPosition;
    private boolean confirmed;

    public GolemInfo(String id, String position, boolean confirmed) {
        this.id = id;
        this.lastKnownPosition = position;
        this.confirmed = confirmed;
    }

    public String getId() { return id; }
    public String getLastKnownPosition() { return lastKnownPosition; }
    public void updatePosition(String pos) { this.lastKnownPosition = pos; }
    public boolean isConfirmed() { return confirmed; }
    public void setConfirmed() { this.confirmed = true; }
}