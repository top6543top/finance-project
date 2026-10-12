package com.trading.account;

import com.trading.account.common.config.DatabaseMigration;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DatabaseMigrationCommandTest {
    @Test
    void refusesUnverifiedTlsBeforeOpeningConnection() {
        assertThatThrownBy(() -> DatabaseMigration.execute("migrate", Map.of(
                "FLYWAY_URL", "jdbc:mysql://unreachable.invalid/account", "FLYWAY_USER", "migration",
                "FLYWAY_PASSWORD", "test-only")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("VERIFY_IDENTITY");
    }
    @Test
    void neverOffersCleanOrAutomaticBaseline() {
        assertThatThrownBy(() -> DatabaseMigration.execute("clean", Map.of()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("migrate");
        assertThatThrownBy(() -> DatabaseMigration.execute("baseline", Map.of()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("reviewed");
    }
    @Test
    void rejectsConflictingTlsParametersBeforeOpeningConnection() {
        assertThatThrownBy(() -> DatabaseMigration.execute("migrate", Map.of(
                "FLYWAY_URL", "jdbc:mysql://unreachable.invalid/account?sslMode=VERIFY_IDENTITY&sslMode=DISABLED")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("exactly one");
    }
    @Test
    void rejectsEncodedDuplicateTlsOptionsAndDriverConfigurationOverrides() {
        for (String query : new String[]{"sslMode=VERIFY_IDENTITY&%73slMode=DISABLED",
                "sslMode=VERIFY_IDENTITY&useConfigs=unsafe", "sslMode=VERIFY_IDENTITY&propertiesTransform=unsafe"}) {
            assertThatThrownBy(() -> DatabaseMigration.execute("migrate", Map.of(
                    "FLYWAY_URL", "jdbc:mysql://unreachable.invalid/account?" + query)))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
    @Test
    void rejectsTlsPropertyNamesThatTheDriverDoesNotRecognize() {
        for (String option : new String[]{"sslmode", "SSLMODE"}) {
            assertThatThrownBy(() -> DatabaseMigration.execute("migrate", Map.of(
                    "FLYWAY_URL", "jdbc:mysql://unreachable.invalid/account?"+option+"=VERIFY_IDENTITY")))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Unsupported");
        }
    }
    @Test
    void rejectsPerHostOverrideSyntaxAndEmbeddedCredentials() {
        for (String authority : new String[]{"address=(host=unreachable.invalid)(sslMode=DISABLED)",
                "user:password@unreachable.invalid", "host1.invalid,host2.invalid"}) {
            assertThatThrownBy(() -> DatabaseMigration.execute("migrate", Map.of(
                    "FLYWAY_URL", "jdbc:mysql://"+authority+"/account?sslMode=VERIFY_IDENTITY")))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("single-host");
        }
    }
}
