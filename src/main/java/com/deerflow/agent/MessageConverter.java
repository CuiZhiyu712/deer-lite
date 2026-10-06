package com.deerflow.agent;

import com.deerflow.persistence.MessageEntity;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
            throw new IllegalStateException("toolCallsJson 解析失败: "
                    + (json.length() > 200 ? json.substring(0, 200) + "…" : json), e);
        }
    }

    /**
     * 历史重放：TOOL 行必须跟随带 toolCalls 的 ASSISTANT 行，否则 API 会报 400。
     * 半写/孤儿结构必须在此自愈（T14 审查）：缺响应的 toolCalls 降级为纯文本 assistant，
     * 孤立 TOOL 行直接丢弃——否则一行坏数据会让该会话此后所有 run 永久 400。
     */
    public List<Message> toDomain(List<MessageEntity> rows) {
        List<Message> out = new ArrayList<>();
        int i = 0;
        while (i < rows.size()) {
            MessageEntity r = rows.get(i);
            switch (r.getRole()) {
                case "USER" -> {
                    out.add(new UserMessage(r.getContent() == null ? "" : r.getContent()));
                    i++;
                }
                case "ASSISTANT" -> {
                    List<ToolCallRecord> calls = parseToolCalls(r.getToolCallsJson());
                    String content = r.getContent() == null ? "" : r.getContent();
                    if (calls.isEmpty()) {
                        out.add(new AssistantMessage(content));
                        i++;
                        break;
                    }
                    Map<String, MessageEntity> responses = new LinkedHashMap<>();
                    int j = i + 1;
                    while (j < rows.size() && "TOOL".equals(rows.get(j).getRole())) {
                        responses.put(rows.get(j).getToolCallId(), rows.get(j));
                        j++;
                    }
                    List<ToolCallRecord> answered = calls.stream().filter(c -> responses.containsKey(c.id())).toList();
                    if (answered.size() < calls.size()) {
                        // 结构不完整（进程中断留下的半写）：降级为纯文本，丢弃未答 toolCalls 与孤立响应
                        out.add(new AssistantMessage(content));
                    } else {
                        out.add(AssistantMessage.builder()
                                .content(content)
                                .toolCalls(answered.stream()
                                        .map(c -> new AssistantMessage.ToolCall(
                                                c.id(), c.type() == null ? "function" : c.type(), c.name(),
                                                c.arguments() == null ? "{}" : c.arguments()))
                                        .toList())
                                .build());
                        for (ToolCallRecord c : answered) {
                            MessageEntity tr = responses.get(c.id());
                            out.add(ToolResponseMessage.builder()
                                    .responses(List.of(new ToolResponseMessage.ToolResponse(
                                            c.id(), c.name(), tr.getContent() == null ? "" : tr.getContent())))
                                    .build());
                        }
                    }
                    i = j;
                }
                case "TOOL" -> i++; // 孤立 TOOL 行：丢弃
                default -> i++;
            }
        }
        return out;
    }
}
