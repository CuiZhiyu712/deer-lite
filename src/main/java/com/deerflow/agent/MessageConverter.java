package com.deerflow.agent;

import com.deerflow.persistence.MessageEntity;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/** DB 行 ↔ Spring AI 消息。tool_calls_json 结构：ToolCallRecord 列表。 */
@Component
public class MessageConverter {

    public record ToolCallRecord(String id, String type, String name, String arguments) {}

    private final ObjectMapper objectMapper;

    public MessageConverter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String serialiseToolCalls(List<ToolCallRecord> calls) {
        try {
            return objectMapper.writeValueAsString(calls);
        } catch (Exception e) {
            throw new IllegalStateException("toolCalls 序列化失败", e);
        }
    }

    public List<ToolCallRecord> parseToolCalls(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, ToolCallRecord.class));
        } catch (Exception e) {
            throw new IllegalStateException("toolCallsJson 解析失败: " + json, e);
        }
    }

    /** 历史重放：TOOL 行必须跟随带 toolCalls 的 ASSISTANT 行，否则 API 会报 400。 */
    public List<Message> toDomain(List<MessageEntity> rows) {
        List<Message> out = new ArrayList<>();
        for (MessageEntity r : rows) {
            switch (r.getRole()) {
                case "USER" -> out.add(new UserMessage(r.getContent()));
                case "ASSISTANT" -> {
                    List<ToolCallRecord> calls = parseToolCalls(r.getToolCallsJson());
                    String content = r.getContent() == null ? "" : r.getContent();
                    if (calls.isEmpty()) {
                        out.add(new AssistantMessage(content));
                    } else {
                        out.add(AssistantMessage.builder()
                                .content(content)
                                .toolCalls(calls.stream()
                                        .map(c -> new AssistantMessage.ToolCall(
                                                c.id(), c.type() == null ? "function" : c.type(), c.name(), c.arguments()))
                                        .toList())
                                .build());
                    }
                }
                case "TOOL" -> out.add(ToolResponseMessage.builder()
                        .responses(List.of(new ToolResponseMessage.ToolResponse(
                                r.getToolCallId(), r.getToolName(), r.getContent())))
                        .build());
                default -> { /* 忽略未知角色 */ }
            }
        }
        return out;
    }
}
