package com.deerflow.config;

import org.springframework.boot.diagnostics.FailureAnalysis;
import org.springframework.boot.diagnostics.FailureAnalyzer;

/**
 * Hibernate 在 EntityManagerFactory 初始化阶段（早于 StartupChecks 这个 ApplicationRunner）
 * 就必须连接数据库，MySQL 未就绪时启动会在方言解析/模式管理处抛异常。
 * 这里把这一类连接失败翻译成可操作的启动提示，补上 StartupChecks 覆盖不到的路径。
 */
public class MysqlUnavailableFailureAnalyzer implements FailureAnalyzer {

    private static final String DESCRIPTION =
            "MySQL 未就绪：请先在项目根目录执行 docker compose up -d，再重启应用。";

    @Override
    public FailureAnalysis analyze(Throwable failure) {
        if (!isMysqlConnectionFailure(failure)) {
            return null;
        }
        return new FailureAnalysis(DESCRIPTION,
                "启动 MySQL：docker compose up -d（容器已创建时可 docker compose start mysql），然后重新启动应用。",
                failure);
    }

    private boolean isMysqlConnectionFailure(Throwable failure) {
        boolean jdbcContext = false;
        boolean connectionRefused = false;
        Throwable t = failure;
        while (t != null) {
            String name = t.getClass().getName();
            if (name.startsWith("java.sql.") || name.startsWith("jakarta.persistence.")
                    || name.startsWith("org.hibernate.") || name.startsWith("com.mysql.")
                    || name.startsWith("org.springframework.jdbc.")
                    || name.startsWith("org.springframework.orm.jpa")) {
                jdbcContext = true;
            }
            String message = String.valueOf(t.getMessage());
            if (name.contains("CommunicationsException")
                    || (name.equals("java.net.ConnectException")
                        && message.contains("Connection refused"))) {
                connectionRefused = true;
            }
            // Hibernate 7 拿不到 JDBC metadata 时会丢掉连接层原因（只留在 WARN 日志里），
            // 最终异常只剩这条方言解析失败——对 MySQL 场景等价于连接不可用
            if (name.startsWith("org.hibernate.")
                    && message.contains("Unable to determine Dialect without JDBC metadata")) {
                jdbcContext = true;
                connectionRefused = true;
            }
            Throwable cause = t.getCause();
            if (cause == t) {
                break;
            }
            t = cause;
        }
        return jdbcContext && connectionRefused;
    }
}
