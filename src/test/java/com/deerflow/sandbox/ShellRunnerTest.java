package com.deerflow.sandbox;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ShellRunnerTest {

    @TempDir
    Path dir;

    ShellRunner runner(long timeoutSeconds, int maxBytes) {
        return new ShellRunner(timeoutSeconds, maxBytes);
    }

    @Test
    void runsSimpleCommandInCwd() {
        var r = runner(10, 10000).run(dir, "echo hello");
        assertThat(r.output()).contains("hello");
        assertThat(r.exitCode()).isEqualTo(0);
    }

    @Test
    void killsOnTimeout() {
        long t0 = System.currentTimeMillis();
        var r = runner(1, 10000).run(dir, sleepCommand(5));
        assertThat(r.timedOut()).isTrue();
        assertThat(System.currentTimeMillis() - t0).isLessThan(4000);
    }

    @Test
    void truncatesHugeOutput() {
        String cmd = System.getProperty("os.name").toLowerCase().contains("win")
                ? "for /L %i in (1,1,20000) do @echo xxxxxxxxxxxxxxxxxxxx"
                : "yes xxxxxx | head -c 100000";
        var r = runner(30, 100).run(dir, cmd);
        assertThat(r.output()).hasSizeLessThan(300).contains("[output truncated]");
    }

    @Test
    void blocksDangerousCommands() {
        var r = runner(10, 10000).run(dir, "rm -rf /");
        assertThat(r.blocked()).isTrue();
        assertThat(r.output()).contains("危险命令");
    }

    /** 跨平台 sleep：Windows(cmd) 与 POSIX(bash) 都支持 */
    private static String sleepCommand(int seconds) {
        return System.getProperty("os.name").toLowerCase().contains("win")
                ? "ping -n " + (seconds + 1) + " 127.0.0.1 > nul"
                : "sleep " + seconds;
    }
}
