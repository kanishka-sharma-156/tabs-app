package com.example.tab_able.data;

import java.util.ArrayList;
import java.util.List;

public class Space {
    private String id;
    private String type; // "DIRECT" or "GROUP"
    private List<String> memberUids;
    private String leaderUid;
    private String name;
    private String emoji;
    private String startDate;
    private String endDate;
    private long createdAt;

    public Space() {
        this.memberUids = new ArrayList<>();
    }

    public Space(String id, String type, List<String> memberUids, String leaderUid, String name, String startDate, String endDate, long createdAt) {
        this.id = id;
        this.type = type != null ? type : "GROUP";
        this.memberUids = memberUids != null ? new ArrayList<>(memberUids) : new ArrayList<>();
        this.leaderUid = leaderUid != null ? leaderUid : "";
        this.name = name != null ? name : "";
        this.emoji = "";
        this.startDate = startDate != null ? startDate : "";
        this.endDate = endDate != null ? endDate : "";
        this.createdAt = createdAt;
    }

    public String getId() { return id != null ? id : ""; }
    public void setId(String id) { this.id = id; }

    public String getType() { return type != null ? type : "GROUP"; }
    public void setType(String type) { this.type = type; }

    public String getEmoji() { return emoji != null ? emoji : ""; }
    public void setEmoji(String emoji) { this.emoji = emoji; }

    public List<String> getMemberUids() {
        if (memberUids == null) memberUids = new ArrayList<>();
        return memberUids;
    }
    public void setMemberUids(List<String> memberUids) { this.memberUids = memberUids; }

    public String getLeaderUid() { return leaderUid != null ? leaderUid : ""; }
    public void setLeaderUid(String leaderUid) { this.leaderUid = leaderUid; }

    public String getName() { return name != null ? name : ""; }
    public void setName(String name) { this.name = name; }

    public String getStartDate() { return startDate != null ? startDate : ""; }
    public void setStartDate(String startDate) { this.startDate = startDate; }

    public String getEndDate() { return endDate != null ? endDate : ""; }
    public void setEndDate(String endDate) { this.endDate = endDate; }

    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }
}
