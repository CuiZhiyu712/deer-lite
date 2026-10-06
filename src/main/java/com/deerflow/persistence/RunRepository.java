package com.deerflow.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface RunRepository extends JpaRepository<Run, String> {
    List<Run> findBySessionIdOrderByStartedAtDesc(String sessionId);

    List<Run> findByStatus(String status);
}
