package com.deerflow.sandbox;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

@Component
public class ShellRunner {

    public record Result(String output, int exitCode, boolean timedOut, boolean blocked) {}

    /** 演示级黑名单：明显破坏性命令直接拒绝（非安全边界，README 已声明）。 */
    private static final List<Pattern> BLOCKLIST = List.of(
            Pattern.compile("rm\\s+(-[a-zA-Z]*\\s+)*/(\\s|$)"),
            Pattern.compile("mkfs|format\\s+[a-zA-Z]:", Pattern.CASE_INSENSITIVE),
            Pattern.compile("shutdown|reboot|halt", Pattern.CASE_INSENSITIVE),
            Pattern.compile("del\\s+/[fsq].*[a-zA-Z]:\\\\", Pattern.CASE_INSENSITIVE),
            Pattern.compile("mklink", Pattern.CASE_INSENSITIVE),
            // 计划原稿为 ":(){ :\|:& };:"，其中 "{"/"}"/"("/")" 是 Java 正则元字符，
            // 直接编译会抛 PatternSyntaxException（Illegal repetition）；此处转义后语义不变。
            Pattern.compile(":\\(\\)\\{ :\\|:& \\};:")
    );

    private final long timeoutSeconds;
    private final int maxOutputBytes;

    // 多构造器的 Spring bean 必须显式标注，否则容器无法选择并启动失败（T8 审查实测）
    @org.springframework.beans.factory.annotation.Autowired
    public ShellRunner(org.springframework.core.env.Environment env) {
        this(env.getProperty("deerflow.sandbox.timeout-seconds", Long.class, 30L),
             env.getProperty("deerflow.sandbox.max-output-bytes", Integer.class, 262144));
    }

    public ShellRunner(long timeoutSeconds, int maxOutputBytes) {
        this.timeoutSeconds = timeoutSeconds;
        this.maxOutputBytes = maxOutputBytes;
    }

    public Result run(Path cwd, String command) {
        for (Pattern p : BLOCKLIST) {
            if (p.matcher(command).find()) {
                return new Result("错误：危险命令被拦截: " + command, -1, false, true);
            }
        }
        ProcessBuilder pb = new ProcessBuilder(shellCommand(command))
                .directory(cwd.toFile())
                .redirectErrorStream(true);
        try {
            Process process = pb.start();
            StringBuilder sb = new StringBuilder();
            boolean[] truncated = {false};
            // 必须在独立线程里持续抽干 stdout：若在主线程读到 EOF 再 waitFor，
            // 超时逻辑永远不可达（无输出进程会阻塞 read 直到自然结束，实测 ping 阻塞 29s）。
            Thread pump = new Thread(() -> {
                try (InputStream in = process.getInputStream()) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) != -1) {
                        synchronized (truncated) {
                            if (sb.length() < maxOutputBytes) {
                                sb.append(new String(buf, 0, Math.min(n, maxOutputBytes - sb.length()), StandardCharsets.UTF_8));
                            } else {
                                truncated[0] = true;
                            }
                        }
                    }
                } catch (IOException e) {
                    // 进程被强杀导致管道关闭属正常路径（尤其 Windows 上子进程句柄滞后释放）
                }
            });
            pump.setName("shell-output-pump");
            pump.setDaemon(true);
            pump.start();
            boolean finished = waitForExit(process, timeoutSeconds);
            if (!finished) {
                process.destroyForcibly();
                killDescendants(process, 2000);
                pump.join(300);
                return new Result(collect(sb, truncated) + "\n[命令超时被强制终止]", -1, true, false);
            }
            pump.join(5000);
            String output = collect(sb, truncated);
            if (truncated[0]) {
                output = output + "\n[output truncated]";
            }
            return new Result(output, process.exitValue(), false, false);
        } catch (IOException e) {
            return new Result("错误：命令启动失败: " + e.getMessage(), -1, false, false);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result("错误：执行被中断", -1, false, false);
        }
    }

    /**
     * 超时后强杀树中残余后代（Windows 上孤儿进程仍保留原 ParentProcessId，父进程已死也能扫到）。
     *
     * 为何用"多线程赛跑"而非一次同步扫描：本机（沙箱/EDR 环境）实测
     * ProcessHandle.descendants() 会随机落到 4-8s 的慢路径（约 1/3 概率），快路径仅 ~50ms；
     * 一次慢扫描就会把超时返回拖到调用方（测试）的 4s 预算之外，而慢调用本身无法中断。
     * 因此每隔 200ms 起一个新的扫描线程，任一快路径命中即完成强杀；仍处于慢路径的线程
     * 在返回后作为兜底把查到的后代杀掉。正常环境首次尝试（毫秒级）即成功，等价同步强杀。
     */
    private static void killDescendants(Process process, long budgetMillis) throws InterruptedException {
        long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(budgetMillis);
        Object done = new Object();
        boolean[] killed = {false};
        while (!killed[0]) {
            long remainingMillis = TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime());
            if (remainingMillis <= 0) {
                return;
            }
            Thread hunter = new Thread(() -> {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                synchronized (done) {
                    killed[0] = true;
                    done.notifyAll();
                }
            }, "shell-descendant-hunter");
            hunter.setDaemon(true);
            hunter.start();
            synchronized (done) {
                if (!killed[0]) {
                    done.wait(Math.min(remainingMillis, 200));
                }
            }
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
    }

    /**
     * 轮询 isAlive 而非 waitFor(timeout)：本机实测 JDK 的 waitFor(timeout) 会明显溢出
     * （1 秒超时实际阻塞约 4.9 秒），导致超时测试的 <4s 断言随环境抖动失败。
     */
    private static boolean waitForExit(Process process, long seconds) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (process.isAlive()) {
            long remainingMillis = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
            if (remainingMillis <= 0) {
                return false;
            }
            Thread.sleep(Math.min(50L, remainingMillis));
        }
        return true;
    }

    private static String collect(StringBuilder sb, boolean[] truncated) {
        synchronized (truncated) {
            return sb.toString();
        }
    }

    /** Windows 用 cmd.exe，其他平台用 bash -lc（README 有说明；模型提示词同步告知）。 */
    private static List<String> shellCommand(String command) {
        if (isWindows()) {
            return List.of("cmd.exe", "/c", command);
        }
        return List.of("bash", "-lc", command);
    }
}
