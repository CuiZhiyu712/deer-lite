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
    @Autowired MessageConverter messageConverter;
    @Autowired ScriptedChatModel scriptedModel;

    static class CollectingSink extends EventSink {
        final List<AgentEvent> events = new CopyOnWriteArrayList<>();
        volatile boolean closed;
        CollectingSink() { super(null, null); }
        @Override public void send(AgentEvent e) { events.add(e); }
        @Override public synchronized void complete() { closed = true; }
        @Override public void fail(Throwable t) { closed = true; }
    }

    @Test
    void runWithToolRoundEmitsEventsAndPersists() {
        scriptedModel.reset();
        String sid = UUID.randomUUID().toString();
        sessionRepo.save(new ChatSession(sid, "t", "deepseek-chat", Instant.now()));

        scriptedModel.pushToolCall("call-1", "echo", "{\"query\":\"hi\"}");
        scriptedModel.pushText("结果是 ", "echo:hi");

        var echo = org.springframework.ai.tool.function.FunctionToolCallback
                .<com.deerflow.tool.ToolInputs.WebSearch, String>builder("echo", in -> "echo:" + in.query())
                .description("echo").inputType(com.deerflow.tool.ToolInputs.WebSearch.class).build();
        when(toolsFactory.forRun(anyString(), any(), any())).thenReturn(List.of(echo));

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
        assertThat(sink.closed).isTrue();
    }

    @Test
    void failurePathPersistsFailedRun() {
        scriptedModel.reset();
        String sid = UUID.randomUUID().toString();
        sessionRepo.save(new ChatSession(sid, "t", "deepseek-chat", Instant.now()));
        when(toolsFactory.forRun(anyString(), any(), any())).thenReturn(List.of());
        var run = new Run(UUID.randomUUID().toString(), sid, "RUNNING", "x", null, null, null, Instant.now(), null);
        var sink = new CollectingSink();
        agentService.executeRun(sessionRepo.findById(sid).orElseThrow(), run, "x", sink, new RunContext());

        assertThat(sink.events.get(sink.events.size() - 1)).isInstanceOf(AgentEvent.RunEnd.class);
        assertThat(((AgentEvent.RunEnd) sink.events.get(sink.events.size() - 1)).status()).isEqualTo("failed");
        var saved = runRepo.findById(run.getId()).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo("FAILED");
        assertThat(saved.getError()).isNotBlank();
        assertThat(saved.getEndedAt()).isNotNull();
        assertThat(sink.closed).isTrue();
    }

    @Test
    void multiRoundTwoToolsMergeIntoOneAssistantRow() {
        scriptedModel.reset();
        String sid = UUID.randomUUID().toString();
        sessionRepo.save(new ChatSession(sid, "t", "deepseek-chat", Instant.now()));
        scriptedModel.pushToolCall("t1", "echo", "{\"query\":\"a\"}");
        scriptedModel.pushToolCall("t2", "echo", "{\"query\":\"b\"}");
        scriptedModel.pushText("ok");
        var echo = org.springframework.ai.tool.function.FunctionToolCallback
                .<com.deerflow.tool.ToolInputs.WebSearch, String>builder("echo", in -> "echo:" + in.query())
                .description("echo").inputType(com.deerflow.tool.ToolInputs.WebSearch.class).build();
        when(toolsFactory.forRun(anyString(), any(), any())).thenReturn(List.of(echo));

        var run = new Run(UUID.randomUUID().toString(), sid, "RUNNING", "x", null, null, null, Instant.now(), null);
        var sink = new CollectingSink();
        agentService.executeRun(sessionRepo.findById(sid).orElseThrow(), run, "x", sink, new RunContext());

        var rows = messageRepo.findBySessionIdOrderBySeqAsc(sid);
        assertThat(rows).extracting(MessageEntity::getRole)
                .containsExactly("USER", "ASSISTANT", "TOOL", "TOOL", "ASSISTANT");
        assertThat(rows).extracting(MessageEntity::getSeq).containsExactly(0, 1, 2, 3, 4);
        // 两个模型轮次的工具调用必须合并进同一 ASSISTANT 行，且参数逐条保留。
        // 注意：持久化的 callId 是 ToolExecutionDecorator 生成的关联 ID——2.0.1 的
        // DefaultToolCallingManager 对单个响应只构建一个共享 ToolContext（javap 实测），
        // 模型侧 id（t1/t2）不会下传到 ToolCallback，故此处按 name/arguments 断言内容。
        var calls = messageConverter.parseToolCalls(rows.get(1).getToolCallsJson());
        assertThat(calls).extracting(MessageConverter.ToolCallRecord::name).containsExactly("echo", "echo");
        assertThat(calls).extracting(MessageConverter.ToolCallRecord::arguments)
                .containsExactly("{\"query\":\"a\"}", "{\"query\":\"b\"}");
        // TOOL 行的 toolCallId 必须与 ASSISTANT 行的 toolCalls id 一一对应（重放合法性）
        assertThat(rows).extracting(MessageEntity::getToolCallId)
                .containsExactly(null, null, calls.get(0).id(), calls.get(1).id(), null);
    }

    @Test
    void historyReplayContinuesSeq() {
        scriptedModel.reset();
        String sid = UUID.randomUUID().toString();
        sessionRepo.save(new ChatSession(sid, "t", "deepseek-chat", Instant.now()));
        when(toolsFactory.forRun(anyString(), any(), any())).thenReturn(List.of());

        scriptedModel.pushText("一");
        var run1 = new Run(UUID.randomUUID().toString(), sid, "RUNNING", "q1", null, null, null, Instant.now(), null);
        agentService.executeRun(sessionRepo.findById(sid).orElseThrow(), run1, "q1", new CollectingSink(), new RunContext());

        scriptedModel.pushText("二");
        var run2 = new Run(UUID.randomUUID().toString(), sid, "RUNNING", "q2", null, null, null, Instant.now(), null);
        agentService.executeRun(sessionRepo.findById(sid).orElseThrow(), run2, "q2", new CollectingSink(), new RunContext());

        var rows = messageRepo.findBySessionIdOrderBySeqAsc(sid);
        assertThat(rows).extracting(MessageEntity::getRole)
                .containsExactly("USER", "ASSISTANT", "USER", "ASSISTANT");
        assertThat(rows).extracting(MessageEntity::getSeq).containsExactly(0, 1, 2, 3);
    }

    @Test
    void cancelViaStartRunEndsCancelledWithPartialPersisted() throws Exception {
        scriptedModel.reset();
        String sid = UUID.randomUUID().toString();
        sessionRepo.save(new ChatSession(sid, "t", "deepseek-chat", Instant.now()));
        scriptedModel.pushToolCall("t1", "slow", "{\"query\":\"a\"}");
        scriptedModel.pushText("完成");
        var slow = org.springframework.ai.tool.function.FunctionToolCallback
                .<com.deerflow.tool.ToolInputs.WebSearch, String>builder("slow", in -> {
                    try { Thread.sleep(800); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
                    return "slow:" + in.query();
                })
                .description("slow").inputType(com.deerflow.tool.ToolInputs.WebSearch.class).build();
        when(toolsFactory.forRun(anyString(), any(), any())).thenReturn(List.of(slow));

        var started = agentService.startRun(sid, "开始");
        // runId 由 StartedRun 同步返回（T16 审查 M2）；仍需等 run 行落库，再等工具真正开始执行
        String runId = started.runId();
        assertThat(runId).isNotNull();
        for (int i = 0; i < 40 && runRepo.findById(runId).isEmpty(); i++) {
            Thread.sleep(50);
        }
        assertThat(runRepo.findById(runId)).isPresent();
        Thread.sleep(150);
        agentService.cancel(runId);

        String status = null;
        for (int i = 0; i < 100 && !"CANCELLED".equals(status); i++) {
            status = runRepo.findById(runId).orElseThrow().getStatus();
            if (!"CANCELLED".equals(status)) Thread.sleep(50);
        }
        assertThat(status).isEqualTo("CANCELLED");
        var rows = messageRepo.findBySessionIdOrderBySeqAsc(sid);
        assertThat(rows).isNotEmpty();
        assertThat(rows.get(0).getRole()).isEqualTo("USER");
        // ④ 取消也持久化部分结果：CANCELLED 状态在 finally 才落库，晚于 persistRunResult，因此此刻末行必为部分 ASSISTANT
        assertThat(rows.get(rows.size() - 1).getRole()).isEqualTo("ASSISTANT");
        started.emitter().complete(); // 清理真实 emitter（无 handler 的 SseEmitter 直接 complete 安全）
    }

    @Test
    void zeroChunkAfterCancelStillCancelled() throws Exception {
        scriptedModel.reset();
        String sid = UUID.randomUUID().toString();
        sessionRepo.save(new ChatSession(sid, "t", "deepseek-chat", Instant.now()));
        var entered = new java.util.concurrent.CountDownLatch(1);
        var blocker = org.springframework.ai.tool.function.FunctionToolCallback
                .<com.deerflow.tool.ToolInputs.WebSearch, String>builder("blocker", in -> {
                    entered.countDown();
                    try { Thread.sleep(600); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
                    return "blocked";
                })
                .description("blocker").inputType(com.deerflow.tool.ToolInputs.WebSearch.class).build();
        when(toolsFactory.forRun(anyString(), any(), any())).thenReturn(List.of(blocker));
        scriptedModel.pushToolCall("t1", "blocker", "{\"query\":\"a\"}");
        scriptedModel.pushEmpty();

        var run = new Run(UUID.randomUUID().toString(), sid, "RUNNING", "x", null, null, null, Instant.now(), null);
        var sink = new CollectingSink();
        var ctx = new RunContext();
        Thread worker = Thread.ofVirtual().start(() ->
                agentService.executeRun(sessionRepo.findById(sid).orElseThrow(), run, "x", sink, ctx));
        assertThat(entered.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        ctx.requestCancel();
        worker.join(10_000);

        assertThat(((AgentEvent.RunEnd) sink.events.get(sink.events.size() - 1)).status()).isEqualTo("cancelled");
        assertThat(runRepo.findById(run.getId()).orElseThrow().getStatus()).isEqualTo("CANCELLED");
    }

    @Test
    void limitTerminationPersistsPartialAndFails() {
        scriptedModel.reset();
        String sid = UUID.randomUUID().toString();
        sessionRepo.save(new ChatSession(sid, "t", "deepseek-chat", Instant.now()));
        when(toolsFactory.forRun(anyString(), any(), any())).thenReturn(List.of());
        scriptedModel.pushText("部分输出");

        // startedAt 拨旧 700s > max-run-seconds(600)：首个 chunk 即触发限额
        var run = new Run(UUID.randomUUID().toString(), sid, "RUNNING", "x", null, null, null,
                Instant.now().minusSeconds(700), null);
        var sink = new CollectingSink();
        agentService.executeRun(sessionRepo.findById(sid).orElseThrow(), run, "x", sink, new RunContext());

        var last = (AgentEvent.RunEnd) sink.events.get(sink.events.size() - 1);
        assertThat(last.status()).isEqualTo("failed");
        var saved = runRepo.findById(run.getId()).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo("FAILED");
        assertThat(saved.getError()).contains("超时");
        var rows = messageRepo.findBySessionIdOrderBySeqAsc(sid);
        assertThat(rows).extracting(MessageEntity::getRole).containsExactly("USER", "ASSISTANT");
    }

    @Test
    void cancelThenModelErrorEndsCancelled() throws Exception {
        scriptedModel.reset();
        String sid = UUID.randomUUID().toString();
        sessionRepo.save(new ChatSession(sid, "t", "deepseek-chat", Instant.now()));
        var entered = new java.util.concurrent.CountDownLatch(1);
        var blocker = org.springframework.ai.tool.function.FunctionToolCallback
                .<com.deerflow.tool.ToolInputs.WebSearch, String>builder("blocker", in -> {
                    entered.countDown();
                    try { Thread.sleep(400); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
                    return "blocked";
                })
                .description("blocker").inputType(com.deerflow.tool.ToolInputs.WebSearch.class).build();
        when(toolsFactory.forRun(anyString(), any(), any())).thenReturn(List.of(blocker));
        scriptedModel.pushToolCall("t1", "blocker", "{\"query\":\"a\"}");
        scriptedModel.pushError(new IllegalStateException("model boom"));

        var run = new Run(UUID.randomUUID().toString(), sid, "RUNNING", "x", null, null, null, Instant.now(), null);
        var sink = new CollectingSink();
        var ctx = new RunContext();
        Thread worker = Thread.ofVirtual().start(() ->
                agentService.executeRun(sessionRepo.findById(sid).orElseThrow(), run, "x", sink, ctx));
        assertThat(entered.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        ctx.requestCancel();
        worker.join(10_000);

        assertThat(((AgentEvent.RunEnd) sink.events.get(sink.events.size() - 1)).status()).isEqualTo("cancelled");
        assertThat(runRepo.findById(run.getId()).orElseThrow().getStatus()).isEqualTo("CANCELLED");
    }
}
