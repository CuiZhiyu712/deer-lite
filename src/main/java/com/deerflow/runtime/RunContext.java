package com.deerflow.runtime;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** 一次 run 的运行时上下文：取消信号、当前子进程引用（供强杀）、工具调用轨迹（供持久化）。 */
public class RunContext {

    public record ToolTrace(String id, String name, String args, String result, boolean ok, long durationMs) {}

    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final AtomicReference<Process> currentProcess = new AtomicReference<>();
    private final List<ToolTrace> tools = new CopyOnWriteArrayList<>();

    public boolean isCancelled() {
        return cancelled.get();
    }

    /** 幂等；守护线程做后代兜底清扫（无预算；Windows 晚到孤儿仍可回收，POSIX 为已知限制）。 */
    public void requestCancel() {
        if (!cancelled.compareAndSet(false, true)) {
            return;
        }
        Process p = currentProcess.get();
        if (p != null) {
            killTreeAsync(p);
        }
    }

    /** 先瞬时杀直接子进程，再由守护线程清理后代——descendants() 在本机可能阻塞 4-8s（T10 实测），不能放在调用线程同步做。 */
    private static void killTreeAsync(Process p) {
        p.destroyForcibly();
        Thread sweeper = new Thread(() -> {
            try {
                p.descendants().forEach(ProcessHandle::destroyForcibly);
            } catch (Exception ignore) {
                // 兜底线程：失败不上报
            }
        }, "run-cancel-sweeper");
        sweeper.setDaemon(true);
        sweeper.start();
    }

    public void bindProcess(Process p) {
        currentProcess.set(p);
        if (cancelled.get()) {
            // 取消落在"启动检查之后、bind 之前"的窗口：补杀，堵住竞态（T11 审查实测修复）
            killTreeAsync(p);
        }
    }

    public void clearProcess(Process p) {
        currentProcess.compareAndSet(p, null);
    }

    public void addToolTrace(ToolTrace t) {
        tools.add(t);
    }

    public List<ToolTrace> toolTraces() {
        return List.copyOf(tools);
    }
}
