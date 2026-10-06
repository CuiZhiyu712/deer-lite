package com.deerflow.config;

import com.deerflow.persistence.Run;
import com.deerflow.persistence.RunRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.time.Instant;
import java.util.List;

@Component
public class StartupChecks implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(StartupChecks.class);

    private final DataSource dataSource;
    private final RunRepository runRepo;
    private final String apiKey;

    public StartupChecks(DataSource dataSource,
                         RunRepository runRepo,
                         @Value("${spring.ai.openai.api-key:}") String apiKey) {
        this.dataSource = dataSource;
        this.runRepo = runRepo;
        this.apiKey = apiKey;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (apiKey == null || apiKey.isBlank() || apiKey.startsWith("missing-key")) {
            throw new IllegalStateException("""
                    未配置 DeepSeek API Key。请把 key 写入项目根目录的 application-local.yml：
                      spring.ai.openai.api-key: sk-xxx
                    然后用 ./mvnw spring-boot:run -Dspring-boot.run.profiles=local 启动。""");
        }
        try (Connection c = dataSource.getConnection()) {
            log.info("MySQL 连接正常: {}", c.getMetaData().getURL());
        } catch (Exception e) {
            throw new IllegalStateException("MySQL 未就绪：请先在项目根目录执行 docker compose up -d，再重启应用。", e);
        }
        // 上次进程崩溃/被强杀留下的 RUNNING 幽灵行：标记为中断（T15/T16 审查归口）
        List<Run> stale = runRepo.findByStatus("RUNNING");
        for (Run r : stale) {
            r.setStatus("FAILED");
            r.setError("应用重启，运行被中断");
            r.setEndedAt(Instant.now());
        }
        if (!stale.isEmpty()) {
            runRepo.saveAll(stale);
            log.warn("清理 {} 条中断的 RUNNING 记录", stale.size());
        }
    }
}
