package com.deerflow.persistence;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
class RepositoryTest {

    @Autowired ChatSessionRepository sessionRepo;
    @Autowired MessageRepository messageRepo;
    @Autowired RunRepository runRepo;

    @Test
    void roundTripSessionMessageRun() {
        var session = new ChatSession("s1", "测试会话", "deepseek-chat", Instant.now());
        sessionRepo.save(session);

        messageRepo.save(new MessageEntity("m1", "s1", 0, "USER", "你好", null, null, null, Instant.now()));
        messageRepo.save(new MessageEntity("m2", "s1", 1, "ASSISTANT", "你好！", null, null, null, Instant.now()));

        var run = new Run("r1", "s1", "DONE", "你好", null, 10L, 20L, Instant.now(), Instant.now());
        runRepo.save(run);

        assertThat(sessionRepo.findById("s1")).isPresent();
        List<MessageEntity> msgs = messageRepo.findBySessionIdOrderBySeqAsc("s1");
        assertThat(msgs).hasSize(2);
        assertThat(msgs.get(1).getRole()).isEqualTo("ASSISTANT");
        assertThat(runRepo.findBySessionIdOrderByStartedAtDesc("s1")).hasSize(1);
    }

    @Test
    void toolFieldsChineseAndSeqOrderingRoundTrip() {
        sessionRepo.save(new ChatSession("s2", "工具会话", "deepseek-chat", Instant.now()));
        String toolCallsJson = "[{\"id\":\"c1\",\"type\":\"function\",\"name\":\"bash\",\"arguments\":\"{\\\"command\\\":\\\"ls\\\"}\"}]";
        messageRepo.save(new MessageEntity("m3", "s2", 2, "TOOL", "结果【中文】", null, "c1", "bash", Instant.now()));
        messageRepo.save(new MessageEntity("m1", "s2", 0, "USER", "中文问题", null, null, null, Instant.now()));
        messageRepo.save(new MessageEntity("m2", "s2", 1, "ASSISTANT", "", toolCallsJson, null, null, Instant.now()));

        List<MessageEntity> ordered = messageRepo.findBySessionIdOrderBySeqAsc("s2");
        assertThat(ordered).extracting(MessageEntity::getSeq).containsExactly(0, 1, 2);
        assertThat(ordered.get(0).getContent()).isEqualTo("中文问题");
        assertThat(ordered.get(1).getToolCallsJson()).isEqualTo(toolCallsJson);
        MessageEntity tool = ordered.get(2);
        assertThat(tool.getToolCallId()).isEqualTo("c1");
        assertThat(tool.getToolName()).isEqualTo("bash");
        assertThat(tool.getContent()).isEqualTo("结果【中文】");
        assertThat(messageRepo.countBySessionId("s2")).isEqualTo(3);
    }
}
