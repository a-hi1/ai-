package com.example.monitor.store;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

public final class JdbcHistoryStore implements AutoCloseable {
    public record PersistedMetric(String serverId, String payloadJson) {
    }

    public record PersistedLog(String serverId, String payloadJson) {
    }

    public record PersistedAlert(String serverId, String payloadJson) {
    }

    private final HikariDataSource dataSource;
    private final boolean available;
    private final String productName;

    public JdbcHistoryStore(MonitorPersistenceConfig config) {
        HikariDataSource candidate = null;
        boolean enabled = false;
        String detectedProduct = "";
        if (config != null && config.databaseEnabled()) {
            try {
                HikariConfig hikariConfig = new HikariConfig();
                hikariConfig.setJdbcUrl(config.databaseUrl());
                hikariConfig.setUsername(config.databaseUsername());
                hikariConfig.setPassword(config.databasePassword());
                hikariConfig.setMaximumPoolSize(6);
                hikariConfig.setMinimumIdle(1);
                hikariConfig.setConnectionTimeout(5000);
                hikariConfig.setValidationTimeout(3000);
                hikariConfig.setInitializationFailTimeout(5000);
                hikariConfig.setPoolName("monitor-jdbc");
                candidate = new HikariDataSource(hikariConfig);
                try (Connection connection = candidate.getConnection()) {
                    detectedProduct = connection.getMetaData().getDatabaseProductName();
                }
                initSchema(candidate);
                enabled = true;
            } catch (Exception ignored) {
                if (candidate != null) {
                    candidate.close();
                }
                candidate = null;
            }
        }
        this.dataSource = candidate;
        this.available = enabled;
        this.productName = detectedProduct == null ? "" : detectedProduct;
    }

    public boolean isAvailable() {
        return available;
    }

    public void saveMetric(String serverId, Instant sampledAt, String payloadJson) {
        executeUpdate(
                "INSERT INTO monitor_metric_samples(server_id, sampled_at, payload_json) VALUES (?, ?, ?)",
                statement -> {
                    statement.setString(1, serverId);
                    statement.setTimestamp(2, Timestamp.from(sampledAt));
                    statement.setString(3, payloadJson);
                });
    }

    public void saveLog(String serverId, Instant occurredAt, String category, String message, String payloadJson) {
        executeUpdate(
                "INSERT INTO monitor_log_entries(server_id, occurred_at, category, message, payload_json) VALUES (?, ?, ?, ?, ?)",
                statement -> {
                    statement.setString(1, serverId);
                    statement.setTimestamp(2, Timestamp.from(occurredAt));
                    statement.setString(3, category);
                    statement.setString(4, message);
                    statement.setString(5, payloadJson);
                });
    }

    public void saveAlert(String alertId, String serverId, Instant occurredAt, boolean acknowledged, String payloadJson) {
        executeUpdate(
                alertUpsertSql(),
                statement -> {
                    statement.setString(1, alertId);
                    statement.setString(2, serverId);
                    statement.setTimestamp(3, Timestamp.from(occurredAt));
                    statement.setBoolean(4, acknowledged);
                    statement.setString(5, payloadJson);
                    if (!isPostgreSql()) {
                        statement.setBoolean(6, acknowledged);
                        statement.setString(7, payloadJson);
                    }
                });
    }

    public void acknowledgeAlert(String alertId) {
        executeUpdate("UPDATE monitor_alert_records SET acknowledged = TRUE WHERE alert_id = ?", statement -> statement.setString(1, alertId));
    }

    public void saveCommand(String commandId, String serverId, String action, Instant requestedAt, String status, String payloadJson) {
        executeUpdate(
                commandUpsertSql(),
                statement -> {
                    statement.setString(1, commandId);
                    statement.setString(2, serverId);
                    statement.setString(3, action);
                    statement.setTimestamp(4, Timestamp.from(requestedAt));
                    statement.setString(5, status);
                    statement.setString(6, payloadJson);
                    if (!isPostgreSql()) {
                        statement.setString(7, action);
                        statement.setTimestamp(8, Timestamp.from(requestedAt));
                        statement.setString(9, status);
                        statement.setString(10, payloadJson);
                    }
                });
    }

    public List<PersistedMetric> loadRecentMetrics(Instant since) {
        return query(
                "SELECT server_id, payload_json FROM monitor_metric_samples WHERE sampled_at >= ? ORDER BY sampled_at ASC",
                statement -> statement.setTimestamp(1, Timestamp.from(since)),
                resultSet -> new PersistedMetric(resultSet.getString(1), resultSet.getString(2)));
    }

    public List<PersistedLog> loadRecentLogs(Instant since) {
        return query(
                "SELECT server_id, payload_json FROM monitor_log_entries WHERE occurred_at >= ? ORDER BY occurred_at ASC",
                statement -> statement.setTimestamp(1, Timestamp.from(since)),
                resultSet -> new PersistedLog(resultSet.getString(1), resultSet.getString(2)));
    }

    public List<PersistedAlert> loadRecentAlerts(Instant since) {
        return query(
                "SELECT server_id, payload_json FROM monitor_alert_records WHERE occurred_at >= ? ORDER BY occurred_at ASC",
                statement -> statement.setTimestamp(1, Timestamp.from(since)),
                resultSet -> new PersistedAlert(resultSet.getString(1), resultSet.getString(2)));
    }

    public void deleteHistoryBefore(Instant cutoff) {
        if (cutoff == null) {
            return;
        }
        executeUpdate("DELETE FROM monitor_metric_samples WHERE sampled_at < ?", statement -> statement.setTimestamp(1, Timestamp.from(cutoff)));
        executeUpdate("DELETE FROM monitor_log_entries WHERE occurred_at < ?", statement -> statement.setTimestamp(1, Timestamp.from(cutoff)));
        executeUpdate("DELETE FROM monitor_alert_records WHERE occurred_at < ?", statement -> statement.setTimestamp(1, Timestamp.from(cutoff)));
        executeUpdate("DELETE FROM monitor_command_records WHERE requested_at < ?", statement -> statement.setTimestamp(1, Timestamp.from(cutoff)));
    }

    public void deleteHistoryByServerId(String serverId) {
        if (serverId == null || serverId.isBlank()) {
            return;
        }
        executeUpdate("DELETE FROM monitor_metric_samples WHERE server_id = ?", statement -> statement.setString(1, serverId));
        executeUpdate("DELETE FROM monitor_log_entries WHERE server_id = ?", statement -> statement.setString(1, serverId));
        executeUpdate("DELETE FROM monitor_alert_records WHERE server_id = ?", statement -> statement.setString(1, serverId));
        executeUpdate("DELETE FROM monitor_command_records WHERE server_id = ?", statement -> statement.setString(1, serverId));
    }

    private void initSchema(HikariDataSource candidate) throws Exception {
        try (Connection connection = candidate.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS monitor_metric_samples ("
                    + "id BIGSERIAL PRIMARY KEY,"
                    + "server_id VARCHAR(128) NOT NULL,"
                    + "sampled_at TIMESTAMP NOT NULL,"
                    + "payload_json TEXT NOT NULL)");
            ensureIndex(statement, "CREATE INDEX IF NOT EXISTS idx_monitor_metric_server_time ON monitor_metric_samples(server_id, sampled_at)");

            statement.execute("CREATE TABLE IF NOT EXISTS monitor_log_entries ("
                    + "id BIGSERIAL PRIMARY KEY,"
                    + "server_id VARCHAR(128) NOT NULL,"
                    + "occurred_at TIMESTAMP NOT NULL,"
                    + "category VARCHAR(64) NOT NULL,"
                    + "message TEXT NOT NULL,"
                    + "payload_json TEXT NOT NULL)");
            ensureIndex(statement, "CREATE INDEX IF NOT EXISTS idx_monitor_log_server_time ON monitor_log_entries(server_id, occurred_at)");

            statement.execute("CREATE TABLE IF NOT EXISTS monitor_alert_records ("
                    + "alert_id VARCHAR(64) PRIMARY KEY,"
                    + "server_id VARCHAR(128) NOT NULL,"
                    + "occurred_at TIMESTAMP NOT NULL,"
                    + "acknowledged BOOLEAN NOT NULL DEFAULT FALSE,"
                    + "payload_json TEXT NOT NULL)");
            ensureIndex(statement, "CREATE INDEX IF NOT EXISTS idx_monitor_alert_server_time ON monitor_alert_records(server_id, occurred_at)");

            statement.execute("CREATE TABLE IF NOT EXISTS monitor_command_records ("
                    + "command_id VARCHAR(64) PRIMARY KEY,"
                    + "server_id VARCHAR(128) NOT NULL,"
                    + "action VARCHAR(64) NOT NULL,"
                    + "requested_at TIMESTAMP NOT NULL,"
                    + "status VARCHAR(64) NOT NULL,"
                    + "payload_json TEXT NOT NULL)");
            ensureIndex(statement, "CREATE INDEX IF NOT EXISTS idx_monitor_command_server_time ON monitor_command_records(server_id, requested_at)");
        }
    }

    private String alertUpsertSql() {
        if (isPostgreSql()) {
            return "INSERT INTO monitor_alert_records(alert_id, server_id, occurred_at, acknowledged, payload_json) VALUES (?, ?, ?, ?, ?) "
                    + "ON CONFLICT (alert_id) DO UPDATE SET acknowledged = EXCLUDED.acknowledged, payload_json = EXCLUDED.payload_json";
        }
        return "INSERT INTO monitor_alert_records(alert_id, server_id, occurred_at, acknowledged, payload_json) VALUES (?, ?, ?, ?, ?) "
                + "ON DUPLICATE KEY UPDATE acknowledged = ?, payload_json = ?";
    }

    private String commandUpsertSql() {
        if (isPostgreSql()) {
            return "INSERT INTO monitor_command_records(command_id, server_id, action, requested_at, status, payload_json) VALUES (?, ?, ?, ?, ?, ?) "
                    + "ON CONFLICT (command_id) DO UPDATE SET action = EXCLUDED.action, requested_at = EXCLUDED.requested_at, status = EXCLUDED.status, payload_json = EXCLUDED.payload_json";
        }
        return "INSERT INTO monitor_command_records(command_id, server_id, action, requested_at, status, payload_json) VALUES (?, ?, ?, ?, ?, ?) "
                + "ON DUPLICATE KEY UPDATE action = ?, requested_at = ?, status = ?, payload_json = ?";
    }

    private boolean isPostgreSql() {
        return productName.toLowerCase(Locale.ROOT).contains("postgres");
    }

    private void ensureIndex(Statement statement, String sql) {
        try {
            statement.execute(sql);
        } catch (Exception ignored) {
        }
    }

    private void executeUpdate(String sql, SqlConsumer<PreparedStatement> consumer) {
        if (!available || dataSource == null) {
            return;
        }
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            consumer.accept(statement);
            statement.executeUpdate();
        } catch (Exception ignored) {
        }
    }

    private <T> List<T> query(String sql, SqlConsumer<PreparedStatement> consumer, SqlMapper<T> mapper) {
        if (!available || dataSource == null) {
            return List.of();
        }
        List<T> results = new ArrayList<>();
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            consumer.accept(statement);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    results.add(mapper.map(resultSet));
                }
            }
        } catch (Exception ignored) {
            return List.of();
        }
        return results;
    }

    @Override
    public void close() {
        if (dataSource != null) {
            dataSource.close();
        }
    }

    @FunctionalInterface
    private interface SqlConsumer<T> {
        void accept(T value) throws Exception;
    }

    @FunctionalInterface
    private interface SqlMapper<T> {
        T map(ResultSet resultSet) throws Exception;
    }
}