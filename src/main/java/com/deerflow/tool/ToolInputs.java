package com.deerflow.tool;

import java.util.List;

/** 对模型暴露的工具入参（FunctionToolCallback 按 record 自动生成 JSON Schema）。 */
public final class ToolInputs {

    private ToolInputs() {}

    public record ReadFile(String path) {}
    public record WriteFile(String path, String content) {}
    public record StrReplace(String path, String oldText, String newText) {}
    public record Ls(String path) {}
    public record Bash(String command) {}
    public record WebSearch(String query, Integer maxResults) {}
    public record WebFetch(String url) {}

    public record WriteTodos(List<TodoInput> todos) {
        public record TodoInput(String content, String status) {}
    }
}
