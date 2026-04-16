// COMPLETE CODE - REPLACE ENTIRE FILE
package eu.su.mas.dedaleEtu.mas.knowledge;

import java.io.Serializable;

public class GolemInfo implements Serializable {
    private static final long serialVersionUID = 1L;
    
    private final String id;
    private String lastKnownPosition;
    private boolean confirmed;
    private long timestamp;         

    public GolemInfo(String id, String position, boolean confirmed) {
        this.id = id;
        this.lastKnownPosition = position;
        this.confirmed = confirmed;
        this.timestamp = System.currentTimeMillis();
    }

    public String getId() {
        return id;
    }

    public String getLastKnownPosition() {
        return lastKnownPosition;
    }

    public void updatePosition(String position) {
        this.lastKnownPosition = position;
        this.timestamp = System.currentTimeMillis();
    }

    public boolean isConfirmed() {
        return confirmed;
    }

    public void setConfirmed() {
        this.confirmed = true;
        this.timestamp = System.currentTimeMillis();
    }

    public long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }

    @Override
    public String toString() {
        return "GolemInfo{" +
                "id='" + id + '\'' +
                ", pos='" + lastKnownPosition + '\'' +
                ", confirmed=" + confirmed +
                ", timestamp=" + timestamp +
                '}';
    }
}