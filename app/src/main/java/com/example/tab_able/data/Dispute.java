package com.example.tab_able.data;

public class Dispute {
    private String id;
    private String spaceId;
    private String expenseId;
    private String title;
    private String raisedByUid;
    private String raisedByName;
    private String reason;
    private String status; // ACTIVE, RESOLVED, REJECTED

    public Dispute() {}

    public Dispute(String id, String spaceId, String expenseId, String title, String raisedByUid, String raisedByName, String reason, String status) {
        this.id = id != null ? id : "";
        this.spaceId = spaceId != null ? spaceId : "";
        this.expenseId = expenseId != null ? expenseId : "";
        this.title = title != null ? title : "";
        this.raisedByUid = raisedByUid != null ? raisedByUid : "";
        this.raisedByName = raisedByName != null ? raisedByName : "";
        this.reason = reason != null ? reason : "";
        this.status = status != null ? status : "ACTIVE";
    }

    public String getId() { return id != null ? id : ""; }
    public void setId(String id) { this.id = id; }

    public String getSpaceId() { return spaceId != null ? spaceId : ""; }
    public void setSpaceId(String spaceId) { this.spaceId = spaceId; }

    public String getExpenseId() { return expenseId != null ? expenseId : ""; }
    public void setExpenseId(String expenseId) { this.expenseId = expenseId; }

    public String getTitle() { return title != null ? title : ""; }
    public void setTitle(String title) { this.title = title; }

    public String getRaisedByUid() { return raisedByUid != null ? raisedByUid : ""; }
    public void setRaisedByUid(String raisedByUid) { this.raisedByUid = raisedByUid; }

    public String getRaisedByName() { return raisedByName != null ? raisedByName : ""; }
    public void setRaisedByName(String raisedByName) { this.raisedByName = raisedByName; }

    public String getReason() { return reason != null ? reason : ""; }
    public void setReason(String reason) { this.reason = reason; }

    public String getStatus() { return status != null ? status : "ACTIVE"; }
    public void setStatus(String status) { this.status = status; }
}
