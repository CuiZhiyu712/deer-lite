package com.deerflow.agent;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.LocalDate;

public final class PromptBuilder {

    private static final String TEMPLATE = load();

    private PromptBuilder() {}

    public static String systemPrompt(Path workspacePath) {
        return TEMPLATE
                .replace("{workspacePath}", workspacePath.toString())
                .replace("{osName}", System.getProperty("os.name"))
                .replace("{currentDate}", LocalDate.now().toString());
    }

    private static String load() {
        try (var in = PromptBuilder.class.getClassLoader().getResourceAsStream("prompts/system.st")) {
            if (in == null) {
                throw new IllegalStateException("prompts/system.st 资源缺失");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
