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
import java.util.Set;
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

    private static final class RunLimitException extends RuntimeException {
        RunLimitException(String message) {
            super(message);
        }
    }

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
    private final long maxRunSeconds;
    private final long maxRunTokens;

    private final ExecutorService vtExecutor = Executors.newVirtualThreadPerTaskExecutor();
    private final ScheduledExecutorService pingScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "sse-ping");
        t.setDaemon(true);
        return t;
    });
    private final Map<String, RunContext> activeRuns = new ConcurrentHashMap<>();
    private final Set<String> activeSessions = ConcurrentHashMap.newKeySet();

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
                        @Value("${deerflow.limits.max-estimated-tokens:60000}") int maxEstimatedTokens,
                        @Value("${deerflow.limits.max-run-seconds:600}") long maxRunSeconds,
                        @Value("${deerflow.limits.max-run-tokens:200000}") long maxRunTokens) {
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
        this.maxRunSeconds = maxRunSeconds;
        this.maxRunTokens = maxRunTokens;
    }

    public record StartedRun(String runId, SseEmitter emitter) {}

    public StartedRun startRun(String sessionId, String input) {
        ChatSession session = sessionRepo.findById(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("会话不存在: " + sessionId));
        if (!activeSessions.add(sessionId)) {
            throw new IllegalStateException("该会话已有运行中的任务: " + sessionId);
        }
        SseEmitter emitter = new SseEmitter(0L);
        EventSink sink = new EventSink(emitter, objectMapper);
        Run run = new Run(UUID.randomUUID().toString(), sessionId, "RUNNING", input, null, null, null, Instant.now(), null);
        RunContext ctx = new RunContext();
        activeRuns.put(run.getId(), ctx);
        try {
            vtExecutor.submit(() -> executeRun(session, run, input, sink, ctx));
        } catch (RuntimeException e) {
            activeSessions.remove(sessionId);
            activeRuns.remove(run.getId());
            sink.fail(e);
            throw e;
        }
        return new StartedRun(run.getId(), emitter);
    }

    public void cancel(String runId) {
        RunContext ctx = activeRuns.get(runId);
        if (ctx != null) {
            ctx.requestCancel();
        }
    }

    /** 包级可见：集成测试直接调用（绕过 SSE 传输层）。 */
    void executeRun(ChatSession session, Run run, String input, EventSink sink, RunContext ctx) {
        ScheduledFuture<?> ping = null;
        RunUsage usage = new RunUsage();
        StringBuilder full = new StringBuilder();
        int seqRef = -1;
        try {
            runRepo.save(run);
            sink.send(new AgentEvent.RunStart(run.getId(), session.getId()));
            ping = pingScheduler.scheduleAtFixedRate(
                    () -> sink.send(new AgentEvent.Ping()), 15, 15, TimeUnit.SECONDS);
            if (ctx.isCancelled()) { // 取消早于启动（T15 审查 P2）
                throw new RunCancelledException();
            }

            List<MessageEntity> history = messageRepo.findBySessionIdOrderBySeqAsc(session.getId());
            seqRef = history.size();
            messageRepo.save(new MessageEntity(UUID.randomUUID().toString(), session.getId(), seqRef++,
                    "USER", input, null, null, null, Instant.now()));

            Path workspace = workspaceManager.sessionDir(session.getId());
            var counter = new AtomicInteger();
            List<ToolCallback> tools = toolsFactory.forRun(session.getId(), ctx, sink).stream()
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

            client.prompt().messages(promptMessages).toolCallbacks(tools)
                    .stream().chatClientResponse()
                    .doOnNext(resp -> {
                        if (ctx.isCancelled()) {
                            throw new RunCancelledException();
                        }
                        if (java.time.Duration.between(run.getStartedAt(), Instant.now()).toSeconds() > maxRunSeconds) {
                            throw new RunLimitException("运行超时(" + maxRunSeconds + "s)，已终止");
                        }
                        if (usage.exceeds(maxRunTokens)) {
                            throw new RunLimitException("运行 token 超限(" + maxRunTokens + ")，已终止");
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

            if (ctx.isCancelled()) { // 取消落在工具阻塞期但最终轮零 chunk：不得谎报 DONE（T15 审查 P7，2 行修复）
                throw new RunCancelledException();
            }

            if (seqRef >= 0) {
                int seqForPersist = seqRef;
                txTemplate.executeWithoutResult(status -> persistRunResult(session.getId(), seqForPersist, ctx, full.toString()));
            }
            run.setStatus("DONE");
            run.setInputTokens(usage.inputTokens());
            run.setOutputTokens(usage.outputTokens());
            sink.send(new AgentEvent.RunEnd("done", null));
        } catch (RunCancelledException e) {
            run.setStatus("CANCELLED");
            persistPartialQuietly(session.getId(), seqRef, ctx, full.toString());
            sink.send(new AgentEvent.RunEnd("cancelled", null));
        } catch (RunLimitException e) {
            log.warn("run {} 限额终止: {}", run.getId(), e.getMessage());
            run.setStatus("FAILED");
            run.setError(e.getMessage());
            persistPartialQuietly(session.getId(), seqRef, ctx, full.toString());
            sink.send(new AgentEvent.RunEnd("failed", e.getMessage()));
        } catch (Exception e) {
            if (ctx.isCancelled()) {
                // 用户已取消：即使下一轮模型报错，终态也归为 CANCELLED（T16 审查 I3c）
                log.info("run {} 取消后遇到错误，按 CANCELLED 收口: {}", run.getId(), e.getMessage());
                run.setStatus("CANCELLED");
                persistPartialQuietly(session.getId(), seqRef, ctx, full.toString());
                sink.send(new AgentEvent.RunEnd("cancelled", null));
            } else {
                log.error("run {} failed", run.getId(), e);
                run.setStatus("FAILED");
                run.setError(String.valueOf(e.getMessage()));
                sink.send(new AgentEvent.RunEnd("failed", String.valueOf(e.getMessage())));
            }
        } finally {
            try {
                if (ping != null) {
                    ping.cancel(false);
                }
                activeRuns.remove(run.getId());
                activeSessions.remove(session.getId());
                run.setEndedAt(Instant.now());
                runRepo.save(run);
                session.touch();
                sessionRepo.save(session);
            } catch (Exception cleanupError) {
                log.error("run {} 收尾失败", run.getId(), cleanupError);
                sink.fail(cleanupError);
            } finally {
                sink.complete();
            }
        }
    }

    /** 取消/限额路径的部分结果持久化：失败仅告警，不掩盖终态（T16 审查）。 */
    private void persistPartialQuietly(String sessionId, int seqRef, RunContext ctx, String fullText) {
        if (seqRef < 0) {
            return;
        }
        try {
            int seqForPersist = seqRef;
            txTemplate.executeWithoutResult(status -> persistRunResult(sessionId, seqForPersist, ctx, fullText));
        } catch (Exception persistError) {
            log.warn("部分结果持久化失败 session={}", sessionId, persistError);
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
