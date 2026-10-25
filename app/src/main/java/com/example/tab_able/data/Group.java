package com.example.tab_able.data;

import java.util.ArrayList;
import java.util.List;

public class Group {
    private String id;
    private String name;
    private String emoji;
    private int totalSpent;
    private int yourNet; // Positive = owed to you, Negative = you owe, 0 = square
    private List<String> members;

    public Group() {
        this.members = new ArrayList<>();
    }

    public Group(String id, String name, String emoji, int totalSpent, int yourNet, List<String> members) {
        this.id = id;
        this.name = name != null ? name : "";
        this.emoji = emoji != null ? emoji : "👥";
        this.totalSpent = totalSpent;
        this.yourNet = yourNet;
        this.members = members != null ? new ArrayList<>(members) : new ArrayList<>();
    }

    public String getId() { return id != null ? id : ""; }
    public void setId(String id) { this.id = id; }

    public String getName() { return name != null ? name : "Group"; }
    public void setName(String name) { this.name = name; }

    public String getEmoji() { return emoji != null ? emoji : "👥"; }
    public void setEmoji(String emoji) { this.emoji = emoji; }

    public int getTotalSpent() { return totalSpent; }
    public void setTotalSpent(int totalSpent) { this.totalSpent = totalSpent; }

    public int getYourNet() { return yourNet; }
    public void setYourNet(int yourNet) { this.yourNet = yourNet; }

    public List<String> getMembers() { 
        if (members == null) members = new ArrayList<>();
        return members; 
    }
    public void setMembers(List<String> members) { this.members = members; }
}
