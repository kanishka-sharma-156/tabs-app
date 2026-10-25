package com.example.tab_able.data;

public class Person {
    private String id;
    private String name;
    private String emoji;

    /**
     * Net balance in WHOLE RUPEES.
     *
     * Positive means this person owes you, negative means you owe them. This is
     * rupees, not paise, because that is what every screen displays. Money amounts
     * that come out of the split logic are paise, so convert once when setting this
     * and never multiply by 100 when reading it.
     */
    private int netAmount;

    private String tag;
    private int nudgeCount;
    private String email;

    public Person() {
        // Required for Firebase
    }

    public Person(String id, String name, String emoji, int netAmount, String tag) {
        this.id = id;
        this.name = name;
        this.emoji = emoji;
        this.netAmount = netAmount;
        this.tag = tag;
        this.nudgeCount = 0;
        this.email = "";
    }

    public Person(String id, String name, String emoji, int netAmount, String tag, String email) {
        this.id = id;
        this.name = name;
        this.emoji = emoji;
        this.netAmount = netAmount;
        this.tag = tag;
        this.nudgeCount = 0;
        this.email = email;
    }

    public String getId() { return id != null ? id : ""; }
    public void setId(String id) { this.id = id; }

    public String getName() { return name != null ? name : "Friend"; }
    public void setName(String name) { this.name = name; }

    public String getEmoji() { return emoji != null ? emoji : "🙂"; }
    public void setEmoji(String emoji) { this.emoji = emoji; }

    /** Net balance in whole rupees; positive means they owe you. */
    public int getNetAmount() { return netAmount; }

    /** Expects WHOLE RUPEES - see the field comment. */
    public void setNetAmount(int netAmount) { this.netAmount = netAmount; }

    public String getTag() { return tag != null ? tag : ""; }
    public void setTag(String tag) { this.tag = tag; }

    public int getNudgeCount() { return nudgeCount; }
    public void setNudgeCount(int nudgeCount) { this.nudgeCount = nudgeCount; }
    public void incrementNudge() { this.nudgeCount++; }

    public String getEmail() { return email != null ? email : ""; }
    public void setEmail(String email) { this.email = email; }
}
