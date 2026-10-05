package com.deerflow.persistence;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "sessions")
public class ChatSession {
    @Id
    private String id;
    private String title;
    private String model;
    @Column(columnDefinition = "TEXT")
    private String todosJson;
    private Instant createdAt;
    private Instant updatedAt;

    protected ChatSession() {}

    public ChatSession(String id, String title, String model, Instant createdAt) {
        this.id = id;
        this.title = title;
        this.model = model;
        this.createdAt = createdAt;
        this.updatedAt = createdAt;
    }

    public String getId() { return id; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public String getTodosJson() { return todosJson; }
    public void setTodosJson(String todosJson) { this.todosJson = todosJson; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void touch() { this.updatedAt = Instant.now(); }
}
