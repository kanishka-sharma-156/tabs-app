package com.example.tab_able.data;

public class LedgerItem {
    public enum Kind { EXPENSE, OFFSET, SETTLE }

    private String id;
    private String personId;
    private Kind kind;
    private String date;
    
    // Expense variant
    private String title;
    private String paidBy; // "you" or "them"
    private int total;
    private int yourShare;

    // Offset variant
    private String offsetTitleA;
    private String offsetTitleB;
    private int offsetCancelled;

    // Settle variant
    private int settleAmount;

    public LedgerItem() {
        // Required for Firebase
    }

    // Constructor for Expense
    public LedgerItem(String id, String personId, String date, String title, String paidBy, int total, int yourShare) {
        this.id = id;
        this.personId = personId;
        this.kind = Kind.EXPENSE;
        this.date = date;
        this.title = title;
        this.paidBy = paidBy;
        this.total = total;
        this.yourShare = yourShare;
    }

    // Constructor for Offset
    public LedgerItem(String id, String personId, String date, String offsetTitleA, String offsetTitleB, int offsetCancelled) {
        this.id = id;
        this.personId = personId;
        this.kind = Kind.OFFSET;
        this.date = date;
        this.offsetTitleA = offsetTitleA;
        this.offsetTitleB = offsetTitleB;
        this.offsetCancelled = offsetCancelled;
    }

    // Constructor for Settle
    public LedgerItem(String id, String personId, String date, int settleAmount) {
        this.id = id;
        this.personId = personId;
        this.kind = Kind.SETTLE;
        this.date = date;
        this.settleAmount = settleAmount;
    }

    public String getId() { return id != null ? id : ""; }
    public void setId(String id) { this.id = id; }

    public String getPersonId() { return personId != null ? personId : ""; }
    public void setPersonId(String personId) { this.personId = personId; }

    public Kind getKind() { return kind != null ? kind : Kind.EXPENSE; }
    public void setKind(Kind kind) { this.kind = kind; }

    public String getDate() { return date != null ? date : ""; }
    public void setDate(String date) { this.date = date; }

    public String getTitle() { return title != null ? title : ""; }
    public void setTitle(String title) { this.title = title; }

    public String getPaidBy() { return paidBy != null ? paidBy : "you"; }
    public void setPaidBy(String paidBy) { this.paidBy = paidBy; }

    public int getTotal() { return total; }
    public void setTotal(int total) { this.total = total; }

    public int getYourShare() { return yourShare; }
    public void setYourShare(int yourShare) { this.yourShare = yourShare; }

    public String getOffsetTitleA() { return offsetTitleA != null ? offsetTitleA : ""; }
    public void setOffsetTitleA(String offsetTitleA) { this.offsetTitleA = offsetTitleA; }

    public String getOffsetTitleB() { return offsetTitleB != null ? offsetTitleB : ""; }
    public void setOffsetTitleB(String offsetTitleB) { this.offsetTitleB = offsetTitleB; }

    public int getOffsetCancelled() { return offsetCancelled; }
    public void setOffsetCancelled(int offsetCancelled) { this.offsetCancelled = offsetCancelled; }

    public int getSettleAmount() { return settleAmount; }
    public void setSettleAmount(int settleAmount) { this.settleAmount = settleAmount; }
}
