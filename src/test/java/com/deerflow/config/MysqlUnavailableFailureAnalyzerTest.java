package com.deerflow.config;

import org.hibernate.exception.JDBCConnectionException;
import org.hibernate.service.spi.ServiceException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.BeanCreationException;

import java.net.ConnectException;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;

class MysqlUnavailableFailureAnalyzerTest {

    private final MysqlUnavailableFailureAnalyzer analyzer = new MysqlUnavailableFailureAnalyzer();

    @Test
    void translatesConnectionRefusedDuringHibernateBootIntoActionableGuidance() {
        var refused = new ConnectException("Connection refused: getsockopt");
        var comms = new SQLException("Communications link failure", refused);
        Throwable bootFailure = new BeanCreationException("Error creating bean with name 'entityManagerFactory'",
                new ServiceException("Unable to create requested service [JdbcEnvironment]",
                        new JDBCConnectionException("Unable to obtain isolated JDBC connection", comms)));

        var analysis = analyzer.analyze(bootFailure);

        assertThat(analysis).isNotNull();
        assertThat(analysis.getDescription())
                .contains("MySQL 未就绪：请先在项目根目录执行 docker compose up -d");
    }

    @Test
    void translatesRealHibernate7ChainWhereConnectionCauseIsAlreadyLost() {
        // Hibernate 7.4 实测：连接失败只留在 WARN 日志，最终异常只剩方言解析失败
        Throwable bootFailure = new BeanCreationException("Error creating bean with name 'entityManagerFactory'",
                new ServiceException("Unable to create requested service [org.hibernate.engine.jdbc.env.spi.JdbcEnvironment]"
                        + " due to: Unable to determine Dialect without JDBC metadata"
                        + " (please set 'jakarta.persistence.jdbc.url' for common cases or 'hibernate.dialect'"));

        var analysis = analyzer.analyze(bootFailure);

        assertThat(analysis).isNotNull();
        assertThat(analysis.getDescription())
                .contains("MySQL 未就绪：请先在项目根目录执行 docker compose up -d");
    }

    @Test
    void ignoresAuthFailuresAndUnrelatedErrors() {
        Throwable denied = new JDBCConnectionException("Unable to obtain isolated JDBC connection",
                new SQLException("Access denied for user 'root'@'localhost' (using password: YES)"));
        assertThat(analyzer.analyze(denied)).isNull();

        assertThat(analyzer.analyze(new IllegalStateException("boom"))).isNull();
        assertThat(analyzer.analyze(new ConnectException("Connection refused"))).isNull();
    }
}
