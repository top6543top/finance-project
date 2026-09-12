package com.trading.account;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.MySQLContainer;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

// 테스트 클래스마다 컨테이너를 따로 띄우면 클래스별로 Spring 컨텍스트가 갈려서
// (JDBC 포트가 매번 달라 컨텍스트 캐시가 재사용 안 됨) JVM 종료 시 이미 죽은 컨테이너에
// 재연결을 시도하며 HikariCP가 커넥션당 30초씩 타임아웃을 낸다.
// JVM 전체에서 컨테이너 하나만 띄우고 공유 — @Container(JUnit 라이프사이클)로 관리하지 않고
// static 블록에서 직접 start()만 호출해 테스트 클래스 하나의 @AfterAll에 stop되지 않게 하고,
// 종료는 Testcontainers Ryuk이 JVM 종료 시점에 맡는다.
//
// 컨테이너를 공유하면서 물리 DB도 하나가 됐다. @DataJpaTest는 메서드마다 자동 롤백되지만
// @SpringBootTest(동시성 테스트 등)는 롤백이 없어 커밋된 데이터가 남는다 — ddl-auto는
// 컨텍스트 하나 열릴 때 한 번만 도는 거라 다른 컨텍스트가 이미 커밋해둔 데이터까지는
// 못 지운다. 그래서 여러 테스트 클래스가 같은 계좌번호/이메일 같은 고정값을 쓰면 DB에
// 남아있던 이전 테스트 데이터와 충돌해 unique 제약 위반이 난다. 매 테스트 전 전체 테이블을
// 비워 어떤 컨텍스트/순서로 실행되든 항상 빈 스키마에서 시작하도록 보장한다.
public abstract class AbstractIntegrationTest {

    @ServiceConnection
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0");

    static {
        MYSQL.start();
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
                    "SELECT table_name FROM information_schema.tables WHERE table_schema = DATABASE()")) {
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
