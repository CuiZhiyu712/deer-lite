package com.deerflow.chat.dto;

import java.time.Instant;

public final class Dtos {

    private Dtos() {}

    public record CreateSessionRequest(String title) {}
    public record RunRequest(String input, String model) {}
    public record SessionDto(String id, String title, String model, String todosJson, Instant updatedAt) {}
    public record MessageDto(String id, String role, String content, String toolCallsJson,
                             String toolCallId, String toolName, Instant createdAt) {}
    public record TodoItem(String content, String status) {}
}
