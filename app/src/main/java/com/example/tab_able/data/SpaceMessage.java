package com.example.tab_able.data;

public class SpaceMessage {
    private String id;
    private String senderUid;
    private String senderName;
    private String text;
    private String type; // TEXT, EXPENSE, SETTLEMENT, QUERY
    private int amount;
    private long timestamp;
    private String status; // PENDING, PAID, ACTIVE, DISPUTED, RESOLVED
    private String referenceId; // references expenseId or settlementId

    public SpaceMessage() {}

    public SpaceMessage(String id, String senderUid, String senderName, String text, String type, int amount, long timestamp, String status, String referenceId) {
        this.id = id != null ? id : "";
        this.senderUid = senderUid != null ? senderUid : "";
        this.senderName = senderName != null ? senderName : "";
        this.text = text != null ? text : "";
        this.type = type != null ? type : "TEXT";
        this.amount = amount;
        this.timestamp = timestamp;
        this.status = status != null ? status : "SENT";
        this.referenceId = referenceId != null ? referenceId : "";
    }

    public String getId() { return id != null ? id : ""; }
    public void setId(String id) { this.id = id; }

    public String getSenderUid() { return senderUid != null ? senderUid : ""; }
    public void setSenderUid(String senderUid) { this.senderUid = senderUid; }

    public String getSenderName() { return senderName != null ? senderName : ""; }
    public void setSenderName(String senderName) { this.senderName = senderName; }

    public String getText() { return text != null ? text : ""; }
    public void setText(String text) { this.text = text; }

    public String getType() { return type != null ? type : "TEXT"; }
    public void setType(String type) { this.type = type; }

    public int getAmount() { return amount; }
    public void setAmount(int amount) { this.amount = amount; }

    public long getTimestamp() { return timestamp; }
    public void setTimestamp(long timestamp) { this.timestamp = timestamp; }

    public String getStatus() { return status != null ? status : "SENT"; }
    public void setStatus(String status) { this.status = status; }

    public String getReferenceId() { return referenceId != null ? referenceId : ""; }
    public void setReferenceId(String referenceId) { this.referenceId = referenceId; }
}
