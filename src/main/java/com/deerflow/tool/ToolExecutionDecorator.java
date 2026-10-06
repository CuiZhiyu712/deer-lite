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

    /** 必须透传：框架会读 returnDirect 等元数据（T4 审查 javap 实测，勿删）。 */
    @Override
    public ToolMetadata getToolMetadata() {
        return delegate.getToolMetadata();
    }

    @Override
    public String call(String toolInput) {
        return call(toolInput, null);
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        String name = getToolDefinition().name();
        int round = roundCounter.incrementAndGet();
        if (round > maxRounds) {
            return "错误：本次运行工具调用已达上限(" + maxRounds + ")，请停止调用工具并直接给出最终答复。";
        }
        if (ctx.isCancelled()) {
            return "错误：用户已取消本次运行，请停止调用工具。";
        }
        String callId = UUID.randomUUID().toString();
        sink.send(new AgentEvent.ToolStart(callId, name, preview(toolInput, 300)));
        long t0 = System.currentTimeMillis();
        try {
            String out = delegate.call(toolInput, toolContext);
            ctx.addToolTrace(new RunContext.ToolTrace(callId, name, toolInput, out, true));
            sink.send(new AgentEvent.ToolResult(callId, name, true, preview(out, 500), System.currentTimeMillis() - t0));
            return out;
        } catch (Exception e) {
            ctx.addToolTrace(new RunContext.ToolTrace(callId, name, toolInput, String.valueOf(e.getMessage()), false));
            sink.send(new AgentEvent.ToolResult(callId, name, false, preview(String.valueOf(e.getMessage()), 300), System.currentTimeMillis() - t0));
            return "工具执行失败(" + name + "): " + e.getMessage() + "。请调整参数或换一种方式，不要重复同样的调用。";
        }
    }

    private static String preview(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
