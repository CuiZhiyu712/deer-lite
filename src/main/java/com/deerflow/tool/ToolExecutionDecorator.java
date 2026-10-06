package com.deerflow.tool;

import com.deerflow.event.AgentEvent;
import com.deerflow.event.EventSink;
import com.deerflow.runtime.RunContext;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 每个工具实例在每次 run 中被装饰一次，绑定该 run 的 EventSink 与轮次计数器。
 * 职责：tool_start/tool_result 事件；异常转错误文本回填模型（模型自恢复）；全局轮次上限。
 */
public class ToolExecutionDecorator implements ToolCallback {

    private final ToolCallback delegate;
    private final EventSink sink;
    private final AtomicInteger roundCounter;
    private final int maxRounds;
    private final RunContext ctx;

    public ToolExecutionDecorator(ToolCallback delegate, EventSink sink,
                                  AtomicInteger roundCounter, int maxRounds, RunContext ctx) {
        this.delegate = delegate;
        this.sink = sink;
        this.roundCounter = roundCounter;
        this.maxRounds = maxRounds;
        this.ctx = ctx;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return delegate.getToolDefinition();
    }

    /** 必须透传：框架会读 returnDirect 等元数据（T4/T11 审查 javap 实测，勿删）；null 防御。 */
    @Override
    public ToolMetadata getToolMetadata() {
        ToolMetadata metadata = delegate.getToolMetadata();
        return metadata != null ? metadata : ToolMetadata.builder().build();
    }

    @Override
    public String call(String toolInput) {
        return call(toolInput, null);
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        String name = getToolDefinition().name();
        if (ctx.isCancelled()) {
            // 取消先判且不消耗轮次（T11 审查）
            return "错误：用户已取消本次运行，请停止调用工具。";
        }
        int round = roundCounter.incrementAndGet();
        if (round > maxRounds) {
            return "错误：本次运行工具调用已达上限(" + maxRounds + ")，请停止调用工具并直接给出最终答复。";
        }
        String callId = UUID.randomUUID().toString();
        sink.send(new AgentEvent.ToolStart(callId, name, preview(toolInput, 300)));
        long t0 = System.currentTimeMillis();
        try {
            String out = delegate.call(toolInput, toolContext);
            long ms = System.currentTimeMillis() - t0;
            boolean ok = !ctx.isCancelled();
            ctx.addToolTrace(new RunContext.ToolTrace(callId, name, toolInput, out, ok, ms));
            sink.send(new AgentEvent.ToolResult(callId, name, ok, preview(out, 500), ms));
            return out;
        } catch (Exception e) {
            String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            long ms = System.currentTimeMillis() - t0;
            ctx.addToolTrace(new RunContext.ToolTrace(callId, name, toolInput, msg, false, ms));
            sink.send(new AgentEvent.ToolResult(callId, name, false, preview(msg, 300), ms));
            return "工具执行失败(" + name + "): " + msg + "。请调整参数或换一种方式，不要重复同样的调用。";
        }
    }

    private static String preview(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
