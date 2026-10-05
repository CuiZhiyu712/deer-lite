package com.deerflow.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.method.MethodToolCallback;
import org.springframework.ai.tool.support.ToolDefinitions;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.ReflectionUtils;

import com.deerflow.tool.ToolExecutionDecorator;

@Configuration
public class AiConfig {

    @Bean
    ChatClient chatClient(ChatClient.Builder builder) {
        return builder.build();
    }

    @Bean
    ToolCallback timeTool() {
        var method = ReflectionUtils.findMethod(TimeTools.class, "now");
        // 计划代码为 ToolDefinition.builder(method)；2.0.1 实际在 ToolDefinitions 支持类上
        return new ToolExecutionDecorator(MethodToolCallback.builder()
                .toolDefinition(ToolDefinitions.builder(method)
                        .description("获取当前服务器时间")
                        .build())
                .toolMethod(method)
                .toolObject(new TimeTools())
                .build());
    }

    static class TimeTools {
        public String now() {
            return java.time.OffsetDateTime.now().toString();
        }
    }
}
