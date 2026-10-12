package com.trading.account;

import org.flywaydb.core.Flyway;
import com.trading.account.common.config.DatabaseMigration;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.DriverManager;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class SchemaMigrationTest {
    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0");

    @Test
    void versionedMigrationsCreateBusinessAndBatchTablesAndCanRunTwice() throws Exception {
        Flyway flyway = Flyway.configure().dataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword())
                .cleanDisabled(true).baselineOnMigrate(false).load();
        flyway.migrate();
        try (Connection connection = mysql.createConnection(""); Statement sql = connection.createStatement()) {
            try (ResultSet result = sql.executeQuery("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() "
                    + "AND table_name IN ('account','member','transaction_history','idempotency_key','BATCH_JOB_EXECUTION')")) {
                result.next();
                assertThat(result.getInt(1)).isEqualTo(5);
            }
        }
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        assertThat(flyway.validateWithResult().validationSuccessful).isTrue();
    }

    @Test
    void preservesDistinctLegacyKeysFromNoPadCollation() throws Exception {
        String database = "review_nopad_upgrade";
        try (Connection admin = DriverManager.getConnection(mysql.getJdbcUrl(), "root", mysql.getPassword());
             Statement sql = admin.createStatement()) {
            sql.execute("CREATE DATABASE " + database + " CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
            sql.execute("GRANT ALL ON " + database + ".* TO 'test'@'%'");
        }
        String url = mysql.getJdbcUrl().replace('/' + mysql.getDatabaseName(), '/' + database);
        Flyway.configure().dataSource(url, mysql.getUsername(), mysql.getPassword()).target("1").load().migrate();
        try (Connection connection = DriverManager.getConnection(url, mysql.getUsername(), mysql.getPassword());
             Statement sql = connection.createStatement()) {
            sql.execute("INSERT INTO idempotency_key(idempotency_key,status,response_body) VALUES"
                    + "('key','COMPLETED','first'),('key ','COMPLETED','second')");
        }
        Flyway flyway = Flyway.configure().dataSource(url,mysql.getUsername(),mysql.getPassword()).load();
        flyway.migrate();
        assertThat(flyway.validateWithResult().validationSuccessful).isTrue();
        try (Connection connection = DriverManager.getConnection(url, mysql.getUsername(), mysql.getPassword());
             Statement sql = connection.createStatement();
             ResultSet result = sql.executeQuery("SELECT legacy_key,response_body FROM idempotency_key WHERE actor_id=0 ORDER BY id")) {
            assertThat(result.next()).isTrue();
            assertThat(result.getString(1)).isEqualTo("key");
            assertThat(result.getString(2)).isEqualTo("first");
            assertThat(result.next()).isTrue();
            assertThat(result.getString(1)).isEqualTo("key ");
            assertThat(result.getString(2)).isEqualTo("second");
            assertThat(result.next()).isFalse();
        }
    }

    @Test
    void upgradesAnExplicitBaselineWithoutLosingMoneyHistoryOrLegacyKeyEquivalence() throws Exception {
        String database = "review_upgrade";
        try (Connection admin = DriverManager.getConnection(mysql.getJdbcUrl(), "root", mysql.getPassword());
             Statement sql = admin.createStatement()) {
            sql.execute("CREATE DATABASE " + database + " CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
            sql.execute("GRANT ALL ON " + database + ".* TO 'test'@'%'");
        }
        String url = mysql.getJdbcUrl().replace('/' + mysql.getDatabaseName(), '/' + database);
        Flyway.configure().dataSource(url, mysql.getUsername(), mysql.getPassword()).target("1").load().migrate();
        try (Connection connection = DriverManager.getConnection(url, mysql.getUsername(), mysql.getPassword());
             Statement sql = connection.createStatement()) {
            sql.execute("INSERT INTO member(id,email,name,password) VALUES(1,'review@example.invalid','Review','test-only')");
            sql.execute("INSERT INTO account(id,account_number,balance,version,member_id) VALUES(1,'legacy-account',100.00,0,1)");
            sql.execute("INSERT INTO transaction_history(to_account_id,amount,type) VALUES(1,100.00,'DEPOSIT')");
            sql.execute("INSERT INTO idempotency_key(idempotency_key,status,response_body) VALUES('Café-Key ','COMPLETED','legacy-response')");
            sql.execute("ALTER TABLE idempotency_key RENAME INDEX uk_legacy_idempotency_key TO hibernate_generated_old_name");
            // This is our disposable database only: simulate an original, unmanaged installation.
            sql.execute("DROP TABLE flyway_schema_history");
        }
        Map<String,String> environment = new HashMap<>(Map.of("FLYWAY_URL",url,"FLYWAY_USER",mysql.getUsername(),
                "FLYWAY_PASSWORD",mysql.getPassword(),"FLYWAY_REQUIRE_TLS","false",
                "FLYWAY_BASELINE_ACK","original-schema-reviewed"));
        DatabaseMigration.execute("baseline",environment);
        DatabaseMigration.execute("migrate",environment);
        DatabaseMigration.execute("validate",environment);
        try (Connection connection = DriverManager.getConnection(url,mysql.getUsername(),mysql.getPassword());
             Statement sql = connection.createStatement()) {
            try (ResultSet result = sql.executeQuery("SELECT balance FROM account WHERE id=1")) {
                result.next(); assertThat(result.getBigDecimal(1)).isEqualByComparingTo("100.00");
            }
            try (ResultSet result = sql.executeQuery("SELECT COUNT(*) FROM transaction_history")) {
                result.next(); assertThat(result.getInt(1)).isEqualTo(1);
            }
            // Preserve case, accent and PAD SPACE semantics of the original collation.
            try (ResultSet result = sql.executeQuery("SELECT response_body FROM idempotency_key WHERE actor_id=0 AND legacy_key='cafe-key'")) {
                assertThat(result.next()).isTrue(); assertThat(result.getString(1)).isEqualTo("legacy-response");
            }
            sql.execute("INSERT INTO idempotency_key(actor_id,idempotency_key,status,operation,request_fingerprint) VALUES"
                    + "(1,'NEW','IN_PROGRESS','DEPOSIT',REPEAT('0',64)),(1,'new','IN_PROGRESS','DEPOSIT',REPEAT('0',64)),"
                    + "(2,'NEW','IN_PROGRESS','DEPOSIT',REPEAT('0',64))");
        }
    }
}
