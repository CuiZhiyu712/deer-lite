package com.deerflow.sandbox;

import com.deerflow.runtime.RunContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ShellRunnerCancelTest {

    @TempDir
    Path dir;

    @Test
    void cancelKillsRunningProcessQuickly() throws Exception {
        var ctx = new RunContext();
        var runner = new ShellRunner(30, 10000);
        // Windows 上先 chdir 离开 TempDir：取消后孤儿 ping 由异步清扫器回收（本机 EDR 慢路径 4-8s），
        // 若它仍持有 TempDir 为工作目录，JUnit 删除临时目录会失败（sharing violation）——平台性调整，命令变、断言不变
        String cmd = System.getProperty("os.name").toLowerCase().contains("win")
                ? "cd /d C:\\ && ping -n 20 127.0.0.1 > nul" : "sleep 20";
        long t0 = System.currentTimeMillis();
        var future = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()
                .submit(() -> runner.run(dir, cmd, ctx));
        Thread.sleep(600);   // 等进程真正启动
        ctx.requestCancel();
        var result = future.get(5, java.util.concurrent.TimeUnit.SECONDS);
        assertThat(System.currentTimeMillis() - t0).isLessThan(5000);
        assertThat(result.output()).contains("取消");
    }

    @Test
    void alreadyCancelledContextRejectsImmediately() {
        var ctx = new RunContext();
        ctx.requestCancel();
        var result = new ShellRunner(30, 10000).run(dir, "echo hi", ctx);
        assertThat(result.output()).contains("取消");
        assertThat(result.blocked()).isFalse();
    }
}
