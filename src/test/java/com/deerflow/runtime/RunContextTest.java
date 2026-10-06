package com.deerflow.runtime;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RunContextTest {

    @Test
    void cancelSetsFlagAndRecordsTraces() {
        var ctx = new RunContext();
        assertThat(ctx.isCancelled()).isFalse();
        ctx.addToolTrace(new RunContext.ToolTrace("id1", "echo", "{}", "ok", true));
        assertThat(ctx.toolTraces()).hasSize(1);

        ctx.requestCancel(); // 无绑定进程时不得抛异常
        assertThat(ctx.isCancelled()).isTrue();
    }
}
