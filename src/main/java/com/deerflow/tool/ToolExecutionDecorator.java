package com.deerflow.tool;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** M0 雏形：仅日志观察；M1 Task 11 换为正式版（SSE 事件+错误恢复+计数） */
public class ToolExecutionDecorator implements ToolCallback {

    private static final Logger log = LoggerFactory.getLogger(ToolExecutionDecorator.class);
    private final ToolCallback delegate;

    public ToolExecutionDecorator(ToolCallback delegate) {
        this.delegate = delegate;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return delegate.getToolDefinition();
    }

    @Override
    public String call(String toolInput) {
        return call(toolInput, null);
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        long t0 = System.currentTimeMillis();
        log.info("[SPIKE] tool_start name={} args={}", getToolDefinition().name(), toolInput);
        try {
            String out = delegate.call(toolInput, toolContext);
            log.info("[SPIKE] tool_result name={} ok=true ms={} out={}", getToolDefinition().name(), System.currentTimeMillis() - t0, preview(out));
            return out;
        } catch (Exception e) {
            log.warn("[SPIKE] tool_result name={} ok=false err={}", getToolDefinition().name(), e.getMessage());
            throw e;
        }
    }

    private static String preview(String s) {
        return s == null ? "null" : s.substring(0, Math.min(200, s.length()));
    }
}
