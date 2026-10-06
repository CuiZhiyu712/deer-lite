package com.deerflow.agent;

import com.deerflow.event.AgentEvent;
import com.deerflow.event.EventSink;
import com.deerflow.persistence.ChatSession;
import com.deerflow.persistence.ChatSessionRepository;
import com.deerflow.persistence.MessageEntity;
import com.deerflow.persistence.MessageRepository;
import com.deerflow.persistence.Run;
import com.deerflow.persistence.RunRepository;
import com.deerflow.runtime.RunContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@SpringBootTest
@ActiveProfiles("test")
// ScriptedChatModel 的 @TestConfiguration 嵌套在非测试类中，Boot 不自动注册（实测 NoSuchBeanDefinition），需显式导入
@Import(ScriptedChatModel.Config.class)
class AgentServiceIT {

    @MockitoBean
    AgentToolsFactory toolsFactory;

    @Autowired AgentService agentService;
    @Autowired ChatSessionRepository sessionRepo;
    @Autowired MessageRepository messageRepo;
    @Autowired RunRepository runRepo;
    @Autowired ScriptedChatModel scriptedModel;

    static class CollectingSink extends EventSink {
        final List<AgentEvent> events = new CopyOnWriteArrayList<>();
        CollectingSink() { super(null, null); }
        @Override public void send(AgentEvent e) { events.add(e); }
        @Override public void complete() { }
        @Override public void fail(Throwable t) { }
    }

    @Test
    void runWithToolRoundEmitsEventsAndPersists() {
        String sid = UUID.randomUUID().toString();
        sessionRepo.save(new ChatSession(sid, "t", "deepseek-chat", Instant.now()));

        scriptedModel.pushToolCall("call-1", "echo", "{\"query\":\"hi\"}");
        scriptedModel.pushText("结果是 ", "echo:hi");

        var echo = org.springframework.ai.tool.function.FunctionToolCallback
                .<com.deerflow.tool.ToolInputs.WebSearch, String>builder("echo", in -> "echo:" + in.query())
                .description("echo").inputType(com.deerflow.tool.ToolInputs.WebSearch.class).build();
        when(toolsFactory.forRun(anyString(), any())).thenReturn(List.of(echo));

        var run = new Run(UUID.randomUUID().toString(), sid, "RUNNING", "帮我echo", null, null, null, Instant.now(), null);
        var sink = new CollectingSink();
        agentService.executeRun(sessionRepo.findById(sid).orElseThrow(), run, "帮我echo", sink, new RunContext());

        var names = sink.events.stream().map(AgentEvent::type).toList();
        // containsSubsequence 而非 containsSequence：UsageTrackingAdvisor 在最外层，会对每个流经的
        // chunk 发 usage 事件（实测序列 run_start,tool_start,tool_result,usage,text_delta,usage,text_delta,run_end），
        // 四个关键事件之间必然夹杂 usage/text_delta。此处断言的是相对顺序，不弱化。
        assertThat(names).containsSubsequence("run_start", "tool_start", "tool_result", "run_end");
        assertThat(names.get(0)).isEqualTo("run_start");
        assertThat(names.get(names.size() - 1)).isEqualTo("run_end");
        assertThat(sink.events.stream().filter(e -> e instanceof AgentEvent.TextDelta))
                .extracting(e -> ((AgentEvent.TextDelta) e).delta())
                .containsExactly("结果是 ", "echo:hi");
        assertThat(((AgentEvent.RunEnd) sink.events.get(sink.events.size() - 1)).status()).isEqualTo("done");

        var rows = messageRepo.findBySessionIdOrderBySeqAsc(sid);
        assertThat(rows).extracting(MessageEntity::getRole)
                .containsExactly("USER", "ASSISTANT", "TOOL", "ASSISTANT");
        assertThat(runRepo.findById(run.getId()).orElseThrow().getStatus()).isEqualTo("DONE");
    }
}
