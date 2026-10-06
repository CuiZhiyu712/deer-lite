package com.deerflow.sandbox;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

@Component
public class WorkspaceManager {

    private final Path root;

    // 多构造器的 Spring bean 必须显式标注，否则容器无法选择并启动失败（T8 审查实测）
    @org.springframework.beans.factory.annotation.Autowired
    public WorkspaceManager(org.springframework.core.env.Environment env) {
        this(env.getProperty("deerflow.sandbox.root", "./workspace"));
    }

    public WorkspaceManager(String root) {
        this.root = Path.of(root).toAbsolutePath().normalize();
    }

    public Path sessionDir(String sessionId) {
        Path dir = root.resolve(sanitize(sessionId)).normalize();
        if (!dir.startsWith(root)) {
            throw new SandboxSecurityException("非法会话目录: " + sessionId);
        }
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return dir;
    }

    private static final java.util.regex.Pattern RESERVED =
            java.util.regex.Pattern.compile("(?i)^(con|prn|aux|nul|com[1-9]|lpt[1-9])(\\..*)?$");

    /** 把模型给的相对路径解析到会话工作区内；任何越界输入直接拒绝。 */
    public Path resolveSafe(String sessionId, String relative) {
        if (relative == null || relative.isBlank()) {
            throw new SandboxSecurityException("路径不能为空");
        }
        Path base = sessionDir(sessionId);
        String normalized = relative.replace('\\', '/');
        for (String segment : normalized.split("/")) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                continue;
            }
            if (segment.endsWith(".") || segment.endsWith(" ")) {
                throw new SandboxSecurityException("非法路径片段（尾随点/空格）: " + relative);
            }
            if (RESERVED.matcher(segment).matches()) {
                throw new SandboxSecurityException("非法路径片段（Windows 保留名）: " + relative);
            }
        }
        Path candidate = base.resolve(normalized).normalize();
        if (!candidate.startsWith(base)) {
            throw new SandboxSecurityException("路径越界，禁止访问工作区之外: " + relative);
        }
        return candidate;
    }

    private static String sanitize(String sessionId) {
        if (sessionId == null || !sessionId.matches("[A-Za-z0-9_-]{1,64}")) {
            throw new SandboxSecurityException("非法会话 ID: " + sessionId);
        }
        return sessionId;
    }
}
