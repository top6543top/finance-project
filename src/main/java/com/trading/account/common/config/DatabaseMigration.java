package com.trading.account.common.config;

import org.flywaydb.core.Flyway;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/** Separate release command: no web server, scheduler, or runtime DML credentials. */
public final class DatabaseMigration {
    private DatabaseMigration() {}

    public static void main(String[] args) {
        try {
            if (args.length != 1) throw new IllegalArgumentException("Choose migrate, validate or baseline");
            execute(args[0], System.getenv());
            System.out.println("Schema command completed: " + args[0]);
        } catch (Exception error) {
            // JDBC URLs and nested exceptions may contain credentials. Flyway retains its own diagnostics.
            System.err.println("Schema command failed: " + error.getClass().getSimpleName());
            System.exit(1);
        }
    }

    public static void execute(String action, Map<String, String> environment) {
        if (!Arrays.asList("migrate", "validate", "baseline").contains(action)) {
            throw new IllegalArgumentException("Choose migrate, validate or baseline; destructive clean is not supported");
        }
        if (action.equals("baseline") && !"original-schema-reviewed".equals(environment.get("FLYWAY_BASELINE_ACK"))) {
            throw new IllegalArgumentException("Existing schema and backup must be reviewed before baseline");
        }
        String url = required(environment, "FLYWAY_URL");
        if (!url.startsWith("jdbc:mysql://")) throw new IllegalArgumentException("Only MySQL migration URLs are supported");
        if (!"false".equalsIgnoreCase(environment.get("FLYWAY_REQUIRE_TLS"))) {
            validateTlsUrl(url);
        }
        Flyway flyway = Flyway.configure().dataSource(url, required(environment, "FLYWAY_USER"),
                        required(environment, "FLYWAY_PASSWORD"))
                .cleanDisabled(true).baselineOnMigrate(false).baselineVersion("1")
                .locations("classpath:db/migration").load();
        switch (action) {
            case "migrate" -> flyway.migrate();
            case "validate" -> flyway.validate();
            case "baseline" -> flyway.baseline();
            default -> throw new IllegalStateException("Unreachable action");
        }
    }

    private static void validateTlsUrl(String url) {
        URI uri = URI.create(url.substring("jdbc:".length()));
        if (uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null
                || uri.getPath() == null || !uri.getPath().matches("/[A-Za-z0-9_]+")) {
            throw new IllegalArgumentException("Use a single-host MySQL URL without embedded credentials");
        }
        // Keep the supported production URL contract small: Connector/J useConfigs,
        // per-host settings and propertiesTransform must not override TLS after validation.
        // Connector/J recognizes these names case-sensitively. Validate the same spelling the driver receives.
        Set<String> allowed = Set.of("sslMode", "serverTimezone", "connectionTimeZone", "characterEncoding",
                "connectTimeout", "socketTimeout", "enabledTLSProtocols", "forceConnectionTimeZoneToSession");
        Map<String, String> properties = new HashMap<>();
        String query = uri.getRawQuery() == null ? "" : uri.getRawQuery();
        for (String entry : query.split("&")) {
            if (entry.isEmpty()) continue;
            String[] pair = entry.split("=", 2);
            String key = URLDecoder.decode(pair[0], StandardCharsets.UTF_8);
            String value = pair.length == 2 ? URLDecoder.decode(pair[1], StandardCharsets.UTF_8) : "";
            if (!allowed.contains(key)) throw new IllegalArgumentException("Unsupported MySQL connection option");
            if (properties.putIfAbsent(key, value) != null) {
                throw new IllegalArgumentException("Specify exactly one value per MySQL option, including sslMode=VERIFY_IDENTITY");
            }
        }
        if (!"VERIFY_IDENTITY".equals(properties.get("sslMode"))) {
            throw new IllegalArgumentException("FLYWAY_URL must use exactly one sslMode=VERIFY_IDENTITY setting");
        }
    }

    private static String required(Map<String, String> environment, String name) {
        String value = environment.get(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
        return value;
    }
}
