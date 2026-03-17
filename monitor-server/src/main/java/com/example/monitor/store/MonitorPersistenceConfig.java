package com.example.monitor.store;

public record MonitorPersistenceConfig(
    String databaseUrl,
    String databaseUsername,
    String databasePassword
) {
    public static MonitorPersistenceConfig fromEnvironment() {
        return new MonitorPersistenceConfig(
            firstNonBlank(
                System.getenv("MONITOR_DB_URL"),
                System.getenv("MONITOR_POSTGRES_URL")
            ),
            firstNonBlank(
                System.getenv("MONITOR_DB_USERNAME"),
                System.getenv("MONITOR_PG_USERNAME"),
                System.getenv("MONITOR_POSTGRES_USERNAME")
            ),
            firstNonBlank(
                System.getenv("MONITOR_DB_PASSWORD"),
                System.getenv("MONITOR_PG_PASSWORD"),
                System.getenv("MONITOR_POSTGRES_PASSWORD")
            )
        );
    }

    public boolean databaseEnabled() {
        return !databaseUrl.isBlank();
    }

    private static String blankToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            String normalized = blankToEmpty(value);
            if (!normalized.isBlank()) {
                return normalized;
            }
        }
        return "";
    }
}