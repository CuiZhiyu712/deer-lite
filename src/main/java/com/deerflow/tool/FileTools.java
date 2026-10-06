package com.deerflow.tool;

import com.deerflow.sandbox.WorkspaceManager;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 文件工具底层实现：显式接收 sessionId，便于纯单元测试。
 * 对模型暴露时由 AgentToolsFactory（Task 15）用 FunctionToolCallback 把 sessionId
 * 绑定为闭包，再由 AgentService 统一套 ToolExecutionDecorator。
 */
@Component
public class FileTools {

    private final WorkspaceManager workspace;

    public FileTools(WorkspaceManager workspace) {
        this.workspace = workspace;
    }

    public String readFile(String sessionId, String path) {
        Path p = workspace.resolveSafe(sessionId, path);
        if (Files.isDirectory(p)) {
            return "错误：目标是目录，不是文件: " + path;
        }
        if (!Files.isRegularFile(p)) {
            return "错误：文件不存在 " + path;
        }
        try {
            return Files.readString(p);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public String writeFile(String sessionId, String path, String content) {
        if (content == null) {
            throw new IllegalArgumentException("content 不能为 null（写空文件请显式传空字符串）");
        }
        Path p = workspace.resolveSafe(sessionId, path);
        if (Files.isDirectory(p)) {
            return "错误：目标是目录，无法写入: " + path;
        }
        try {
            if (p.getParent() != null) {
                Files.createDirectories(p.getParent());
            }
            Files.writeString(p, content);
            return "已写入 " + path + " (" + content.length() + " 字符)";
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public String strReplace(String sessionId, String path, String oldText, String newText) {
        if (oldText == null || oldText.isEmpty()) {
            throw new IllegalArgumentException("oldText 不能为空");
        }
        if (newText == null) {
            throw new IllegalArgumentException("newText 不能为 null（删除内容请显式传空字符串）");
        }
        Path p = workspace.resolveSafe(sessionId, path);
        if (Files.isDirectory(p)) {
            return "错误：目标是目录，不是文件: " + path;
        }
        if (!Files.isRegularFile(p)) {
            return "错误：文件不存在 " + path;
        }
        try {
            String content = Files.readString(p);
            int idx = content.indexOf(oldText);
            if (idx < 0) {
                return "错误：未找到要替换的内容: " + oldText;
            }
            Files.writeString(p, content.substring(0, idx) + newText + content.substring(idx + oldText.length()));
            return "已替换 " + path;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public String ls(String sessionId, String path) {
        Path p = workspace.resolveSafe(sessionId, path);
        if (!Files.isDirectory(p)) {
            return "错误：目录不存在 " + path;
        }
        try (Stream<Path> s = Files.list(p)) {
            String listing = s.sorted(Comparator.comparing(Path::getFileName))
                    .map(x -> (Files.isDirectory(x) ? "[dir] " : "[file] ") + x.getFileName())
                    .collect(Collectors.joining("\n"));
            return listing.isEmpty() ? "(空目录)" : listing;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
