package com.deerflow.agent;

import com.deerflow.persistence.MessageEntity;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MessageConverterTest {

    MessageConverter converter() {
        return new MessageConverter(new ObjectMapper());
    }

    @Test
    void toolCallsSerializeParseRoundTrip() {
        var calls = List.of(new MessageConverter.ToolCallRecord("id1", "function", "bash", "{\"command\":\"ls\"}"));
        String json = converter().serialiseToolCalls(calls);
        assertThat(converter().parseToolCalls(json)).isEqualTo(calls);
    }

    @Test
    void rebuildsAssistantWithToolCallsAndToolResponses() {
        var conv = converter();
        var rows = List.of(
                new MessageEntity("m1", "s1", 0, "USER", "现在几点", null, null, null, Instant.now()),
                new MessageEntity("m2", "s1", 1, "ASSISTANT", "",
                        conv.serialiseToolCalls(List.of(new MessageConverter.ToolCallRecord("c1", "function", "get_time", "{}"))),
                        null, null, Instant.now()),
                new MessageEntity("m3", "s1", 2, "TOOL", "12:00", null, "c1", "get_time", Instant.now()),
                new MessageEntity("m4", "s1", 3, "ASSISTANT", "现在是 12:00", null, null, null, Instant.now())
        );
        var messages = conv.toDomain(rows);
        assertThat(messages).hasSize(4);
        assertThat(messages.get(0)).isInstanceOf(UserMessage.class);
        var am = (AssistantMessage) messages.get(1);
        assertThat(am.getToolCalls()).hasSize(1);
        assertThat(am.getToolCalls().get(0).name()).isEqualTo("get_time");
        var trm = (ToolResponseMessage) messages.get(2);
        assertThat(trm.getResponses().get(0).id()).isEqualTo("c1");
        assertThat(trm.getResponses().get(0).responseData()).isEqualTo("12:00");
    }
}
