package com.example.monitor.protocol;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import io.netty.util.AttributeKey;

public final class MonitorProtocol {
    public static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public static final AttributeKey<String> SERVICE_ID_KEY = AttributeKey.valueOf("serviceId");

    public static final AlertPolicyDefinition CPU_ALERT_POLICY = new AlertPolicyDefinition(
            "CPU_USAGE", "cpuUsage", "CPU 使用率", 90.0d, 95.0d, 300, 300, "%");
    public static final AlertPolicyDefinition MEMORY_ALERT_POLICY = new AlertPolicyDefinition(
            "MEMORY_USAGE", "memoryUsage", "内存使用率", 85.0d, 92.0d, 180, 180, "%");
    public static final AlertPolicyDefinition DISK_ALERT_POLICY = new AlertPolicyDefinition(
            "DISK_USAGE", "diskUsage", "磁盘使用率", 90.0d, 95.0d, 60, 120, "%");
        public static final AlertPolicyDefinition NETWORK_LATENCY_ALERT_POLICY = new AlertPolicyDefinition(
            "NETWORK_LATENCY", "networkLatency", "网络延迟", 200.0d, 400.0d, 60, 120, "ms");

    private MonitorProtocol() {
    }

    public static String ack(String status, String serverId, String code, String message) {
        try {
            Map<String, String> body = new LinkedHashMap<>();
            body.put("type", "ACK");
            body.put("status", status);
            body.put("serverId", serverId == null ? "" : serverId);
            body.put("code", code);
            body.put("message", message == null ? "" : message);
            return MAPPER.writeValueAsString(body);
        } catch (JsonProcessingException ex) {
            return "{\"type\":\"ACK\",\"status\":\"ERROR\",\"code\":\"SERIALIZATION\"}";
        }
    }

    public static String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    public static int intValue(Object value) {
        if (value == null) {
            return 0;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    public static double decimalValue(Object value) {
        if (value == null) {
            return 0.0d;
        }
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return 0.0d;
        }
    }

    public static Double nullableDecimal(Object value) {
        if (value == null || String.valueOf(value).isBlank()) {
            return null;
        }
        return decimalValue(value);
    }

    public static Instant instantValue(Object value, Instant fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            return Instant.parse(String.valueOf(value));
        } catch (Exception ex) {
            return fallback;
        }
    }

    public static double valueOrExisting(Object value, double fallback) {
        if (value == null) {
            return fallback;
        }
        return decimalValue(value);
    }

    public static int intOrExisting(Object value, int fallback) {
        if (value == null) {
            return fallback;
        }
        return intValue(value);
    }

    public static long longOrExisting(Object value, long fallback) {
        if (value == null) {
            return fallback;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    public static String blankToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    public record MetricSample(Instant timestamp, double cpuUsage, double memoryUsage, double jvmHeapUsage,
                               double diskUsage, int networkLatency, double networkThroughputMbps,
                               int threadCount, long gcCount, double gcPauseMs, String status) {
    }

    public record LogEntry(Instant timestamp, String category, String message) {
    }

    public record ServiceSummary(String serverId, String serverType, String serviceName, String host, int port,
                                 String status, double cpuUsage, double memoryUsage, double jvmHeapUsage,
                                 double diskUsage, int networkLatency, double networkThroughputMbps,
                                 int threadCount, long gcCount, double gcPauseMs, Instant lastHeartbeat,
                                 long connectionClosedCount, Instant registerTime, Instant startupTime,
                                 long uptimeSeconds, String log) {
    }

    public record ServiceDetail(ServiceSummary summary, List<MetricSample> metricsHistory,
                                TrendBundle trend24h, TrendBundle trend7d,
                                List<LogEntry> logs, List<AlertRecord> alerts, JvmSnapshot jvm) {
    }

    public record TrendBundle(String label, List<MetricSample> points) {
    }

    public record JvmSnapshot(double heapUsedMb, double heapMaxMb, int threadCount, int daemonThreadCount,
                              long gcCount, double gcPauseMs, Instant lastGcAt, double systemLoad,
                              String stackSummary) {
    }

    public static final class AlertRecord {
        private final String id;
        private final Instant timestamp;
        private final String serverId;
        private final String serviceName;
        private final String severity;
        private final String source;
        private final String message;
        private final String policyKey;
        private final String metric;
        private final Double currentValue;
        private final Double thresholdValue;
        private final int suppressedCount;
        private volatile boolean acknowledged;

        public AlertRecord(String id, Instant timestamp, String serverId, String serviceName, String severity,
                           String source, String message, String policyKey, String metric,
                           Double currentValue, Double thresholdValue, int suppressedCount) {
            this.id = id;
            this.timestamp = timestamp;
            this.serverId = serverId;
            this.serviceName = serviceName;
            this.severity = severity;
            this.source = source;
            this.message = message;
            this.policyKey = policyKey;
            this.metric = metric;
            this.currentValue = currentValue;
            this.thresholdValue = thresholdValue;
            this.suppressedCount = suppressedCount;
        }

        public String id() {
            return id;
        }

        public String getId() {
            return id;
        }

        public Instant timestamp() {
            return timestamp;
        }

        public Instant getTimestamp() {
            return timestamp;
        }

        public String serverId() {
            return serverId;
        }

        public String getServerId() {
            return serverId;
        }

        public String serviceName() {
            return serviceName;
        }

        public String getServiceName() {
            return serviceName;
        }

        public String severity() {
            return severity;
        }

        public String getSeverity() {
            return severity;
        }

        public String source() {
            return source;
        }

        public String getSource() {
            return source;
        }

        public String message() {
            return message;
        }

        public String getMessage() {
            return message;
        }

        public String policyKey() {
            return policyKey;
        }

        public String getPolicyKey() {
            return policyKey;
        }

        public String metric() {
            return metric;
        }

        public String getMetric() {
            return metric;
        }

        public Double currentValue() {
            return currentValue;
        }

        public Double getCurrentValue() {
            return currentValue;
        }

        public Double thresholdValue() {
            return thresholdValue;
        }

        public Double getThresholdValue() {
            return thresholdValue;
        }

        public int suppressedCount() {
            return suppressedCount;
        }

        public int getSuppressedCount() {
            return suppressedCount;
        }

        public boolean acknowledged() {
            return acknowledged;
        }

        public boolean isAcknowledged() {
            return acknowledged;
        }

        public void acknowledge() {
            acknowledged = true;
        }

        public Map<String, Object> toMap() {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("id", id);
            payload.put("timestamp", timestamp);
            payload.put("serverId", serverId);
            payload.put("serviceName", serviceName);
            payload.put("severity", severity);
            payload.put("source", source);
            payload.put("message", message);
            payload.put("policyKey", policyKey);
            payload.put("metric", metric);
            payload.put("currentValue", currentValue);
            payload.put("thresholdValue", thresholdValue);
            payload.put("suppressedCount", suppressedCount);
            payload.put("acknowledged", acknowledged);
            return payload;
        }
    }

    public record OverviewResponse(Instant timestamp, List<ServiceSummary> services,
                                   List<AlertRecord> alerts, NotificationTargets notificationTargets,
                                   List<AlertPolicySummary> alertPolicies) {
    }

    public record AlertPolicyDefinition(String key, String metric, String description,
                                        double warningThreshold, double criticalThreshold,
                                        int warningSuppressSeconds, int criticalSuppressSeconds,
                                        String unit) {
    }

    public record AlertPolicySummary(String key, String metric, String description,
                                     double warningThreshold, double criticalThreshold,
                                     int warningSuppressSeconds, int criticalSuppressSeconds,
                                     long activeServiceCount) {
    }

    public record AlertAckRequest(String alertId) {
    }

    public record AlertAckResult(boolean acknowledged, String status) {
    }

    public record NotificationConfigUpdate(Boolean enabled, String emailWebhookUrl,
                                           String emailRecipients, String wecomWebhookUrl) {
    }

    public record NotificationTargets(boolean enabled, String emailWebhookUrl,
                                      String emailRecipients, String wecomWebhookUrl) {
        public static NotificationTargets fromEnvironment() {
            return new NotificationTargets(
                    true,
                    blankToEmpty(System.getenv("MONITOR_ALERT_EMAIL_WEBHOOK")),
                    blankToEmpty(System.getenv("MONITOR_ALERT_EMAIL_RECIPIENTS")),
                    blankToEmpty(System.getenv("MONITOR_ALERT_WECOM_WEBHOOK"))
            );
        }

        public NotificationTargets sanitized() {
            return new NotificationTargets(enabled, emailWebhookUrl, emailRecipients, wecomWebhookUrl);
        }

        public NotificationTargets merge(NotificationConfigUpdate update) {
            if (update == null) {
                return this;
            }
            return new NotificationTargets(
                    update.enabled() == null ? enabled : update.enabled(),
                    update.emailWebhookUrl() == null ? emailWebhookUrl : blankToEmpty(update.emailWebhookUrl()),
                    update.emailRecipients() == null ? emailRecipients : blankToEmpty(update.emailRecipients()),
                    update.wecomWebhookUrl() == null ? wecomWebhookUrl : blankToEmpty(update.wecomWebhookUrl())
            );
        }
    }

    public record CommandRequest(String serverId, String action, String token, String artifactUrl,
                                 Map<String, String> args) {
    }

    public record CommandMessage(String type, String commandId, String action, String artifactUrl,
                                 Instant requestedAt, Map<String, String> args) {
    }

    public record CommandResult(boolean accepted, String status, String commandId, String serverId) {
    }

    public record SnapshotEvent(String type, Instant timestamp, List<ServiceSummary> services,
                                List<AlertRecord> alerts, int activeAlertCount) {
    }

    public static final class ServiceState {
        private final String serverId;
        private String serverType = "product";
        private String serviceName = "";
        private String host = "127.0.0.1";
        private int port;
        private String status = "OFFLINE";
        private double cpuUsage;
        private double memoryUsage;
        private double jvmHeapUsage;
        private double diskUsage;
        private int networkLatency;
        private double networkThroughputMbps;
        private int threadCount;
        private int daemonThreadCount;
        private long gcCount;
        private double gcPauseMs;
        private double heapUsedMb;
        private double heapMaxMb = 2048.0d;
        private double systemLoad;
        private String jvmStackSummary = "等待 agent 上报 JVM 堆栈摘要";
        private Instant lastGcAt;
        private Instant lastHeartbeat;
        private long connectionClosedCount;
        private Instant registerTime;
        private Instant startupTime;
        private final Deque<MetricSample> metricsHistory = new ArrayDeque<>();
        private final Deque<LogEntry> logs = new ArrayDeque<>();
        private final Deque<AlertRecord> alerts = new ArrayDeque<>();

        public ServiceState(String serverId) {
            this.serverId = serverId;
        }

        public String serverId() {
            return serverId;
        }

        public String serverType() {
            return serverType;
        }

        public void serverType(String serverType) {
            this.serverType = serverType;
        }

        public String serviceName() {
            return serviceName;
        }

        public void serviceName(String serviceName) {
            this.serviceName = serviceName;
        }

        public String host() {
            return host;
        }

        public void host(String host) {
            this.host = host;
        }

        public int port() {
            return port;
        }

        public void port(int port) {
            this.port = port;
        }

        public String status() {
            return status;
        }

        public void status(String status) {
            this.status = status;
        }

        public double cpuUsage() {
            return cpuUsage;
        }

        public void cpuUsage(double cpuUsage) {
            this.cpuUsage = cpuUsage;
        }

        public double memoryUsage() {
            return memoryUsage;
        }

        public void memoryUsage(double memoryUsage) {
            this.memoryUsage = memoryUsage;
        }

        public double jvmHeapUsage() {
            return jvmHeapUsage;
        }

        public void jvmHeapUsage(double jvmHeapUsage) {
            this.jvmHeapUsage = jvmHeapUsage;
        }

        public double diskUsage() {
            return diskUsage;
        }

        public void diskUsage(double diskUsage) {
            this.diskUsage = diskUsage;
        }

        public int networkLatency() {
            return networkLatency;
        }

        public void networkLatency(int networkLatency) {
            this.networkLatency = networkLatency;
        }

        public double networkThroughputMbps() {
            return networkThroughputMbps;
        }

        public void networkThroughputMbps(double networkThroughputMbps) {
            this.networkThroughputMbps = networkThroughputMbps;
        }

        public int threadCount() {
            return threadCount;
        }

        public void threadCount(int threadCount) {
            this.threadCount = threadCount;
        }

        public int daemonThreadCount() {
            return daemonThreadCount;
        }

        public void daemonThreadCount(int daemonThreadCount) {
            this.daemonThreadCount = daemonThreadCount;
        }

        public long gcCount() {
            return gcCount;
        }

        public void gcCount(long gcCount) {
            this.gcCount = gcCount;
        }

        public double gcPauseMs() {
            return gcPauseMs;
        }

        public void gcPauseMs(double gcPauseMs) {
            this.gcPauseMs = gcPauseMs;
        }

        public double heapUsedMb() {
            return heapUsedMb;
        }

        public void heapUsedMb(double heapUsedMb) {
            this.heapUsedMb = heapUsedMb;
        }

        public double heapMaxMb() {
            return heapMaxMb;
        }

        public void heapMaxMb(double heapMaxMb) {
            this.heapMaxMb = heapMaxMb;
        }

        public double systemLoad() {
            return systemLoad;
        }

        public void systemLoad(double systemLoad) {
            this.systemLoad = systemLoad;
        }

        public String jvmStackSummary() {
            return jvmStackSummary;
        }

        public void jvmStackSummary(String jvmStackSummary) {
            this.jvmStackSummary = jvmStackSummary;
        }

        public Instant lastGcAt() {
            return lastGcAt;
        }

        public void lastGcAt(Instant lastGcAt) {
            this.lastGcAt = lastGcAt;
        }

        public Instant lastHeartbeat() {
            return lastHeartbeat;
        }

        public void lastHeartbeat(Instant lastHeartbeat) {
            this.lastHeartbeat = lastHeartbeat;
        }

        public long connectionClosedCount() {
            return connectionClosedCount;
        }

        public void connectionClosedCount(long connectionClosedCount) {
            this.connectionClosedCount = connectionClosedCount;
        }

        public Instant registerTime() {
            return registerTime;
        }

        public void registerTime(Instant registerTime) {
            this.registerTime = registerTime;
        }

        public Instant startupTime() {
            return startupTime;
        }

        public void startupTime(Instant startupTime) {
            this.startupTime = startupTime;
        }

        public Deque<MetricSample> metricsHistory() {
            return metricsHistory;
        }

        public Deque<LogEntry> logs() {
            return logs;
        }

        public Deque<AlertRecord> alerts() {
            return alerts;
        }

        public MetricSample pushMetric(Instant timestamp) {
            MetricSample metricSample = new MetricSample(timestamp, cpuUsage, memoryUsage, jvmHeapUsage, diskUsage,
                    networkLatency, networkThroughputMbps, threadCount, gcCount, gcPauseMs, status);
            metricsHistory.addLast(metricSample);
            return metricSample;
        }

        public LogEntry pushLog(Instant timestamp, String category, String message) {
            LogEntry logEntry = new LogEntry(timestamp, category, message);
            logs.addLast(logEntry);
            return logEntry;
        }

        public ServiceSummary toSummary() {
            return new ServiceSummary(serverId, serverType, serviceName, host, port, status, cpuUsage, memoryUsage, jvmHeapUsage,
                    diskUsage, networkLatency, networkThroughputMbps, threadCount, gcCount, gcPauseMs,
                    lastHeartbeat, connectionClosedCount, registerTime, startupTime, connectedDurationSeconds(), recentLog());
        }

        public ServiceDetail toDetail() {
            List<MetricSample> history = new ArrayList<>(metricsHistory);
            return new ServiceDetail(
                    toSummary(),
                    history,
                    buildTrend(history, Duration.ofHours(24), 24),
                    buildTrend(history, Duration.ofDays(7), 28),
                    new ArrayList<>(logs),
                    new ArrayList<>(alerts),
                    new JvmSnapshot(heapUsedMb, heapMaxMb, threadCount, daemonThreadCount, gcCount, gcPauseMs,
                            lastGcAt, systemLoad, jvmStackSummary)
            );
        }

        public void enrichDerivedMetrics() {
            if (heapMaxMb <= 0.0d) {
                heapMaxMb = 2048.0d;
            }
            if (heapUsedMb <= 0.0d) {
                heapUsedMb = heapMaxMb * (jvmHeapUsage / 100.0d);
            }
            if (threadCount <= 0) {
                threadCount = Math.max(12, 32 + (int) Math.round(cpuUsage));
            }
            if (daemonThreadCount <= 0) {
                daemonThreadCount = Math.max(4, threadCount / 3);
            }
            if (diskUsage <= 0.0d) {
                diskUsage = Math.min(100.0d, Math.max(memoryUsage * 0.72d, jvmHeapUsage * 0.8d));
            }
            if (networkThroughputMbps <= 0.0d) {
                networkThroughputMbps = Math.max(0.8d, cpuUsage * 0.22d + memoryUsage * 0.12d);
            }
            if (gcPauseMs <= 0.0d) {
                gcPauseMs = Math.max(4.0d, jvmHeapUsage * 0.35d);
            }
            if (systemLoad <= 0.0d) {
                systemLoad = Math.min(100.0d, cpuUsage * 0.9d + memoryUsage * 0.3d);
            }
            if (lastGcAt == null) {
                lastGcAt = Instant.now();
            }
        }

        public void acknowledgeAlert(String alertId) {
            for (AlertRecord alert : alerts) {
                if (alert.id().equals(alertId)) {
                    alert.acknowledge();
                }
            }
        }

        private TrendBundle buildTrend(List<MetricSample> history, Duration window, int maxPoints) {
            Instant cutoff = Instant.now().minus(window);
            List<MetricSample> windowed = history.stream()
                    .filter(sample -> !sample.timestamp().isBefore(cutoff))
                    .toList();

            if (windowed.size() <= maxPoints) {
                return new TrendBundle(window.toString(), windowed);
            }

            List<MetricSample> sampled = new ArrayList<>();
            double step = (double) windowed.size() / (double) maxPoints;
            for (int index = 0; index < maxPoints; index++) {
                int actualIndex = Math.min(windowed.size() - 1, (int) Math.floor(index * step));
                sampled.add(windowed.get(actualIndex));
            }
            return new TrendBundle(window.toString(), sampled);
        }

        private long connectedDurationSeconds() {
            if (startupTime == null) {
                return 0L;
            }
            return Math.max(0L, Duration.between(startupTime, Instant.now()).getSeconds());
        }

        private String recentLog() {
            return logs.peekLast() == null ? "" : logs.peekLast().message();
        }
    }
}