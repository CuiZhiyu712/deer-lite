package com.deerflow.sandbox;

import org.springframework.stereotype.Component;

import java.io.IOException;
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
            Pattern.compile("rm\\s+(-{1,2}[a-zA-Z-]*\\s+)*/(\\*|\\s|$)"),
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
        return run(cwd, command, null);
    }

    public Result run(Path cwd, String command, com.deerflow.runtime.RunContext ctx) {
        if (ctx != null && ctx.isCancelled()) {
            // blocked 语义保留给黑名单拦截；取消走独立分支（ShellRunnerCancelTest 断言 blocked=false）
            return new Result("错误：运行已取消", -1, false, false);
        }
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
            if (ctx != null) {
                ctx.bindProcess(process);
            }
            try {
                process.getOutputStream().close(); // 给 stdin EOF，避免 more/pause 类命令白等到超时
                StringBuilder sb = new StringBuilder();
                boolean[] truncated = {false};
                int[] total = {0}; // 截断预算按字符计（配置键 max-output-bytes 名称沿用，语义为字符上限）
                // 必须在独立线程里持续抽干 stdout：若在主线程读到 EOF 再 waitFor，
                // 超时逻辑永远不可达（无输出/读 stdin 的进程会阻塞 read 直到自然结束）。
                Thread pump = new Thread(() -> {
                    try (var reader = new java.io.InputStreamReader(process.getInputStream(), outputCharset())) {
                        char[] buf = new char[4096];
                        int n;
                        while ((n = reader.read(buf)) != -1) {
                            synchronized (truncated) {
                                if (total[0] < maxOutputBytes) {
                                    int take = Math.min(n, maxOutputBytes - total[0]);
                                    sb.append(buf, 0, take);
                                    total[0] += take;
                                    if (take < n) {
                                        truncated[0] = true;
                                    }
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
                    // Windows：父进程死后孤儿仍保留 PPID，race 扫描可回收（已实测）；
                    // POSIX：孤儿会被重挂，按 PPID 链扫描不可达——已知限制，README 声明
                    process.destroyForcibly();
                    killDescendants(process, 2000);
                    pump.join(300);
                    boolean cancelled = ctx != null && ctx.isCancelled();
                    String reason = cancelled ? "[运行已取消，进程已终止]" : "[命令超时被强制终止]";
                    return new Result(collect(sb, truncated) + "\n" + reason, -1, !cancelled, false);
                }
                pump.join(1000); // 若管道句柄被孙进程继承钉住，至多等 1s 后带部分输出返回
                String output = collect(sb, truncated);
                if (truncated[0]) {
                    output = output + "\n[output truncated]";
                }
                if (ctx != null && ctx.isCancelled()) {
                    // requestCancel 会直接强杀进程 → waitForExit 走 finished=true 路径，须在此补发取消语义（T15 审查）
                    return new Result(output + "\n[运行已取消，进程已终止]", -1, false, false);
                }
                return new Result(output, process.exitValue(), false, false);
            } finally {
                if (ctx != null) {
                    ctx.clearProcess(process);
                }
            }
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

    /** Windows 子进程输出走 OEM/ANSI 码页（本机为 936/GBK），POSIX 为 UTF-8。 */
    private static java.nio.charset.Charset outputCharset() {
        if (isWindows()) {
            String name = System.getProperty("native.encoding");
            try {
                return java.nio.charset.Charset.forName(name == null || name.isBlank() ? "GBK" : name);
            } catch (Exception e) {
                return java.nio.charset.Charset.forName("GBK");
            }
        }
        return StandardCharsets.UTF_8;
    }

    /**
     * 轮询 isAlive（50ms 粒度）而非 waitFor(timeout)：出于确定性边界考虑。
     * 注：曾怀疑 waitFor 本身溢出（"1s 阻塞 4.9s"），复核表明是本机 ping 被网络策略
     * 拖慢（~4.5s）造成的混淆，waitFor 实测精确——保留轮询仅为行为确定性，此注释留作记录。
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
