package com.deerflow.agent;

import com.deerflow.agent.advisors.ContextAssemblyAdvisor;
import com.deerflow.agent.advisors.TokenBudgetAdvisor;
import com.deerflow.agent.advisors.UsageTrackingAdvisor;
import com.deerflow.event.AgentEvent;
import com.deerflow.event.EventSink;
import com.deerflow.persistence.ChatSession;
import com.deerflow.persistence.ChatSessionRepository;
import com.deerflow.persistence.MessageEntity;
import com.deerflow.persistence.MessageRepository;
import com.deerflow.persistence.Run;
import com.deerflow.persistence.RunRepository;
import com.deerflow.runtime.RunContext;
import com.deerflow.sandbox.WorkspaceManager;
import com.deerflow.tool.ToolExecutionDecorator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class AgentService {

    private static final Logger log = LoggerFactory.getLogger(AgentService.class);

    private static final class RunCancelledException extends RuntimeException {}

    private final ChatClient.Builder chatClientBuilder;
    private final ObjectMapper objectMapper;
    private final ChatSessionRepository sessionRepo;
    private final MessageRepository messageRepo;
    private final RunRepository runRepo;
    private final MessageConverter messageConverter;
    private final WorkspaceManager workspaceManager;
    private final AgentToolsFactory toolsFactory;
    private final TransactionTemplate txTemplate;

    private final int maxToolRounds;
    private final int keepRecentMessages;
    private final int maxEstimatedTokens;

    private final ExecutorService vtExecutor = Executors.newVirtualThreadPerTaskExecutor();
    private final ScheduledExecutorService pingScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "sse-ping");
        t.setDaemon(true);
        return t;
    });
    private final Map<String, RunContext> activeRuns = new ConcurrentHashMap<>();

    public AgentService(ChatClient.Builder chatClientBuilder,
                        ObjectMapper objectMapper,
                        ChatSessionRepository sessionRepo,
                        MessageRepository messageRepo,
                        RunRepository runRepo,
                        MessageConverter messageConverter,
                        WorkspaceManager workspaceManager,
                        AgentToolsFactory toolsFactory,
                        PlatformTransactionManager txManager,
                        @Value("${deerflow.limits.max-tool-rounds:40}") int maxToolRounds,
                        @Value("${deerflow.limits.keep-recent-messages:16}") int keepRecentMessages,
                        @Value("${deerflow.limits.max-estimated-tokens:60000}") int maxEstimatedTokens) {
        this.chatClientBuilder = chatClientBuilder;
        this.objectMapper = objectMapper;
        this.sessionRepo = sessionRepo;
        this.messageRepo = messageRepo;
        this.runRepo = runRepo;
        this.messageConverter = messageConverter;
        this.workspaceManager = workspaceManager;
        this.toolsFactory = toolsFactory;
        this.txTemplate = new TransactionTemplate(txManager);
        this.maxToolRounds = maxToolRounds;
        this.keepRecentMessages = keepRecentMessages;
        this.maxEstimatedTokens = maxEstimatedTokens;
    }

    public SseEmitter startRun(String sessionId, String input) {
        ChatSession session = sessionRepo.findById(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("会话不存在: " + sessionId));
        SseEmitter emitter = new SseEmitter(0L);
        EventSink sink = new EventSink(emitter, objectMapper);
        Run run = new Run(UUID.randomUUID().toString(), sessionId, "RUNNING", input, null, null, null, Instant.now(), null);
        RunContext ctx = new RunContext();
        activeRuns.put(run.getId(), ctx);
        vtExecutor.submit(() -> executeRun(session, run, input, sink, ctx));
        return emitter;
    }

    public void cancel(String runId) {
        RunContext ctx = activeRuns.get(runId);
        if (ctx != null) {
            ctx.requestCancel();
        }
    }

    /** 包级可见：集成测试直接调用（绕过 SSE 传输层）。 */
    void executeRun(ChatSession session, Run run, String input, EventSink sink, RunContext ctx) {
        runRepo.save(run);
        sink.send(new AgentEvent.RunStart(run.getId(), session.getId()));
        ScheduledFuture<?> ping = pingScheduler.scheduleAtFixedRate(
                () -> sink.send(new AgentEvent.Ping()), 15, 15, TimeUnit.SECONDS);
        RunUsage usage = new RunUsage();
        try {
            List<MessageEntity> history = messageRepo.findBySessionIdOrderBySeqAsc(session.getId());
            int seq = history.size();
            messageRepo.save(new MessageEntity(UUID.randomUUID().toString(), session.getId(), seq++,
                    "USER", input, null, null, null, Instant.now()));

            Path workspace = workspaceManager.sessionDir(session.getId());
            var counter = new AtomicInteger();
            List<ToolCallback> tools = toolsFactory.forRun(session.getId(), ctx).stream()
                    .map(cb -> (ToolCallback) new ToolExecutionDecorator(cb, sink, counter, maxToolRounds, ctx))
                    .toList();

            ChatClient client = chatClientBuilder.clone()
                    .defaultSystem(PromptBuilder.systemPrompt(workspace))
                    .defaultAdvisors(
                            new ContextAssemblyAdvisor(session.getId(), workspace.toString()),
                            new TokenBudgetAdvisor(maxEstimatedTokens, keepRecentMessages),
                            new UsageTrackingAdvisor(usage, sink))
                    .build();

            List<Message> promptMessages = new ArrayList<>(messageConverter.toDomain(history));
            promptMessages.add(new UserMessage(input));

            StringBuilder full = new StringBuilder();
            client.prompt().messages(promptMessages).toolCallbacks(tools)
                    .stream().chatClientResponse()
                    .doOnNext(resp -> {
                        if (ctx.isCancelled()) {
                            throw new RunCancelledException();
                        }
                        ChatResponse cr = resp.chatResponse();
                        if (cr != null && cr.getResult() != null && cr.getResult().getOutput() != null) {
                            String delta = cr.getResult().getOutput().getText();
                            if (delta != null && !delta.isEmpty()) {
                                full.append(delta);
                                sink.send(new AgentEvent.TextDelta(delta));
                            }
                        }
                    })
                    .blockLast();

            int resultSeq = seq; // lambda 捕获需要 effectively final（seq 上面已自增过）
            txTemplate.executeWithoutResult(status -> persistRunResult(session.getId(), resultSeq, ctx, full.toString()));
            run.setStatus("DONE");
            run.setInputTokens(usage.inputTokens());
            run.setOutputTokens(usage.outputTokens());
            sink.send(new AgentEvent.RunEnd("done", null));
        } catch (RunCancelledException e) {
            run.setStatus("CANCELLED");
            sink.send(new AgentEvent.RunEnd("cancelled", null));
        } catch (Exception e) {
            log.error("run {} failed", run.getId(), e);
            run.setStatus("FAILED");
            run.setError(String.valueOf(e.getMessage()));
            sink.send(new AgentEvent.RunEnd("failed", String.valueOf(e.getMessage())));
        } finally {
            ping.cancel(false);
            activeRuns.remove(run.getId());
            run.setEndedAt(Instant.now());
            runRepo.save(run);
            session.touch();
            sessionRepo.save(session);
            sink.complete();
        }
    }

    /** 工具轨迹 + 最终回复在同一事务内写入——半写结构会毒化历史重放（T14 审查）。 */
    private void persistRunResult(String sessionId, int seq, RunContext ctx, String finalText) {
        int next = persistToolTrace(sessionId, seq, ctx);
        messageRepo.save(new MessageEntity(UUID.randomUUID().toString(), sessionId, next,
                "ASSISTANT", finalText, null, null, null, Instant.now()));
    }

    /** 把本 run 的工具调用轨迹写成 ASSISTANT(toolCalls) + 若干 TOOL 行，保证下次历史重放格式合法。 */
    private int persistToolTrace(String sessionId, int seq, RunContext ctx) {
        List<RunContext.ToolTrace> traces = ctx.toolTraces();
        if (traces.isEmpty()) {
            return seq;
        }
        var calls = traces.stream()
                .map(t -> new MessageConverter.ToolCallRecord(t.id(), "function", t.name(), t.args()))
                .toList();
        messageRepo.save(new MessageEntity(UUID.randomUUID().toString(), sessionId, seq++,
                "ASSISTANT", "", messageConverter.serialiseToolCalls(calls), null, null, Instant.now()));
        for (RunContext.ToolTrace t : traces) {
            String result = t.result() == null ? "" : t.result();
            if (result.length() > 20_000) {
                result = result.substring(0, 20_000) + "\n[结果截断]";
            }
            messageRepo.save(new MessageEntity(UUID.randomUUID().toString(), sessionId, seq++,
                    "TOOL", result, null, t.id(), t.name(), Instant.now()));
        }
        return seq;
    }
}
