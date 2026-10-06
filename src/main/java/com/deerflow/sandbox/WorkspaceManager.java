package com.deerflow.sandbox;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

@Component
public class WorkspaceManager {

    private final Path root;

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

    /** 把模型给的相对路径解析到会话工作区内；任何越界输入直接拒绝。 */
    public Path resolveSafe(String sessionId, String relative) {
        Path base = sessionDir(sessionId);
        Path candidate = base.resolve(relative.replace('\\', '/')).normalize();
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
