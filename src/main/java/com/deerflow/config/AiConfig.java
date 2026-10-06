package com.deerflow.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.method.MethodToolCallback;
import org.springframework.ai.tool.support.ToolDefinitions;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.ReflectionUtils;

@Configuration
public class AiConfig {

    @Bean
    ChatClient chatClient(ChatClient.Builder builder) {
        return builder.build();
    }

    /** spike 已结束：返回裸工具，不再包装饰器（装饰由 AgentService 每 run 施加）。 */
    @Bean
    ToolCallback timeTool() {
        var method = ReflectionUtils.findMethod(TimeTools.class, "now");
        // 计划代码为 ToolDefinition.builder(method)；2.0.1 实际在 ToolDefinitions 支持类上
        return MethodToolCallback.builder()
                .toolDefinition(ToolDefinitions.builder(method)
                        .description("获取当前服务器时间")
                        .build())
                .toolMethod(method)
                .toolObject(new TimeTools())
                .build();
    }

    static class TimeTools {
        public String now() {
            return java.time.OffsetDateTime.now().toString();
        }
    }
}
