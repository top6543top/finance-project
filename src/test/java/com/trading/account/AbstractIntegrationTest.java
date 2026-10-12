package com.trading.account;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

// JVM 전체에서 MySQL/Redis 컨테이너를 공유한다. Flyway로 같은 버전 스키마를 만들고
// Hibernate는 validate만 수행하므로 여러 Spring 컨텍스트 종료 시 DDL 충돌이 없다.
// 테스트 간 업무 데이터는 아래에서 비우고, Batch 시퀀스와 Flyway 이력은 보존한다.
public abstract class AbstractIntegrationTest {

    @ServiceConnection
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0");

    // 이미지 이름이 "redis"로 시작하면 Spring Boot가 GenericContainer도 spring.data.redis.*로
    // 자동 연결해준다 (MySQL과 같은 @ServiceConnection 메커니즘) — 별도 RedisContainer 모듈 불필요.
    @ServiceConnection("redis")
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    static {
        MYSQL.start();
        REDIS.start();
    }

    // 테이블 이름을 하드코딩하면 나중에 엔티티가 추가돼도 여기 안 늘리면 그 테이블만
    // 안 비워져서 지금 고치는 것과 같은 unique 제약 위반이 조용히 재발한다.
    // information_schema에서 실제 테이블 목록을 매번 조회해 스키마 변경에 안 썩게 한다.
    @BeforeEach
    void cleanDatabase() throws SQLException {
        try (Connection conn = MYSQL.createConnection("");
             Statement stmt = conn.createStatement()) {
            List<String> tables = new ArrayList<>();
            try (ResultSet rs = stmt.executeQuery(
                    // BATCH_*는 제외: MySQL용 Spring Batch는 BATCH_JOB_SEQ 등 시퀀스 테이블의 단일 행을
                    // 증가시켜 ID를 만든다. 그 행을 지우면 한 테스트에서 Job을 두 번 돌릴 때 ID가 겹쳐 PK 충돌.
                    "SELECT table_name FROM information_schema.tables "
                            + "WHERE table_schema = DATABASE() AND table_name NOT LIKE 'BATCH\\_%' "
                            + "AND table_name <> 'flyway_schema_history'")) {
                while (rs.next()) {
                    tables.add(rs.getString(1));
                }
            }
            stmt.execute("SET FOREIGN_KEY_CHECKS=0");
            for (String table : tables) {
                stmt.execute("TRUNCATE TABLE " + table);
            }
            stmt.execute("SET FOREIGN_KEY_CHECKS=1");
        }
    }
}
