package com.deerflow.tool;

import com.deerflow.event.AgentEvent;
import com.deerflow.event.EventSink;
import com.deerflow.persistence.ChatSessionRepository;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Set;

@Component
public class TodoTool {

    public record TodoItem(String content, String status) {}

    private static final Set<String> VALID = Set.of("pending", "in_progress", "completed");

    private final ChatSessionRepository sessions;
    private final ObjectMapper objectMapper;

    public TodoTool(ChatSessionRepository sessions, ObjectMapper objectMapper) {
        this.sessions = sessions;
        this.objectMapper = objectMapper;
    }

    public String writeTodos(String sessionId, List<TodoItem> todos, EventSink sink) {
        if (todos == null || todos.isEmpty()) {
            return "错误：待办列表不能为空";
        }
        long inProgress = todos.stream().filter(t -> "in_progress".equals(t.status())).count();
        if (inProgress > 1) {
            return "错误：最多一个任务处于 in_progress";
        }
        for (TodoItem t : todos) {
            if (!VALID.contains(t.status())) {
                return "错误：status 只能是 pending / in_progress / completed";
            }
        }
        var session = sessions.findById(sessionId).orElseThrow();
        try {
            session.setTodosJson(objectMapper.writeValueAsString(todos));
        } catch (Exception e) {
            return "错误：待办序列化失败 " + e.getMessage();
        }
        session.touch();
        sessions.save(session);
        sink.send(new AgentEvent.TodoUpdate(todos.stream()
                .map(t -> new AgentEvent.TodoItem(t.content(), t.status())).toList()));
        return "已更新待办清单（" + todos.size() + " 项）";
    }
}
