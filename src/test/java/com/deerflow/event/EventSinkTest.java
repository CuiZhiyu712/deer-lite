package com.deerflow.event;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

class EventSinkTest {

    /** 用 Mockito 假 emitter：真实走 send→emit 路径，验证顺序/关闭语义与 JSON 序列化。 */
    @Test
    void sendsEventsAndStopsAfterComplete() throws java.io.IOException {
        var emitter = org.mockito.Mockito.mock(org.springframework.web.servlet.mvc.method.annotation.SseEmitter.class);
        EventSink sink = new EventSink(emitter, new ObjectMapper());

        sink.send(new AgentEvent.TextDelta("你"));
        sink.send(new AgentEvent.RunEnd("done", null));
        org.mockito.Mockito.verify(emitter, org.mockito.Mockito.times(2))
                .send(org.mockito.ArgumentMatchers.any(org.springframework.web.servlet.mvc.method.annotation.SseEmitter.SseEventBuilder.class));
        org.mockito.Mockito.verify(emitter, org.mockito.Mockito.never()).complete();

        sink.complete();
        org.mockito.Mockito.verify(emitter).complete();
        assertThat(sink.isClosed()).isTrue();

        sink.send(new AgentEvent.TextDelta("ignored"));
        org.mockito.Mockito.verify(emitter, org.mockito.Mockito.times(2))
                .send(org.mockito.ArgumentMatchers.any(org.springframework.web.servlet.mvc.method.annotation.SseEmitter.SseEventBuilder.class));
    }

    @Test
    void serializesEventPayloadAsJson() throws java.io.IOException {
        var emitter = org.mockito.Mockito.mock(org.springframework.web.servlet.mvc.method.annotation.SseEmitter.class);
        EventSink sink = new EventSink(emitter, new ObjectMapper());
        sink.send(new AgentEvent.TextDelta("你"));

        var captor = org.mockito.ArgumentCaptor.forClass(org.springframework.web.servlet.mvc.method.annotation.SseEmitter.SseEventBuilder.class);
        org.mockito.Mockito.verify(emitter).send(captor.capture());
        // Spring 7 build() 返回 Set<DataWithMediaType>（无 toString 渲染），
        // 逐元素取 data 拼接即真实 emitter 写出的内容：event:text_delta\ndata:{json}\n\n
        String rendered = captor.getValue().build().stream()
                .map(d -> String.valueOf(d.getData()))
                .collect(java.util.stream.Collectors.joining());
        assertThat(rendered).contains("text_delta").contains("delta").contains("你");
    }
}
