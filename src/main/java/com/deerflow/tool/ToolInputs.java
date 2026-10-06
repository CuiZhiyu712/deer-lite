package com.deerflow.tool;

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
}
