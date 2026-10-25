package com.example.tab_able.data;

import java.util.ArrayList;
import java.util.List;

public class ExpenseRecord {
    private String id;
    private String title;
    private int amount;
    private String paidBy;
    private List<String> splitBetween;
    private String groupId;
    private String date;
    private long timestamp;
    private String transitFrom;
    private String transitTo;

    public ExpenseRecord() {
        this.splitBetween = new ArrayList<>();
    }

    public ExpenseRecord(String id, String title, int amount, String paidBy, List<String> splitBetween, String groupId, String date, long timestamp, String transitFrom, String transitTo) {
        this.id = id;
        this.title = title;
        this.amount = amount;
        this.paidBy = paidBy;
        this.splitBetween = splitBetween != null ? new ArrayList<>(splitBetween) : new ArrayList<>();
        this.groupId = groupId != null ? groupId : "";
        this.date = date;
        this.timestamp = timestamp;
        this.transitFrom = transitFrom != null ? transitFrom : "";
        this.transitTo = transitTo != null ? transitTo : "";
    }

    public String getId() { return id != null ? id : ""; }
    public void setId(String id) { this.id = id; }

    public String getTitle() { return title != null ? title : ""; }
    public void setTitle(String title) { this.title = title; }

    public int getAmount() { return amount; }
    public void setAmount(int amount) { this.amount = amount; }

    public String getPaidBy() { return paidBy != null ? paidBy : "You"; }
    public void setPaidBy(String paidBy) { this.paidBy = paidBy; }

    public List<String> getSplitBetween() {
        if (splitBetween == null) splitBetween = new ArrayList<>();
        return splitBetween;
    }
    public void setSplitBetween(List<String> splitBetween) { this.splitBetween = splitBetween; }

    public String getGroupId() { return groupId != null ? groupId : ""; }
    public void setGroupId(String groupId) { this.groupId = groupId; }

    public String getDate() { return date != null ? date : ""; }
    public void setDate(String date) { this.date = date; }

    public long getTimestamp() { return timestamp; }
    public void setTimestamp(long timestamp) { this.timestamp = timestamp; }

    public String getTransitFrom() { return transitFrom != null ? transitFrom : ""; }
    public void setTransitFrom(String transitFrom) { this.transitFrom = transitFrom; }

    public String getTransitTo() { return transitTo != null ? transitTo : ""; }
    public void setTransitTo(String transitTo) { this.transitTo = transitTo; }
}
