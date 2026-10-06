package com.deerflow.agent;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.DefaultToolCallingChatOptions;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/** 按脚本逐轮返回的假模型：push 什么，第 N 次 stream 调用就返回什么。 */
public class ScriptedChatModel implements ChatModel {

    private final Queue<Flux<ChatResponse>> rounds = new ConcurrentLinkedQueue<>();

    /** 工具轮：单个 chunk 即携带完整 toolCalls（2.0.1 聚合器单 chunk 直通，实测见 T15 报告）。 */
    public void pushToolCall(String id, String name, String argsJson) {
        var message = AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall(id, "function", name, argsJson)))
                .build();
        var meta = ChatGenerationMetadata.builder().finishReason("tool_calls").build();
        rounds.add(Flux.just(new ChatResponse(List.of(new Generation(message, meta)))));
    }

    public void pushText(String... chunks) {
        List<ChatResponse> list = new ArrayList<>();
        for (int i = 0; i < chunks.length; i++) {
            var meta = i == chunks.length - 1
                    ? ChatGenerationMetadata.builder().finishReason("stop").build()
                    : ChatGenerationMetadata.builder().finishReason("").build();
            list.add(new ChatResponse(List.of(new Generation(new AssistantMessage(chunks[i]), meta))));
        }
        rounds.add(Flux.fromIterable(list));
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        return stream(prompt).blockLast();
    }

    /**
     * 关键：必须返回 ToolCallingChatOptions。2.0.1 的 DefaultChatClientUtils.toChatClientRequest
     * 仅当 chatModel.getOptions().mutate() 是 ToolCallingChatOptions.Builder 时才把请求级
     * toolCallbacks 合并进 options；否则 ToolCallingAdvisor 因 options 不是 ToolCallingChatOptions
     * 直接透传，工具循环不触发（真实 OpenAiChatModel.getOptions() 返回 OpenAiChatOptions 满足此条件）。
     */
    @Override
    public ChatOptions getOptions() {
        return DefaultToolCallingChatOptions.builder().build();
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        Flux<ChatResponse> next = rounds.poll();
        if (next == null) {
            throw new IllegalStateException("ScriptedChatModel 脚本已用尽");
        }
        return next;
    }

    @TestConfiguration
    static class Config {
        @Bean
        @Primary
        ScriptedChatModel scriptedChatModel() {
            return new ScriptedChatModel();
        }
    }
}
