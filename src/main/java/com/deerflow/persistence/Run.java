package com.deerflow.persistence;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "runs", indexes = @Index(name = "idx_run_session", columnList = "sessionId"))
public class Run {
    @Id
    private String id;
    @Column(nullable = false)
    private String sessionId;
    @Column(nullable = false, length = 32)
    private String status;   // RUNNING / DONE / FAILED / CANCELLED
    @Column(columnDefinition = "MEDIUMTEXT")
    private String input;
    @Column(columnDefinition = "MEDIUMTEXT")
    private String error;
    private Long inputTokens;
    private Long outputTokens;
    private Instant startedAt;
    private Instant endedAt;

    protected Run() {}

    public Run(String id, String sessionId, String status, String input, String error,
               Long inputTokens, Long outputTokens, Instant startedAt, Instant endedAt) {
        this.id = id; this.sessionId = sessionId; this.status = status; this.input = input;
        this.error = error; this.inputTokens = inputTokens; this.outputTokens = outputTokens;
        this.startedAt = startedAt; this.endedAt = endedAt;
    }

    public String getId() { return id; }
    public String getSessionId() { return sessionId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getInput() { return input; }
    public String getError() { return error; }
    public void setError(String error) { this.error = error; }
    public Long getInputTokens() { return inputTokens; }
    public void setInputTokens(Long v) { this.inputTokens = v; }
    public Long getOutputTokens() { return outputTokens; }
    public void setOutputTokens(Long v) { this.outputTokens = v; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getEndedAt() { return endedAt; }
    public void setEndedAt(Instant endedAt) { this.endedAt = endedAt; }
    public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }
}
