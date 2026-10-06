package com.deerflow.tool;

import com.deerflow.event.AgentEvent;
import com.deerflow.event.EventSink;
import com.deerflow.persistence.ChatSession;
import com.deerflow.persistence.ChatSessionRepository;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class TodoToolTest {

    static class CollectingSink extends EventSink {
        final List<AgentEvent> events = new CopyOnWriteArrayList<>();
        CollectingSink() { super(null, null); }
        @Override public void send(AgentEvent e) { events.add(e); }
    }

    private final ChatSessionRepository sessions = mock(ChatSessionRepository.class);
    private final ChatSession session = new ChatSession("s1", "t", "m", Instant.now());

    private TodoTool tool() {
        when(sessions.findById("s1")).thenReturn(Optional.of(session));
        return new TodoTool(sessions, new ObjectMapper());
    }

    @Test
    void updatesSessionAndEmitsEvent() {
        var sink = new CollectingSink();
        String out = tool().writeTodos("s1", List.of(
                new TodoTool.TodoItem("写脚本", "in_progress"),
                new TodoTool.TodoItem("运行脚本", "pending")), sink);

        assertThat(out).contains("已更新");
        assertThat(session.getTodosJson()).contains("写脚本");
        verify(sessions).save(session);
        assertThat(sink.events).hasSize(1);
        assertThat(((AgentEvent.TodoUpdate) sink.events.get(0)).todos()).hasSize(2);
    }

    @Test
    void rejectsMultipleInProgress() {
        String out = tool().writeTodos("s1", List.of(
                new TodoTool.TodoItem("a", "in_progress"),
                new TodoTool.TodoItem("b", "in_progress")), new CollectingSink());
        assertThat(out).contains("最多一个");
        verify(sessions, never()).save(any());
    }

    @Test
    void rejectsInvalidStatus() {
        String out = tool().writeTodos("s1", List.of(
                new TodoTool.TodoItem("a", "doing")), new CollectingSink());
        assertThat(out).contains("status 只能是");
        verify(sessions, never()).save(any());
    }
}
