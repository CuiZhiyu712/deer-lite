package com.deerflow.event;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.ObjectMapper;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 单次 run 的 SSE 出口。线程安全：run 虚拟线程与 ping 定时线程都会写。
 */
public class EventSink {

    private static final Logger log = LoggerFactory.getLogger(EventSink.class);

    private final SseEmitter emitter;
    private final ObjectMapper objectMapper;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    public EventSink(SseEmitter emitter, ObjectMapper objectMapper) {
        this.emitter = emitter;
        this.objectMapper = objectMapper;
    }

    public void send(AgentEvent event) {
        if (closed.get()) {
            return;
        }
        try {
            String json = objectMapper.writeValueAsString(event);
            emit(event.type(), json);
        } catch (Exception e) {
            markBroken();
        }
    }

    /** 便于测试覆写。 */
    protected void emit(String name, Object data) throws Exception {
        synchronized (this) {
            emitter.send(SseEmitter.event().name(name).data(data));
        }
    }

    public synchronized void complete() {
        if (closed.compareAndSet(false, true)) {
            emitter.complete();
        }
    }

    public void fail(Throwable t) {
        if (closed.compareAndSet(false, true)) {
            emitter.completeWithError(t);
        }
    }

    private void markBroken() {
        if (closed.compareAndSet(false, true)) {
            log.warn("SSE sink broken (client disconnected?), run continues and will persist");
        }
    }

    public boolean isClosed() {
        return closed.get();
    }
}
