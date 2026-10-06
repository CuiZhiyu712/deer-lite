package com.deerflow.tool;

import com.deerflow.event.AgentEvent;
import com.deerflow.event.EventSink;
import com.deerflow.runtime.RunContext;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class ToolExecutionDecoratorTest {

    static final ToolDefinition DEF = ToolDefinition.builder()
            .name("echo").description("echo tool").inputSchema("{\"type\":\"object\"}").build();

    static ToolCallback fake(boolean throwOnCall) {
        return new ToolCallback() {
            public ToolDefinition getToolDefinition() { return DEF; }
            public String call(String input) { return call(input, null); }
            public String call(String input, org.springframework.ai.chat.model.ToolContext ctx) {
                if (throwOnCall) throw new RuntimeException("boom");
                return "echo:" + input;
            }
        };
    }

    static class CollectingSink extends EventSink {
        final List<AgentEvent> events = new CopyOnWriteArrayList<>();
        CollectingSink() { super(null, null); }
        @Override protected void emit(String name, Object data) { }
        @Override public void send(AgentEvent e) { events.add(e); }
    }

    @Test
    void emitsStartAndResultAndReturnsOutput() {
        var sink = new CollectingSink();
        var deco = new ToolExecutionDecorator(fake(false), sink, new AtomicInteger(), 10, new RunContext());
        String out = deco.call("hi");
        assertThat(out).isEqualTo("echo:hi");
        assertThat(sink.events).hasSize(2);
        assertThat(sink.events.get(0)).isInstanceOf(AgentEvent.ToolStart.class);
        var result = (AgentEvent.ToolResult) sink.events.get(1);
        assertThat(result.ok()).isTrue();
    }

    @Test
    void convertsExceptionToTextForModelRecovery() {
        var sink = new CollectingSink();
        var deco = new ToolExecutionDecorator(fake(true), sink, new AtomicInteger(), 10, new RunContext());
        String out = deco.call("hi");
        assertThat(out).contains("工具执行失败").contains("boom");
        assertThat(((AgentEvent.ToolResult) sink.events.get(1)).ok()).isFalse();
    }

    @Test
    void enforcesRoundLimit() {
        var counter = new AtomicInteger();
        var sink = new CollectingSink();
        var deco = new ToolExecutionDecorator(fake(false), sink, counter, 1, new RunContext());
        deco.call("a");
        String second = deco.call("b");
        assertThat(second).contains("工具调用已达上限");
    }

    @Test
    void recordsTraceAndStopsWhenCancelled() {
        var ctx = new RunContext();
        var sink = new CollectingSink();
        var deco = new ToolExecutionDecorator(fake(false), sink, new AtomicInteger(), 10, ctx);
        deco.call("x");
        assertThat(ctx.toolTraces()).hasSize(1);
        assertThat(ctx.toolTraces().get(0).ok()).isTrue();

        ctx.requestCancel();
        String out = deco.call("y");
        assertThat(out).contains("已取消");
    }
}
