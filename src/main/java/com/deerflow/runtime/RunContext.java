package com.deerflow.runtime;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** 一次 run 的运行时上下文：取消信号、当前子进程引用（供强杀）、工具调用轨迹（供持久化）。 */
public class RunContext {

    public record ToolTrace(String id, String name, String args, String result, boolean ok) {}

    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final AtomicReference<Process> currentProcess = new AtomicReference<>();
    private final List<ToolTrace> tools = new CopyOnWriteArrayList<>();

    public boolean isCancelled() {
        return cancelled.get();
    }

    public void requestCancel() {
        cancelled.set(true);
        Process p = currentProcess.get();
        if (p != null) {
            // 先瞬时杀直接子进程，再由守护线程做有界后代清扫——
            // descendants() 在本机可能阻塞 4-8s（T10 实测），不能放在调用线程同步做
            p.destroyForcibly();
            Thread sweeper = new Thread(() -> p.descendants().forEach(ProcessHandle::destroyForcibly), "run-cancel-sweeper");
            sweeper.setDaemon(true);
            sweeper.start();
        }
    }

    public void bindProcess(Process p) {
        currentProcess.set(p);
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
