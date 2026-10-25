package com.example.tab_able.data;

public class ChatMessage {
    private String id;
    private String chatId; // personId or groupId
    private String senderId;
    private String senderName;
    private String text;
    private String type; // TEXT, PAYMENT, REQUEST, QUERY
    private int amount;
    private long timestamp;
    private String status; // PENDING, PAID, DISPUTED, RESOLVED

    public ChatMessage() {
        // Required for Firebase
    }

    public ChatMessage(String id, String chatId, String senderId, String senderName, String text, String type, int amount, long timestamp, String status) {
        this.id = id;
        this.chatId = chatId;
        this.senderId = senderId;
        this.senderName = senderName;
        this.text = text;
        this.type = type;
        this.amount = amount;
        this.timestamp = timestamp;
        this.status = status;
    }

    public String getId() { return id != null ? id : ""; }
    public void setId(String id) { this.id = id; }

    public String getChatId() { return chatId != null ? chatId : ""; }
    public void setChatId(String chatId) { this.chatId = chatId; }

    public String getSenderId() { return senderId != null ? senderId : ""; }
    public void setSenderId(String senderId) { this.senderId = senderId; }

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
}
