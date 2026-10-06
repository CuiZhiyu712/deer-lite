package com.deerflow.event;

import java.util.List;

public sealed interface AgentEvent {
    String type();

    record RunStart(String runId, String sessionId) implements AgentEvent {
        public String type() { return "run_start"; }
    }

    record TextDelta(String delta) implements AgentEvent {
        public String type() { return "text_delta"; }
    }

    record ToolStart(String id, String name, String argsPreview) implements AgentEvent {
        public String type() { return "tool_start"; }
    }

    record ToolResult(String id, String name, boolean ok, String outputPreview, long durationMs) implements AgentEvent {
        public String type() { return "tool_result"; }
    }

    record TodoUpdate(List<TodoItem> todos) implements AgentEvent {
        public TodoUpdate {
            todos = List.copyOf(todos);
        }
        public String type() { return "todo_update"; }
    }

    record Usage(Long inputTokens, Long outputTokens) implements AgentEvent {
        public String type() { return "usage"; }
    }

    record RunEnd(String status, String error) implements AgentEvent {
        public String type() { return "run_end"; }
    }

    record Ping() implements AgentEvent {
        public String type() { return "ping"; }
    }

    record TodoItem(String content, String status) {}
}
