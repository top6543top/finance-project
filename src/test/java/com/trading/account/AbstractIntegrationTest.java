package com.trading.account;

import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.MySQLContainer;

// 테스트 클래스마다 컨테이너를 따로 띄우면 클래스별로 Spring 컨텍스트가 갈려서
// (JDBC 포트가 매번 달라 컨텍스트 캐시가 재사용 안 됨) JVM 종료 시 이미 죽은 컨테이너에
// 재연결을 시도하며 HikariCP가 커넥션당 30초씩 타임아웃을 낸다.
// JVM 전체에서 컨테이너 하나만 띄우고 공유 — @Container(JUnit 라이프사이클)로 관리하지 않고
// static 블록에서 직접 start()만 호출해 테스트 클래스 하나의 @AfterAll에 stop되지 않게 하고,
// 종료는 Testcontainers Ryuk이 JVM 종료 시점에 맡는다.
public abstract class AbstractIntegrationTest {

    @ServiceConnection
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0");

    static {
        MYSQL.start();
    }
}
