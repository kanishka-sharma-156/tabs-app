package com.example.tab_able.data;

public class SpaceSettlement {
    private String id;
    private String fromUid;
    private String toUid;
    private long amountPaise;
    private long createdAt;
    private String status; // PENDING, CONFIRMED, DISPUTED

    public SpaceSettlement() {}

    public SpaceSettlement(String id, String fromUid, String toUid, long amountPaise, long createdAt, String status) {
        this.id = id != null ? id : "";
        this.fromUid = fromUid != null ? fromUid : "";
        this.toUid = toUid != null ? toUid : "";
        this.amountPaise = amountPaise;
        this.createdAt = createdAt;
        this.status = status != null ? status : "PENDING";
    }

    public String getId() { return id != null ? id : ""; }
    public void setId(String id) { this.id = id; }

    public String getFromUid() { return fromUid != null ? fromUid : ""; }
    public void setFromUid(String fromUid) { this.fromUid = fromUid; }

    public String getToUid() { return toUid != null ? toUid : ""; }
    public void setToUid(String toUid) { this.toUid = toUid; }

    public long getAmountPaise() { return amountPaise; }
    public void setAmountPaise(long amountPaise) { this.amountPaise = amountPaise; }

    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }

    public String getStatus() { return status != null ? status : "PENDING"; }
    public void setStatus(String status) { this.status = status; }
}
