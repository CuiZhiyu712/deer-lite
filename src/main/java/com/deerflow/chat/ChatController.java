package com.deerflow.chat;

import com.deerflow.agent.AgentService;
import com.deerflow.chat.dto.Dtos.CreateSessionRequest;
import com.deerflow.chat.dto.Dtos.MessageDto;
import com.deerflow.chat.dto.Dtos.RunRequest;
import com.deerflow.chat.dto.Dtos.SessionDto;
import com.deerflow.config.ModelRegistry;
import com.deerflow.persistence.ChatSession;
import com.deerflow.persistence.ChatSessionRepository;
import com.deerflow.persistence.MessageRepository;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api")
public class ChatController {

    private final ChatSessionRepository sessionRepo;
    private final MessageRepository messageRepo;
    private final AgentService agentService;
    private final ModelRegistry modelRegistry;

    public ChatController(ChatSessionRepository sessionRepo,
                          MessageRepository messageRepo,
                          AgentService agentService,
                          ModelRegistry modelRegistry) {
        this.sessionRepo = sessionRepo;
        this.messageRepo = messageRepo;
        this.agentService = agentService;
        this.modelRegistry = modelRegistry;
    }

    @PostMapping("/sessions")
    public SessionDto create(@RequestBody(required = false) CreateSessionRequest req) {
        String title = (req == null || req.title() == null || req.title().isBlank()) ? "新会话" : req.title();
        var session = new ChatSession(UUID.randomUUID().toString(), title, "deepseek-chat", Instant.now());
        sessionRepo.save(session);
        return toDto(session);
    }

    @GetMapping("/sessions")
    public List<SessionDto> list() {
        return sessionRepo.findAllByOrderByUpdatedAtDesc().stream().map(this::toDto).toList();
    }

    @GetMapping("/models")
    public List<String> models() {
        return modelRegistry.names();
    }

    @GetMapping("/sessions/{id}")
    public SessionDto get(@PathVariable String id) {
        return toDto(sessionRepo.findById(id).orElseThrow(
                () -> new IllegalArgumentException("会话不存在: " + id)));
    }

    @GetMapping("/sessions/{id}/messages")
    public List<MessageDto> messages(@PathVariable String id) {
        return messageRepo.findBySessionIdOrderBySeqAsc(id).stream()
                .map(m -> new MessageDto(m.getId(), m.getRole(), m.getContent(), m.getToolCallsJson(),
                        m.getToolCallId(), m.getToolName(), m.getCreatedAt()))
                .toList();
    }

    @PostMapping(value = "/sessions/{id}/runs", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public org.springframework.web.servlet.mvc.method.annotation.SseEmitter run(
            @PathVariable String id, @RequestBody RunRequest req, HttpServletResponse response) {
        if (req.model() != null && !req.model().isBlank()) {
            var session = sessionRepo.findById(id).orElseThrow(() -> new IllegalArgumentException("会话不存在: " + id));
            session.setModel(req.model());
            session.touch();
            sessionRepo.save(session);
        }
        var started = agentService.startRun(id, req.input());
        // 启动即失败（如会话忙）时会抛异常；能走到这里说明 run 已受理——
        // 把 runId 放进响应头，前端无需等 run_start 事件即可启用停止按钮（T16 审查 M2）
        response.setHeader("X-Run-Id", started.runId());
        return started.emitter();
    }

    @PostMapping("/runs/{runId}/cancel")
    public Map<String, Object> cancel(@PathVariable String runId) {
        agentService.cancel(runId);
        return Map.of("ok", true);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public Map<String, String> notFound(IllegalArgumentException e) {
        return Map.of("error", e.getMessage());
    }

    @ExceptionHandler(IllegalStateException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public Map<String, String> conflict(IllegalStateException e) {
        return Map.of("error", e.getMessage());
    }

    private SessionDto toDto(ChatSession s) {
        return new SessionDto(s.getId(), s.getTitle(), s.getModel(), s.getTodosJson(), s.getUpdatedAt());
    }
}
