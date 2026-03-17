CREATE TABLE IF NOT EXISTS monitor_metric_samples (
    id BIGSERIAL PRIMARY KEY,
    server_id VARCHAR(128) NOT NULL,
    sampled_at TIMESTAMP NOT NULL,
    payload_json TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_monitor_metric_server_time
    ON monitor_metric_samples(server_id, sampled_at);

CREATE TABLE IF NOT EXISTS monitor_log_entries (
    id BIGSERIAL PRIMARY KEY,
    server_id VARCHAR(128) NOT NULL,
    occurred_at TIMESTAMP NOT NULL,
    category VARCHAR(64) NOT NULL,
    message TEXT NOT NULL,
    payload_json TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_monitor_log_server_time
    ON monitor_log_entries(server_id, occurred_at);

CREATE TABLE IF NOT EXISTS monitor_alert_records (
    alert_id VARCHAR(64) PRIMARY KEY,
    server_id VARCHAR(128) NOT NULL,
    occurred_at TIMESTAMP NOT NULL,
    acknowledged BOOLEAN NOT NULL DEFAULT FALSE,
    payload_json TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_monitor_alert_server_time
    ON monitor_alert_records(server_id, occurred_at);

CREATE TABLE IF NOT EXISTS monitor_command_records (
    command_id VARCHAR(64) PRIMARY KEY,
    server_id VARCHAR(128) NOT NULL,
    action VARCHAR(64) NOT NULL,
    requested_at TIMESTAMP NOT NULL,
    status VARCHAR(64) NOT NULL,
    payload_json TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_monitor_command_server_time
    ON monitor_command_records(server_id, requested_at);