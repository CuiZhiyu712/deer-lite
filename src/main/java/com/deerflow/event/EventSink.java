package com.deerflow.event;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.ObjectMapper;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 单次 run 的 SSE 出口。线程安全：
 * - 所有 emitter 触达（send/complete/completeWithError）都在同一把对象锁内，与 closed 检查互斥；
 * - 断连或发送失败时关闭 sink 并 completeWithError，避免无超时 SseEmitter 把客户端挂死。
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
        try {
            String json = objectMapper.writeValueAsString(event);
            emit(event.type(), json);
        } catch (Exception e) {
            markBroken(e);
        }
    }

    /** 便于测试覆写；closed 检查在锁内，与 complete/fail 同锁，保证 complete 之后不再触达 emitter。 */
    protected void emit(String name, Object data) throws Exception {
        synchronized (this) {
            if (closed.get()) {
                return;
            }
            emitter.send(SseEmitter.event().name(name).data(data));
        }
    }

    public synchronized void complete() {
        if (closed.compareAndSet(false, true)) {
            emitter.complete();
        }
    }

    public synchronized void fail(Throwable t) {
        if (closed.compareAndSet(false, true)) {
            emitter.completeWithError(t);
        }
    }

    private void markBroken(Throwable cause) {
        if (closed.compareAndSet(false, true)) {
            log.warn("SSE send failed; closing sink (client likely disconnected or payload error)", cause);
            try {
                emitter.completeWithError(cause);
            } catch (Exception ignore) {
                // emitter 已断开时 completeWithError 也可能抛；无需处理
            }
        } else {
            log.debug("SSE send failed after sink already closed", cause);
        }
    }

    public boolean isClosed() {
        return closed.get();
    }
}
