package com.example.tab_able.data;

import java.util.HashMap;
import java.util.Map;

public class SpaceExpense {
    private String id;
    private String title;
    private long amountPaise;
    private String paidByUid;
    private Map<String, Long> shares;
    private long occurredAt;
    private int dayIndex;
    private String createdBy;
    private String status;
    private boolean isTransit;
    private Place place;
    private Place fromPlace;
    private Place toPlace;
    private String transitFrom;
    private String transitTo;

    public SpaceExpense() {
        this.shares = new HashMap<>();
        this.place = new Place();
        this.fromPlace = new Place();
        this.toPlace = new Place();
    }

    public String getId() { return id != null ? id : ""; }
    public void setId(String id) { this.id = id; }

    public String getTitle() { return title != null ? title : ""; }
    public void setTitle(String title) { this.title = title; }

    public long getAmountPaise() { return amountPaise; }
    public void setAmountPaise(long amountPaise) { this.amountPaise = amountPaise; }

    public String getPaidByUid() { return paidByUid != null ? paidByUid : ""; }
    public void setPaidByUid(String paidByUid) { this.paidByUid = paidByUid; }

    public Map<String, Long> getShares() {
        if (shares == null) shares = new HashMap<>();
        return shares;
    }
    public void setShares(Map<String, Long> shares) { this.shares = shares; }

    public long getOccurredAt() { return occurredAt; }
    public void setOccurredAt(long occurredAt) { this.occurredAt = occurredAt; }

    public int getDayIndex() { return dayIndex; }
    public void setDayIndex(int dayIndex) { this.dayIndex = dayIndex; }

    public String getCreatedBy() { return createdBy != null ? createdBy : ""; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }

    public String getStatus() { return status != null ? status : "ACTIVE"; }
    public void setStatus(String status) { this.status = status; }

    public boolean isTransit() { return isTransit; }
    public void setTransit(boolean transit) { isTransit = transit; }

    public Place getPlace() { return place != null ? place : new Place(); }
    public void setPlace(Place place) { this.place = place; }

    public Place getFromPlace() { return fromPlace != null ? fromPlace : new Place(); }
    public void setFromPlace(Place fromPlace) { this.fromPlace = fromPlace; }

    public Place getToPlace() { return toPlace != null ? toPlace : new Place(); }
    public void setToPlace(Place toPlace) { this.toPlace = toPlace; }

    public String getTransitFrom() { return transitFrom != null ? transitFrom : (fromPlace != null ? fromPlace.getName() : ""); }
    public void setTransitFrom(String transitFrom) { this.transitFrom = transitFrom; }

    public String getTransitTo() { return transitTo != null ? transitTo : (toPlace != null ? toPlace.getName() : ""); }
    public void setTransitTo(String transitTo) { this.transitTo = transitTo; }
}
