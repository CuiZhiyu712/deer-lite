package com.deerflow.agent;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class PromptBuilderTest {

    @Test
    void rendersPlaceholders() {
        String prompt = PromptBuilder.systemPrompt(Path.of("/tmp/ws/s1"));
        // Path.toString() 平台相关（Windows 为 \tmp\ws\s1），断言用同一调用保持平台无关
        assertThat(prompt).contains(Path.of("/tmp/ws/s1").toString()).contains(LocalDate.now().toString())
                .contains(System.getProperty("os.name"));
    }
}
