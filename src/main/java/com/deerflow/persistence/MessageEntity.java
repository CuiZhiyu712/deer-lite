package com.deerflow.persistence;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "messages", indexes = @Index(name = "idx_msg_session_seq", columnList = "sessionId,seq"))
public class MessageEntity {
    @Id
    private String id;
    @Column(nullable = false)
    private String sessionId;
    @Column(name = "seq", nullable = false)
    private int seq;
    @Column(nullable = false, length = 32)
    private String role;
    @Column(columnDefinition = "MEDIUMTEXT")
    private String content;
    @Column(columnDefinition = "MEDIUMTEXT")
    private String toolCallsJson;
    private String toolCallId;
    private String toolName;
    private Instant createdAt;

    protected MessageEntity() {}

    public MessageEntity(String id, String sessionId, int seq, String role, String content,
                         String toolCallsJson, String toolCallId, String toolName, Instant createdAt) {
        this.id = id; this.sessionId = sessionId; this.seq = seq; this.role = role;
        this.content = content; this.toolCallsJson = toolCallsJson;
        this.toolCallId = toolCallId; this.toolName = toolName; this.createdAt = createdAt;
    }

    public String getId() { return id; }
    public String getSessionId() { return sessionId; }
    public int getSeq() { return seq; }
    public String getRole() { return role; }
    public String getContent() { return content; }
    public String getToolCallsJson() { return toolCallsJson; }
    public String getToolCallId() { return toolCallId; }
    public String getToolName() { return toolName; }
    public Instant getCreatedAt() { return createdAt; }
}
