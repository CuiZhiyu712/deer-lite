package com.deerflow.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface MessageRepository extends JpaRepository<MessageEntity, String> {
    List<MessageEntity> findBySessionIdOrderBySeqAsc(String sessionId);
    long countBySessionId(String sessionId);
}
