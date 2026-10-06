package com.deerflow.runtime;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RunContextTest {

    @Test
    void cancelSetsFlagAndRecordsTraces() {
        var ctx = new RunContext();
        assertThat(ctx.isCancelled()).isFalse();
        ctx.addToolTrace(new RunContext.ToolTrace("id1", "echo", "{}", "ok", true, 5L));
        assertThat(ctx.toolTraces()).hasSize(1);

        ctx.requestCancel(); // 无绑定进程时不得抛异常
        assertThat(ctx.isCancelled()).isTrue();
    }

    private static Process spawnSleeper() throws Exception {
        if (System.getProperty("os.name").toLowerCase().contains("win")) {
            return new ProcessBuilder("cmd.exe", "/c", "ping -n 11 127.0.0.1 > nul").start();
        }
        return new ProcessBuilder("sleep", "10").start();
    }

    @Test
    void cancelBeforeBindStillKillsLaterBoundProcess() throws Exception {
        var ctx = new RunContext();
        ctx.requestCancel();
        Process p = spawnSleeper();
        ctx.bindProcess(p);
        assertThat(p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void bindThenCancelKillsProcess() throws Exception {
        var ctx = new RunContext();
        Process p = spawnSleeper();
        ctx.bindProcess(p);
        ctx.requestCancel();
        assertThat(p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
    }
}
