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

    @Test
    void brokenSinkClosesAndStopsSending() throws Exception {
        var emitter = org.mockito.Mockito.mock(org.springframework.web.servlet.mvc.method.annotation.SseEmitter.class);
        org.mockito.Mockito.doThrow(new java.io.IOException("client gone"))
                .when(emitter).send(org.mockito.ArgumentMatchers.any(org.springframework.web.servlet.mvc.method.annotation.SseEmitter.SseEventBuilder.class));
        EventSink sink = new EventSink(emitter, new tools.jackson.databind.ObjectMapper());

        sink.send(new AgentEvent.TextDelta("x"));

        assertThat(sink.isClosed()).isTrue();
        org.mockito.Mockito.verify(emitter).completeWithError(org.mockito.ArgumentMatchers.any(java.io.IOException.class));
        sink.send(new AgentEvent.TextDelta("y"));
        org.mockito.Mockito.verify(emitter, org.mockito.Mockito.times(1))
                .send(org.mockito.ArgumentMatchers.any(org.springframework.web.servlet.mvc.method.annotation.SseEmitter.SseEventBuilder.class));
    }

    @Test
    void concurrentSendAndCompleteNeverSendsAfterComplete() throws Exception {
        var emitter = org.mockito.Mockito.mock(org.springframework.web.servlet.mvc.method.annotation.SseEmitter.class);
        var order = new java.util.concurrent.CopyOnWriteArrayList<String>();
        org.mockito.Mockito.doAnswer(inv -> { order.add("send"); return null; })
                .when(emitter).send(org.mockito.ArgumentMatchers.any(org.springframework.web.servlet.mvc.method.annotation.SseEmitter.SseEventBuilder.class));
        org.mockito.Mockito.doAnswer(inv -> { order.add("complete"); return null; }).when(emitter).complete();
        EventSink sink = new EventSink(emitter, new tools.jackson.databind.ObjectMapper());

        var latch = new java.util.concurrent.CountDownLatch(1);
        var t1 = Thread.ofVirtual().start(() -> {
            try { latch.await(); for (int i = 0; i < 50; i++) { sink.send(new AgentEvent.TextDelta("x" + i)); } }
            catch (InterruptedException ignored) { }
        });
        var t2 = Thread.ofVirtual().start(() -> {
            try { latch.await(); sink.complete(); }
            catch (InterruptedException ignored) { }
        });
        latch.countDown();
        t1.join();
        t2.join();

        assertThat(sink.isClosed()).isTrue();
        assertThat(order).contains("complete");
        assertThat(order.get(order.size() - 1)).isEqualTo("complete"); // complete 之后没有任何 send
    }
}
