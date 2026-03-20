package com.example.monitor.registry;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import java.lang.management.ManagementFactory;
import java.io.File;

import com.sun.management.OperatingSystemMXBean;

import com.example.monitor.protocol.MonitorProtocol;
import com.example.monitor.protocol.MonitorProtocol.AlertAckResult;
import com.example.monitor.protocol.MonitorProtocol.AlertPolicyDefinition;
import com.example.monitor.protocol.MonitorProtocol.AlertPolicySummary;
import com.example.monitor.protocol.MonitorProtocol.AlertRecord;
import com.example.monitor.protocol.MonitorProtocol.CommandMessage;
import com.example.monitor.protocol.MonitorProtocol.CommandRequest;
import com.example.monitor.protocol.MonitorProtocol.CommandResult;
import com.example.monitor.protocol.MonitorProtocol.LogEntry;
import com.example.monitor.protocol.MonitorProtocol.MetricSample;
import com.example.monitor.protocol.MonitorProtocol.NotificationConfigUpdate;
import com.example.monitor.protocol.MonitorProtocol.NotificationTargets;
import com.example.monitor.protocol.MonitorProtocol.OverviewResponse;
import com.example.monitor.protocol.MonitorProtocol.ServiceDetail;
import com.example.monitor.protocol.MonitorProtocol.ServiceState;
import com.example.monitor.protocol.MonitorProtocol.ServiceSummary;
import com.example.monitor.protocol.MonitorProtocol.SnapshotEvent;
import com.example.monitor.store.JdbcHistoryStore;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.netty.channel.Channel;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.util.concurrent.GlobalEventExecutor;

public final class MonitorRegistry {
    public record ServicePurgeResult(boolean accepted, String status, String serverId) {
    }

    public record ServicePortAddResult(boolean accepted, String status, String serverId, int port) {
    }

    public record AccountPresenceResult(boolean accepted, String status, String serverId, boolean online, int port) {
    }

    private final ObjectMapper mapper;
    private final JdbcHistoryStore jdbcHistoryStore;
    private final Map<String, ServiceState> services = new ConcurrentHashMap<>();
    private final Map<String, Channel> agentChannels = new ConcurrentHashMap<>();
    private final ChannelGroup webSockets = new DefaultChannelGroup(GlobalEventExecutor.INSTANCE);
    private final Deque<AlertRecord> alerts = new ArrayDeque<>();
    private final Map<String, Instant> alertSuppression = new ConcurrentHashMap<>();
    private final Map<String, Integer> suppressedAlertCounts = new ConcurrentHashMap<>();
    private final Map<String, String> activePolicySeverity = new ConcurrentHashMap<>();
    private final Map<String, Boolean> loadedAlertIds = new ConcurrentHashMap<>();
    private final List<AlertPolicyDefinition> alertPolicies = List.of(
            MonitorProtocol.CPU_ALERT_POLICY,
            MonitorProtocol.MEMORY_ALERT_POLICY,
            MonitorProtocol.DISK_ALERT_POLICY,
            MonitorProtocol.NETWORK_LATENCY_ALERT_POLICY);
    private volatile String controlToken = "monitor-dev-token";
    private volatile NotificationTargets notificationTargets = NotificationTargets.fromEnvironment();

    public MonitorRegistry(JdbcHistoryStore jdbcHistoryStore) {
        this.mapper = MonitorProtocol.MAPPER;
        this.jdbcHistoryStore = jdbcHistoryStore;
        restorePersistedState();
    }

    public void setControlToken(String controlToken) {
        this.controlToken = controlToken;
    }

    public String handleRegister(Channel channel, Map<String, Object> payload) {
        String serverId = requireServerId(payload);
        String serverType = MonitorProtocol.stringValue(payload.getOrDefault("serverType", "product"));
        String serviceName = MonitorProtocol.stringValue(payload.getOrDefault("serviceName", serverId));
        String host = MonitorProtocol.stringValue(firstPayloadValue(payload, "host", "127.0.0.1"));
        int port = MonitorProtocol.intValue(firstPayloadValue(payload, "port", 0));
        Instant now = Instant.now();

        ServiceState state = services.compute(serverId, (key, existing) -> {
            ServiceState current = existing == null ? new ServiceState(serverId) : existing;
            current.serverType(serverType);
            current.serviceName(serviceName);
            current.host(host);
            current.port(port);
            current.status("ONLINE");
            current.registerTime(current.registerTime() == null ? now : current.registerTime());
            current.lastHeartbeat(now);
            current.startupTime(MonitorProtocol.instantValue(firstPayloadValue(payload, "startupTime", payload.get("startupTime")), now));
            appendLog(current, now, "REGISTER", "Service registered");
            return current;
        });
        channel.attr(MonitorProtocol.SERVICE_ID_KEY).set(serverId);
        agentChannels.put(serverId, channel);
        broadcastSnapshot();
        return MonitorProtocol.ack("OK", serverId, "REGISTERED", state.serviceName());
    }

    public String handleHeartbeat(Channel channel, Map<String, Object> payload) {
        String serverId = requireServerId(payload);
        ServiceState state = services.get(serverId);
        if (state == null) {
            return MonitorProtocol.ack("ERROR", serverId, "NOT_FOUND", "Service not registered");
        }
        channel.attr(MonitorProtocol.SERVICE_ID_KEY).set(serverId);
        agentChannels.put(serverId, channel);
        state.status("ONLINE");
        state.lastHeartbeat(Instant.now());
        if (payload.containsKey("networkLatency") || hasHostInfoKey(payload, "networkLatency")) {
            state.networkLatency(MonitorProtocol.intValue(firstPayloadValue(payload, "networkLatency", null)));
        }
        if (payload.containsKey("networkThroughputMbps") || hasHostInfoKey(payload, "networkThroughputMbps")) {
            state.networkThroughputMbps(MonitorProtocol.decimalValue(firstPayloadValue(payload, "networkThroughputMbps", null)));
        }
        broadcastSnapshot();
        return MonitorProtocol.ack("OK", serverId, "HEARTBEAT", "Heartbeat accepted");
    }

    public String handleMetrics(Channel channel, Map<String, Object> payload) {
        String serverId = requireServerId(payload);
        ServiceState state = services.get(serverId);
        if (state == null) {
            return MonitorProtocol.ack("ERROR", serverId, "NOT_FOUND", "Service not registered");
        }
        channel.attr(MonitorProtocol.SERVICE_ID_KEY).set(serverId);
        agentChannels.put(serverId, channel);
        Instant now = Instant.now();
        state.status("ONLINE");
        state.lastHeartbeat(now);
        state.cpuUsage(MonitorProtocol.decimalValue(firstPayloadValue(payload, "cpuUsage", null)));
        state.memoryUsage(MonitorProtocol.decimalValue(firstPayloadValue(payload, "memoryUsage", null)));
        state.jvmHeapUsage(MonitorProtocol.decimalValue(firstPayloadValue(payload, "jvmHeapUsage", null)));
        state.diskUsage(MonitorProtocol.valueOrExisting(firstPayloadValue(payload, "diskUsage", null), state.diskUsage()));
        state.networkLatency(MonitorProtocol.intValue(firstPayloadValue(payload, "networkLatency", null)));
        state.networkThroughputMbps(MonitorProtocol.valueOrExisting(firstPayloadValue(payload, "networkThroughputMbps", null), state.networkThroughputMbps()));
        state.threadCount(MonitorProtocol.intOrExisting(firstPayloadValue(payload, "threadCount", null), state.threadCount()));
        state.daemonThreadCount(MonitorProtocol.intOrExisting(firstPayloadValue(payload, "daemonThreadCount", null), state.daemonThreadCount()));
        state.gcCount(MonitorProtocol.longOrExisting(firstPayloadValue(payload, "gcCount", null), state.gcCount()));
        state.gcPauseMs(MonitorProtocol.valueOrExisting(firstPayloadValue(payload, "gcPauseMs", null), state.gcPauseMs()));
        state.heapUsedMb(MonitorProtocol.valueOrExisting(firstPayloadValue(payload, "heapUsedMb", null), state.heapUsedMb()));
        state.heapMaxMb(MonitorProtocol.valueOrExisting(firstPayloadValue(payload, "heapMaxMb", null), state.heapMaxMb() == 0.0d ? 2048.0d : state.heapMaxMb()));
        state.systemLoad(MonitorProtocol.valueOrExisting(firstPayloadValue(payload, "systemLoad", null), state.systemLoad()));
        state.jvmStackSummary(MonitorProtocol.stringValue(firstPayloadValue(payload, "jvmStackSummary", state.jvmStackSummary())));
        state.lastGcAt(MonitorProtocol.instantValue(firstPayloadValue(payload, "lastGcAt", state.lastGcAt()), state.lastGcAt()));
        state.enrichDerivedMetrics();
        MetricSample metricSample = state.pushMetric(now);
        persistMetric(state.serverId(), metricSample);
        evaluateThresholdAlerts(state, now);
        trimHistory(state, now);
        broadcastSnapshot();
        return MonitorProtocol.ack("OK", serverId, "METRICS", "Metrics accepted");
    }

    public String handleAlert(Channel channel, Map<String, Object> payload) {
        String serverId = requireServerId(payload);
        ServiceState state = services.computeIfAbsent(serverId, ServiceState::new);
        channel.attr(MonitorProtocol.SERVICE_ID_KEY).set(serverId);
        agentChannels.put(serverId, channel);
        Instant now = Instant.now();
        state.lastHeartbeat(now);
        state.status(MonitorProtocol.stringValue(payload.getOrDefault("status", state.status() == null ? "ONLINE" : state.status())));
        String severity = MonitorProtocol.stringValue(payload.getOrDefault("severity", "warning"));
        String message = MonitorProtocol.stringValue(payload.getOrDefault("message", "Alert received"));
        appendLog(state, now, "ALERT", message);
        state.enrichDerivedMetrics();
        pushAlert(state, now, severity, "agent", message);
        trimHistory(state, now);
        broadcastSnapshot();
        return MonitorProtocol.ack("OK", serverId, "ALERT", "Alert accepted");
    }

    public String handleOffline(Channel channel, Map<String, Object> payload) {
        String serverId = requireServerId(payload);
        ServiceState state = services.get(serverId);
        if (state == null) {
            return MonitorProtocol.ack("ERROR", serverId, "NOT_FOUND", "Service not registered");
        }
        state.status("OFFLINE");
        state.lastHeartbeat(Instant.now());
        appendLog(state, state.lastHeartbeat(), "OFFLINE", MonitorProtocol.stringValue(payload.getOrDefault("message", "Agent offline")));
        pushAlert(state, state.lastHeartbeat(), "critical", "agent", MonitorProtocol.stringValue(payload.getOrDefault("message", "Agent offline")));
        agentChannels.remove(serverId);
        if (channel != null) {
            channel.attr(MonitorProtocol.SERVICE_ID_KEY).set(serverId);
        }
        broadcastSnapshot();
        return MonitorProtocol.ack("OK", serverId, "OFFLINE", "Offline accepted");
    }

    public String handleCommandAck(Channel channel, Map<String, Object> payload) {
        String serverId = requireServerId(payload);
        ServiceState state = services.computeIfAbsent(serverId, ServiceState::new);
        Instant now = Instant.now();
        state.lastHeartbeat(now);
        String action = MonitorProtocol.stringValue(payload.getOrDefault("action", "UNKNOWN"));
        String result = MonitorProtocol.stringValue(payload.getOrDefault("result", "UNKNOWN"));
        String message = MonitorProtocol.stringValue(payload.getOrDefault("message", ""));
        appendLog(state, now, "COMMAND_ACK", action + " -> " + result + (message.isBlank() ? "" : (" (" + message + ")")));
        String commandId = MonitorProtocol.stringValue(payload.getOrDefault("commandId", ""));
        persistCommand(commandId, serverId, action, now, result, payload);
        if (!"SUCCESS".equalsIgnoreCase(result) && !"OK".equalsIgnoreCase(result)) {
            pushAlert(state, now, "warning", "command", action + " 执行结果: " + result + (message.isBlank() ? "" : (" - " + message)));
        }
        broadcastSnapshot();
        return MonitorProtocol.ack("OK", serverId, "COMMAND_ACK", "Ack accepted");
    }

    public void markTimeouts(long timeoutMs) {
        Instant now = Instant.now();
        boolean changed = false;
        for (ServiceState state : services.values()) {
            if (state.lastHeartbeat() != null && Duration.between(state.lastHeartbeat(), now).toMillis() > timeoutMs && !"OFFLINE".equals(state.status())) {
                state.status("OFFLINE");
                appendLog(state, now, "TIMEOUT", "Heartbeat timeout exceeded");
                pushAlert(state, now, "critical", "monitor", "心跳超时，节点已被判定为离线");
                changed = true;
            }
            trimHistory(state, now);
        }
        if (changed) {
            broadcastSnapshot();
        }
    }

    public void markChannelOffline(Channel channel) {
        if (channel == null) {
            return;
        }
        String serverId = channel.attr(MonitorProtocol.SERVICE_ID_KEY).get();
        if (serverId == null || serverId.isBlank()) {
            return;
        }
        ServiceState state = services.get(serverId);
        if (state != null) {
            state.status("OFFLINE");
            state.lastHeartbeat(Instant.now());
            state.connectionClosedCount(state.connectionClosedCount() + 1);
            appendLog(state, state.lastHeartbeat(), "DISCONNECT", "TCP channel closed");
            pushAlert(state, state.lastHeartbeat(), "critical", "transport", "TCP 通道断开");
        }
        agentChannels.remove(serverId);
        broadcastSnapshot();
    }

    public void recordTransportError(Channel channel, String message) {
        if (channel == null) {
            return;
        }
        String serverId = channel.attr(MonitorProtocol.SERVICE_ID_KEY).get();
        if (serverId == null || serverId.isBlank()) {
            return;
        }
        ServiceState state = services.computeIfAbsent(serverId, ServiceState::new);
        Instant now = Instant.now();
        appendLog(state, now, "TRANSPORT_ERROR", message == null ? "Unknown transport error" : message);
        pushAlert(state, now, "warning", "transport", message == null ? "Unknown transport error" : message);
        broadcastSnapshot();
    }

    public List<ServiceSummary> listServices() {
        return services.values().stream()
                .sorted(Comparator.comparing(state -> Objects.toString(state.serviceName(), state.serverId())))
                .map(ServiceState::toSummary)
                .toList();
    }

    public OverviewResponse overview() {
        return new OverviewResponse(Instant.now(), listServices(), listAlerts(), notificationTargets(), listAlertPolicies());
    }

    public List<AlertRecord> listAlerts() {
        return alerts.stream()
                .sorted(Comparator.comparing(AlertRecord::timestamp).reversed())
                .toList();
    }

    public NotificationTargets notificationTargets() {
        return notificationTargets.sanitized();
    }

    public List<AlertPolicySummary> listAlertPolicies() {
        return alertPolicies.stream()
                .map(policy -> new AlertPolicySummary(
                        policy.key(),
                        policy.metric(),
                        policy.description(),
                        policy.warningThreshold(),
                        policy.criticalThreshold(),
                        policy.warningSuppressSeconds(),
                        policy.criticalSuppressSeconds(),
                        services.values().stream()
                                .filter(state -> currentMetricValue(state, policy) >= policy.warningThreshold())
                                .count()))
                .toList();
    }

    public NotificationTargets updateNotificationTargets(NotificationConfigUpdate update) {
        notificationTargets = notificationTargets.merge(update);
        return notificationTargets();
    }

    public AlertAckResult acknowledgeAlert(String alertId) {
        if (alertId == null || alertId.isBlank()) {
            return new AlertAckResult(false, "MISSING_ALERT_ID");
        }

        boolean found = false;
        for (AlertRecord alert : alerts) {
            if (alert.id().equals(alertId)) {
                alert.acknowledge();
                found = true;
            }
        }
        for (ServiceState state : services.values()) {
            state.acknowledgeAlert(alertId);
        }
        if (found) {
            jdbcHistoryStore.acknowledgeAlert(alertId);
            broadcastSnapshot();
            return new AlertAckResult(true, "ACKNOWLEDGED");
        }
        return new AlertAckResult(false, "NOT_FOUND");
    }

    public ServiceDetail getServiceDetail(String serverId) {
        ServiceState state = services.get(serverId);
        return state == null ? null : state.toDetail();
    }

    public CommandResult dispatchCommand(CommandRequest request) {
        if (request == null) {
            return new CommandResult(false, "INVALID_REQUEST", null, null);
        }
        if (!Objects.equals(controlToken, request.token())) {
            return new CommandResult(false, "UNAUTHORIZED", null, request.serverId());
        }

        if ("FORCE_OFFLINE".equalsIgnoreCase(request.action())) {
            ServiceState state = services.get(request.serverId());
            if (state == null) {
                return new CommandResult(false, "NOT_FOUND", null, request.serverId());
            }
            Channel channel = agentChannels.remove(request.serverId());
            Instant now = Instant.now();
            state.status("OFFLINE");
            state.lastHeartbeat(now);
            appendLog(state, now, "FORCE_OFFLINE", "节点被控制台强制下线");
            pushAlert(state, now, "warning", "console", "节点已被控制台强制下线");
            if (channel != null) {
                channel.close();
            }
            broadcastSnapshot();
            return new CommandResult(true, "OFFLINE_APPLIED", UUID.randomUUID().toString(), request.serverId());
        }

        String commandId = UUID.randomUUID().toString();
        Instant requestedAt = Instant.now();
        CommandMessage payload = new CommandMessage(
                "COMMAND",
                commandId,
                request.action(),
                request.artifactUrl(),
                requestedAt,
                request.args() == null ? Map.of() : request.args());

        Channel channel = agentChannels.get(request.serverId());
        if (channel == null || !channel.isActive()) {
            if ("RESTART".equalsIgnoreCase(request.action())) {
                return launchOfflineRestart(request, commandId, requestedAt, payload);
            }
            return new CommandResult(false, "AGENT_NOT_CONNECTED", null, request.serverId());
        }

        try {
            channel.writeAndFlush(mapper.writeValueAsString(payload) + "\n");
            ServiceState state = services.computeIfAbsent(request.serverId(), ServiceState::new);
            appendLog(state, requestedAt, "COMMAND", request.action() + " dispatched");
            persistCommand(commandId, request.serverId(), request.action(), requestedAt, "SENT", payload);
            broadcastSnapshot();
            return new CommandResult(true, "SENT", commandId, request.serverId());
        } catch (Exception ex) {
            return new CommandResult(false, "SERIALIZATION_ERROR", null, request.serverId());
        }
    }

    public ServicePurgeResult purgeOfflineServiceHistory(String serverId, String token) {
        String normalizedServerId = MonitorProtocol.stringValue(serverId);
        if (normalizedServerId.isBlank()) {
            return new ServicePurgeResult(false, "MISSING_SERVER_ID", normalizedServerId);
        }
        if (!Objects.equals(controlToken, MonitorProtocol.stringValue(token))) {
            return new ServicePurgeResult(false, "UNAUTHORIZED", normalizedServerId);
        }

        ServiceState state = services.get(normalizedServerId);
        if (state == null) {
            jdbcHistoryStore.deleteHistoryByServerId(normalizedServerId);
            return new ServicePurgeResult(true, "PURGED_HISTORY_ONLY", normalizedServerId);
        }
        if (!"OFFLINE".equalsIgnoreCase(MonitorProtocol.stringValue(state.status()))) {
            return new ServicePurgeResult(false, "SERVICE_ONLINE", normalizedServerId);
        }
        Channel channel = agentChannels.get(normalizedServerId);
        if (channel != null && channel.isActive()) {
            return new ServicePurgeResult(false, "AGENT_CONNECTED", normalizedServerId);
        }

        services.remove(normalizedServerId);
        agentChannels.remove(normalizedServerId);
        alerts.removeIf(alert -> normalizedServerId.equals(alert.serverId()));
        loadedAlertIds.entrySet().removeIf(entry -> {
            String alertId = entry.getKey();
            for (AlertRecord alert : alerts) {
                if (alert.id().equals(alertId)) {
                    return false;
                }
            }
            return true;
        });
        jdbcHistoryStore.deleteHistoryByServerId(normalizedServerId);
        broadcastSnapshot();
        return new ServicePurgeResult(true, "PURGED", normalizedServerId);
    }

    public ServicePortAddResult addManualServicePort(Integer portValue,
                                                     String host,
                                                     String serviceName,
                                                     String serverType,
                                                     String token,
                                                     String accountKey) {
        if (!Objects.equals(controlToken, MonitorProtocol.stringValue(token))) {
            return new ServicePortAddResult(false, "UNAUTHORIZED", "", 0);
        }

        String normalizedAccount = MonitorProtocol.stringValue(accountKey);
        if (normalizedAccount.isBlank()) {
            return new ServicePortAddResult(false, "INVALID_ACCOUNT", "", 0);
        }

        int port = portValue == null ? 0 : portValue;
        if (port <= 0 || port > 65535) {
            return new ServicePortAddResult(false, "INVALID_PORT", "", port);
        }

        String normalizedHost = MonitorProtocol.stringValue(host);
        if (normalizedHost.isBlank()) {
            normalizedHost = "127.0.0.1";
        }
        String normalizedType = MonitorProtocol.stringValue(serverType);
        if (normalizedType.isBlank()) {
            normalizedType = "backend";
        }
        String normalizedName = MonitorProtocol.stringValue(serviceName);
        if (normalizedName.isBlank()) {
            normalizedName = "手动新增端口 " + port;
        }

        final String resolvedHost = normalizedHost;
        final String resolvedType = normalizedType;
        final String resolvedName = normalizedName;

        String serverId = buildManualAccountServerId(normalizedAccount);
        Instant now = Instant.now();
        services.compute(serverId, (key, existing) -> {
            ServiceState current = existing == null ? new ServiceState(serverId) : existing;
            current.serverType(resolvedType);
            current.serviceName(resolvedName + " (" + normalizedAccount + ")");
            current.host(resolvedHost);
            current.port(port);
            current.status("OFFLINE");
            current.registerTime(current.registerTime() == null ? now : current.registerTime());
            current.lastHeartbeat(now);
            current.startupTime(current.startupTime() == null ? now : current.startupTime());
            appendLog(current, now, "MANUAL_PORT", "Account " + normalizedAccount + " mapped to " + resolvedHost + ":" + port);
            return current;
        });

        broadcastSnapshot();
        return new ServicePortAddResult(true, "PORT_ADDED", serverId, port);
    }

    public AccountPresenceResult updateAccountPresence(String accountKey,
                                                       Boolean online,
                                                       Integer portValue,
                                                       String host,
                                                       String token) {
        if (!Objects.equals(controlToken, MonitorProtocol.stringValue(token))) {
            return new AccountPresenceResult(false, "UNAUTHORIZED", "", false, 0);
        }

        String normalizedAccount = MonitorProtocol.stringValue(accountKey);
        if (normalizedAccount.isBlank()) {
            return new AccountPresenceResult(false, "INVALID_ACCOUNT", "", false, 0);
        }

        String serverId = buildManualAccountServerId(normalizedAccount);
        Instant now = Instant.now();
        boolean targetOnline = Boolean.TRUE.equals(online);

        services.compute(serverId, (key, existing) -> {
            ServiceState current = existing == null ? new ServiceState(serverId) : existing;
            String previousStatus = MonitorProtocol.stringValue(current.status());
            int resolvedPort = portValue == null ? current.port() : portValue;
            if (resolvedPort < 0 || resolvedPort > 65535) {
                resolvedPort = current.port();
            }
            String resolvedHost = MonitorProtocol.stringValue(host);
            if (resolvedHost.isBlank()) {
                resolvedHost = MonitorProtocol.blankToEmpty(current.host());
            }
            if (resolvedHost.isBlank()) {
                resolvedHost = "127.0.0.1";
            }

            current.serverType("account");
            current.serviceName("账号节点 " + normalizedAccount);
            current.host(resolvedHost);
            current.port(resolvedPort);
            current.status(targetOnline ? "ONLINE" : "OFFLINE");
            current.registerTime(current.registerTime() == null ? now : current.registerTime());
            current.lastHeartbeat(now);
            if (targetOnline && "OFFLINE".equalsIgnoreCase(previousStatus)) {
                current.startupTime(now);
            } else {
                current.startupTime(current.startupTime() == null ? now : current.startupTime());
            }

            if (targetOnline) {
                applyAccountNodeMetrics(current, resolvedHost, resolvedPort);
                current.enrichDerivedMetrics();
                MetricSample metricSample = current.pushMetric(now);
                persistMetric(current.serverId(), metricSample);
                evaluateThresholdAlerts(current, now);
            }

            appendLog(current, now, targetOnline ? "ACCOUNT_ONLINE" : "ACCOUNT_OFFLINE",
                    "Account " + normalizedAccount + (targetOnline ? " online" : " offline") + " @ " + resolvedHost + ":" + resolvedPort);
            trimHistory(current, now);
            return current;
        });

        ServiceState updated = services.get(serverId);
        broadcastSnapshot();
        return new AccountPresenceResult(
                true,
                targetOnline ? "ONLINE_UPDATED" : "OFFLINE_UPDATED",
                serverId,
                targetOnline,
                updated == null ? 0 : updated.port());
    }

    private void applyAccountNodeMetrics(ServiceState state, String host, int port) {
        state.cpuUsage(sampleSystemCpuPercent());
        state.memoryUsage(sampleSystemMemoryPercent());
        state.diskUsage(sampleDiskUsagePercent());
        state.networkLatency(sampleConnectLatency(host, port));
    }

    private double sampleSystemCpuPercent() {
        try {
            OperatingSystemMXBean osBean = (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
            double load = osBean.getSystemCpuLoad();
            if (load < 0.0d) {
                return 0.0d;
            }
            return Math.round(Math.min(100.0d, load * 100.0d) * 100.0d) / 100.0d;
        } catch (Exception ignored) {
            return 0.0d;
        }
    }

    private double sampleSystemMemoryPercent() {
        try {
            OperatingSystemMXBean osBean = (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
            long total = osBean.getTotalMemorySize();
            long free = osBean.getFreeMemorySize();
            if (total <= 0L) {
                return 0.0d;
            }
            double usedPercent = ((double) (total - free) / (double) total) * 100.0d;
            return Math.round(Math.min(100.0d, Math.max(0.0d, usedPercent)) * 100.0d) / 100.0d;
        } catch (Exception ignored) {
            return 0.0d;
        }
    }

    private double sampleDiskUsagePercent() {
        try {
            File[] roots = File.listRoots();
            if (roots == null || roots.length == 0) {
                return 0.0d;
            }
            long total = 0L;
            long free = 0L;
            for (File root : roots) {
                if (root == null) {
                    continue;
                }
                total += Math.max(0L, root.getTotalSpace());
                free += Math.max(0L, root.getUsableSpace());
            }
            if (total <= 0L) {
                return 0.0d;
            }
            double usedPercent = ((double) (total - free) / (double) total) * 100.0d;
            return Math.round(Math.min(100.0d, Math.max(0.0d, usedPercent)) * 100.0d) / 100.0d;
        } catch (Exception ignored) {
            return 0.0d;
        }
    }

    private int sampleConnectLatency(String host, int port) {
        String targetHost = MonitorProtocol.stringValue(host).isBlank() ? "127.0.0.1" : host;
        int targetPort = port <= 0 || port > 65535 ? 80 : port;
        long begin = System.nanoTime();
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(targetHost, targetPort), 1200);
            long elapsedMs = (System.nanoTime() - begin) / 1_000_000L;
            return (int) Math.max(1L, Math.min(5000L, elapsedMs));
        } catch (Exception ignored) {
            return 200;
        }
    }

    private String buildManualAccountServerId(String accountKey) {
        String normalizedAccount = MonitorProtocol.stringValue(accountKey)
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+", "")
                .replaceAll("-+$", "");
        if (normalizedAccount.isBlank()) {
            normalizedAccount = "guest";
        }
        return "manual-account-" + normalizedAccount;
    }

    private CommandResult launchOfflineRestart(CommandRequest request, String commandId, Instant requestedAt, CommandMessage payload) {
        ServiceState state = services.computeIfAbsent(request.serverId(), ServiceState::new);
        RestartTargetContext context = resolveRestartTargetContext(request, state);

        if (context.port() <= 0 || context.port() > 65535) {
            appendLog(state, requestedAt, "COMMAND", "RESTART local launch skipped: invalid target port " + context.port());
            return new CommandResult(false, "INVALID_TARGET_PORT", null, request.serverId());
        }

        state.serviceName(context.serviceName());
        state.serverType(context.serverType());
        state.host(context.host());
        state.port(context.port());

        Path restartScript = resolveLocalRestartScript(request, state);
        if (restartScript == null) {
            appendLog(state, requestedAt, "COMMAND", "RESTART local launch skipped: restart script not found");
            return new CommandResult(false, "LOCAL_RESTART_SCRIPT_NOT_FOUND", null, request.serverId());
        }

        try {
            ProcessBuilder processBuilder = new ProcessBuilder(buildRestartCommand(restartScript, context));
            Path workingDirectory = restartScript.getParent();
            if (workingDirectory != null && Files.exists(workingDirectory)) {
                processBuilder.directory(workingDirectory.toFile());
            }
            processBuilder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            processBuilder.redirectError(ProcessBuilder.Redirect.DISCARD);
            processBuilder.start();
            appendLog(state, requestedAt, "COMMAND", "RESTART launched locally by monitor-server");
            persistCommand(commandId, request.serverId(), request.action(), requestedAt, "LOCAL_LAUNCHED", payload);
            broadcastSnapshot();
            return new CommandResult(true, "LOCAL_LAUNCHED", commandId, request.serverId());
        } catch (Exception ex) {
            String reason = ex.getClass().getSimpleName();
            appendLog(state, requestedAt, "COMMAND", "RESTART local launch failed: " + reason + " - " + ex.getMessage());
            return new CommandResult(false, "LOCAL_LAUNCH_FAILED_" + reason.toUpperCase(Locale.ROOT), null, request.serverId());
        }
    }

    private RestartTargetContext resolveRestartTargetContext(CommandRequest request, ServiceState state) {
        Map<String, String> args = request == null || request.args() == null ? Map.of() : request.args();

        String serviceName = MonitorProtocol.stringValue(args.get("serviceName"));
        if (serviceName.isBlank()) {
            serviceName = state == null ? "" : MonitorProtocol.blankToEmpty(state.serviceName());
        }
        if (serviceName.isBlank()) {
            serviceName = "ecommerce-backend";
        }

        String serverType = MonitorProtocol.stringValue(args.get("serverType"));
        if (serverType.isBlank()) {
            serverType = state == null ? "" : MonitorProtocol.blankToEmpty(state.serverType());
        }
        if (serverType.isBlank()) {
            serverType = "backend";
        }

        String host = MonitorProtocol.stringValue(args.get("host"));
        if (host.isBlank()) {
            host = state == null ? "" : MonitorProtocol.blankToEmpty(state.host());
        }
        if (host.isBlank()) {
            host = "127.0.0.1";
        }

        int port = MonitorProtocol.intValue(args.get("port"));
        if (port <= 0 || port > 65535) {
            int statePort = state == null ? 0 : state.port();
            if (statePort > 0 && statePort <= 65535) {
                port = statePort;
            }
        }
        if (port <= 0 || port > 65535) {
            port = 0;
        }

        String serviceId = MonitorProtocol.stringValue(args.get("serviceId"));
        if (serviceId.isBlank()) {
            serviceId = state == null ? "" : MonitorProtocol.blankToEmpty(state.serverId());
        }

        String runtimeName = serviceId.isBlank()
                ? (serviceName.replaceAll("[^a-zA-Z0-9._-]", "-") + "-" + port)
                : serviceId.replaceAll("[^a-zA-Z0-9._-]", "-");

        return new RestartTargetContext(serviceName, serverType, host, port, serviceId, runtimeName);
    }

    private Path resolveLocalRestartScript(CommandRequest request, ServiceState state) {
        String configured = MonitorProtocol.blankToEmpty(System.getenv("MONITOR_LOCAL_RESTART_SCRIPT"));
        if (!configured.isBlank()) {
            Path candidate = Path.of(configured).toAbsolutePath().normalize();
            if (Files.exists(candidate)) {
                return candidate;
            }
        }

        String serviceName = state == null ? "" : MonitorProtocol.blankToEmpty(state.serviceName());
        Path current = Path.of(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
        for (int depth = 0; depth < 4 && current != null; depth++) {
            Path genericCandidate = current.resolve("start-monitor-node-instance.ps1");
            if (Files.exists(genericCandidate)) {
                return genericCandidate;
            }
            if ("ecommerce-backend".equalsIgnoreCase(serviceName) || serviceName.isBlank() || !serviceName.isBlank()) {
                Path candidate = current.resolve("start-ecommerce-backend.ps1");
                if (Files.exists(candidate)) {
                    return candidate;
                }
            }
            current = current.getParent();
        }
        return null;
    }

    private List<String> buildRestartCommand(Path restartScript, RestartTargetContext context) {
        String script = restartScript.toAbsolutePath().normalize().toString();
        String serviceName = context.serviceName();
        String serverType = context.serverType();
        String host = context.host();
        int port = context.port();
        String serviceId = context.serviceId();
        String runtimeName = context.runtimeName();

        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
            if (script.endsWith("start-monitor-node-instance.ps1")) {
                return List.of(
                        "powershell", "-ExecutionPolicy", "Bypass", "-File", script,
                        "-Action", "restart",
                        "-ServiceName", serviceName,
                        "-ServerType", serverType,
                        "-Port", String.valueOf(port),
                        "-AdvertiseHost", host,
                        "-RuntimeName", runtimeName,
                        "-ServiceId", serviceId);
            }
            return List.of(
                    "powershell", "-ExecutionPolicy", "Bypass", "-File", script,
                    "-Action", "restart",
                    "-ServiceName", serviceName,
                    "-ServerType", serverType,
                    "-AppPort", String.valueOf(port),
                    "-AdvertiseHost", host,
                    "-RuntimeName", runtimeName,
                    "-ServiceId", serviceId);
        }
        return List.of("sh", script);
    }

    private record RestartTargetContext(String serviceName,
                                        String serverType,
                                        String host,
                                        int port,
                                        String serviceId,
                                        String runtimeName) {
    }

    public SnapshotEvent snapshotEvent() {
        long activeAlerts = alerts.stream().filter(alert -> !alert.acknowledged()).count();
        return new SnapshotEvent("snapshot", Instant.now(), listServices(), listAlerts().stream().limit(12).toList(), (int) activeAlerts);
    }

    public void registerWebChannel(Channel channel) {
        if (channel != null) {
            webSockets.add(channel);
        }
    }

    public void unregisterWebChannel(Channel channel) {
        if (channel != null) {
            webSockets.remove(channel);
        }
    }

    private void broadcastSnapshot() {
        try {
            String json = mapper.writeValueAsString(snapshotEvent());
            webSockets.writeAndFlush(new TextWebSocketFrame(json));
        } catch (Exception ignored) {
        }
    }

    private void trimHistory(ServiceState state, Instant now) {
        Instant cutoff = now.minus(Duration.ofDays(7));
        while (!state.metricsHistory().isEmpty() && state.metricsHistory().peekFirst().timestamp().isBefore(cutoff)) {
            state.metricsHistory().pollFirst();
        }
        while (!state.logs().isEmpty() && state.logs().peekFirst().timestamp().isBefore(cutoff)) {
            state.logs().pollFirst();
        }
        while (!state.alerts().isEmpty() && state.alerts().peekFirst().timestamp().isBefore(cutoff)) {
            state.alerts().pollFirst();
        }
        while (!alerts.isEmpty() && alerts.peekFirst().timestamp().isBefore(cutoff)) {
            alerts.pollFirst();
        }
        while (state.metricsHistory().size() > 336) {
            state.metricsHistory().pollFirst();
        }
        while (state.logs().size() > 120) {
            state.logs().pollFirst();
        }
        while (state.alerts().size() > 80) {
            state.alerts().pollFirst();
        }
        while (alerts.size() > 200) {
            alerts.pollFirst();
        }
    }

    private void pushAlert(ServiceState state, Instant timestamp, String severity, String source, String message) {
        pushAlert(state, timestamp, severity, source, message, "", "", null, null, 0);
    }

    private void pushAlert(ServiceState state, Instant timestamp, String severity, String source, String message,
                           String policyKey, String metric, Double currentValue, Double thresholdValue,
                           int suppressedCount) {
        AlertRecord alert = new AlertRecord(
                UUID.randomUUID().toString(),
                timestamp,
                state.serverId(),
                state.serviceName() == null || state.serviceName().isBlank() ? state.serverId() : state.serviceName(),
                normalizeSeverity(severity),
                source == null || source.isBlank() ? "monitor" : source,
                message == null || message.isBlank() ? "Alert triggered" : message,
                policyKey == null ? "" : policyKey,
                metric == null ? "" : metric,
                currentValue,
                thresholdValue,
                suppressedCount
        );
        state.alerts().addLast(alert);
        alerts.addLast(alert);
        loadedAlertIds.put(alert.id(), Boolean.TRUE);
        persistAlert(alert);
        dispatchAlertNotifications(alert);
    }

    private void evaluateThresholdAlerts(ServiceState state, Instant now) {
        for (AlertPolicyDefinition policy : alertPolicies) {
            evaluateThresholdAlert(state, now, policy, currentMetricValue(state, policy));
        }
    }

    private void evaluateThresholdAlert(ServiceState state, Instant now, AlertPolicyDefinition policy, double currentValue) {
        String activeKey = state.serverId() + ':' + policy.key();
        if (currentValue < policy.warningThreshold()) {
            if (activePolicySeverity.remove(activeKey) != null) {
                appendLog(state, now, "THRESHOLD_RECOVERY", policy.description() + " 已恢复到阈值以下");
            }
            alertSuppression.remove(activeKey + ":WARNING");
            alertSuppression.remove(activeKey + ":CRITICAL");
            suppressedAlertCounts.remove(activeKey + ":WARNING");
            suppressedAlertCounts.remove(activeKey + ":CRITICAL");
            return;
        }

        String severity = currentValue >= policy.criticalThreshold() ? "CRITICAL" : "WARNING";
        String fingerprint = activeKey + ':' + severity;
        String lowerFingerprint = activeKey + ":WARNING";
        if ("CRITICAL".equals(severity)) {
            alertSuppression.remove(lowerFingerprint);
            suppressedAlertCounts.remove(lowerFingerprint);
        }

        Instant lastSentAt = alertSuppression.get(fingerprint);
        long suppressSeconds = "CRITICAL".equals(severity) ? policy.criticalSuppressSeconds() : policy.warningSuppressSeconds();
        if (lastSentAt != null && Duration.between(lastSentAt, now).getSeconds() < suppressSeconds) {
            suppressedAlertCounts.merge(fingerprint, 1, Integer::sum);
            return;
        }

        alertSuppression.put(fingerprint, now);
        activePolicySeverity.put(activeKey, severity);
        int suppressedCount = suppressedAlertCounts.getOrDefault(fingerprint, 0);
        suppressedAlertCounts.remove(fingerprint);
        String message = String.format(
                Locale.ROOT,
                "%s %.2f%s 超过 %s 阈值 %.2f%s%s",
                policy.description(),
                currentValue,
                policy.unit(),
                severity.toLowerCase(Locale.ROOT),
                "CRITICAL".equals(severity) ? policy.criticalThreshold() : policy.warningThreshold(),
                policy.unit(),
                suppressedCount > 0 ? String.format(Locale.ROOT, "，期间抑制重复告警 %d 次", suppressedCount) : "");
        appendLog(state, now, "THRESHOLD_ALERT", message);
        pushAlert(
                state,
                now,
                severity,
                "policy",
                message,
                policy.key(),
                policy.metric(),
                currentValue,
                "CRITICAL".equals(severity) ? policy.criticalThreshold() : policy.warningThreshold(),
                suppressedCount);
    }

    private double currentMetricValue(ServiceState state, AlertPolicyDefinition policy) {
        return switch (policy.metric()) {
            case "cpuUsage" -> state.cpuUsage();
            case "memoryUsage" -> state.memoryUsage();
            case "diskUsage" -> state.diskUsage();
            case "networkLatency" -> state.networkLatency();
            default -> 0.0d;
        };
    }

    private void dispatchAlertNotifications(AlertRecord alert) {
        NotificationTargets targets = notificationTargets;
        if (targets == null || !targets.enabled()) {
            return;
        }
        if (targets.emailWebhookUrl() != null && !targets.emailWebhookUrl().isBlank()) {
            dispatchWebhook(targets.emailWebhookUrl(), Map.of(
                    "channel", "email",
                    "severity", alert.severity(),
                    "serverId", alert.serverId(),
                    "serviceName", alert.serviceName(),
                    "message", alert.message(),
                    "recipients", targets.emailRecipients(),
                    "timestamp", alert.timestamp().toString()
            ));
        }
        if (targets.wecomWebhookUrl() != null && !targets.wecomWebhookUrl().isBlank()) {
            dispatchWebhook(targets.wecomWebhookUrl(), Map.of(
                    "msgtype", "markdown",
                    "markdown", Map.of(
                            "content", String.format(Locale.ROOT,
                                    "## 监控告警\n- 级别: **%s**\n- 节点: **%s**\n- 服务: **%s**\n- 内容: %s\n- 时间: %s",
                                    alert.severity(),
                                    alert.serverId(),
                                    alert.serviceName(),
                                    alert.message(),
                                    alert.timestamp())
                    )
            ));
        }
    }

    private void dispatchWebhook(String endpoint, Object payload) {
        Thread thread = new Thread(() -> {
            try {
                HttpURLConnection connection = (HttpURLConnection) URI.create(endpoint).toURL().openConnection();
                connection.setRequestMethod("POST");
                connection.setConnectTimeout(3000);
                connection.setReadTimeout(3000);
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                byte[] body = mapper.writeValueAsBytes(payload);
                connection.getOutputStream().write(body);
                connection.getOutputStream().flush();
                connection.getInputStream().close();
                connection.disconnect();
            } catch (IOException ignored) {
            }
        }, "monitor-alert-webhook");
        thread.setDaemon(true);
        thread.start();
    }

    private String normalizeSeverity(String severity) {
        String normalized = MonitorProtocol.stringValue(severity).toUpperCase(Locale.ROOT);
        if (normalized.isBlank()) {
            return "WARNING";
        }
        return normalized;
    }

    private String requireServerId(Map<String, Object> payload) {
        String serverId = MonitorProtocol.stringValue(payload.get("serverId"));
        if (serverId.isBlank()) {
            throw new IllegalArgumentException("Missing serverId");
        }
        return serverId;
    }

    private void restorePersistedState() {
        restoreRecentHistory();
    }

    private void restoreRecentHistory() {
        if (!jdbcHistoryStore.isAvailable()) {
            return;
        }
        Instant since = Instant.now().minus(Duration.ofDays(7));
        for (JdbcHistoryStore.PersistedMetric persistedMetric : jdbcHistoryStore.loadRecentMetrics(since)) {
            if (persistedMetric.payloadJson() == null || persistedMetric.payloadJson().isBlank()) {
                continue;
            }
            try {
                MetricSample metricSample = mapper.readValue(persistedMetric.payloadJson(), MetricSample.class);
                ServiceState state = services.computeIfAbsent(persistedMetric.serverId(), ServiceState::new);
                state.metricsHistory().addLast(metricSample);
            } catch (Exception ignored) {
            }
        }
        for (JdbcHistoryStore.PersistedLog persistedLog : jdbcHistoryStore.loadRecentLogs(since)) {
            if (persistedLog.payloadJson() == null || persistedLog.payloadJson().isBlank()) {
                continue;
            }
            try {
                LogEntry logEntry = mapper.readValue(persistedLog.payloadJson(), LogEntry.class);
                ServiceState state = services.computeIfAbsent(persistedLog.serverId(), ServiceState::new);
                state.logs().addLast(logEntry);
            } catch (Exception ignored) {
            }
        }
        for (JdbcHistoryStore.PersistedAlert persistedAlert : jdbcHistoryStore.loadRecentAlerts(since)) {
            if (persistedAlert.payloadJson() == null || persistedAlert.payloadJson().isBlank()) {
                continue;
            }
            try {
                AlertRecord alert = deserializeAlertRecord(persistedAlert.payloadJson());
                if (alert == null || loadedAlertIds.putIfAbsent(alert.id(), Boolean.TRUE) != null) {
                    continue;
                }
                ServiceState state = services.computeIfAbsent(persistedAlert.serverId(), ServiceState::new);
                state.alerts().addLast(alert);
                alerts.addLast(alert);
            } catch (Exception ignored) {
            }
        }
    }

    private void appendLog(ServiceState state, Instant timestamp, String category, String message) {
        LogEntry logEntry = state.pushLog(timestamp, category, message);
        persistLog(state.serverId(), category, message, logEntry);
    }

    private void persistMetric(String serverId, MetricSample metricSample) {
        try {
            jdbcHistoryStore.saveMetric(serverId, metricSample.timestamp(), mapper.writeValueAsString(metricSample));
        } catch (Exception ignored) {
        }
    }

    private void persistLog(String serverId, String category, String message, LogEntry logEntry) {
        try {
            jdbcHistoryStore.saveLog(serverId, logEntry.timestamp(), category, message, mapper.writeValueAsString(logEntry));
        } catch (Exception ignored) {
        }
    }

    private void persistAlert(AlertRecord alert) {
        try {
            jdbcHistoryStore.saveAlert(alert.id(), alert.serverId(), alert.timestamp(), alert.acknowledged(), mapper.writeValueAsString(alert.toMap()));
        } catch (Exception ignored) {
        }
    }

    private void persistCommand(String commandId, String serverId, String action, Instant requestedAt, String status, Object payload) {
        if (commandId == null || commandId.isBlank()) {
            return;
        }
        try {
            jdbcHistoryStore.saveCommand(commandId, serverId, action == null ? "UNKNOWN" : action, requestedAt, status, mapper.writeValueAsString(payload));
        } catch (Exception ignored) {
        }
    }

    private AlertRecord deserializeAlertRecord(String payload) {
        try {
            Map<String, Object> map = mapper.readValue(payload, new TypeReference<Map<String, Object>>() { });
            AlertRecord alert = new AlertRecord(
                    MonitorProtocol.stringValue(map.get("id")),
                    MonitorProtocol.instantValue(map.get("timestamp"), Instant.now()),
                    MonitorProtocol.stringValue(map.get("serverId")),
                    MonitorProtocol.stringValue(map.get("serviceName")),
                    MonitorProtocol.stringValue(map.get("severity")),
                    MonitorProtocol.stringValue(map.get("source")),
                    MonitorProtocol.stringValue(map.get("message")),
                    MonitorProtocol.stringValue(map.get("policyKey")),
                    MonitorProtocol.stringValue(map.get("metric")),
                    MonitorProtocol.nullableDecimal(map.get("currentValue")),
                    MonitorProtocol.nullableDecimal(map.get("thresholdValue")),
                    MonitorProtocol.intValue(map.get("suppressedCount"))
            );
            if (Boolean.TRUE.equals(map.get("acknowledged")) || "true".equalsIgnoreCase(MonitorProtocol.stringValue(map.get("acknowledged")))) {
                alert.acknowledge();
            }
            return alert;
        } catch (Exception ex) {
            return null;
        }
    }

    private boolean hasHostInfoKey(Map<String, Object> payload, String key) {
        return hostInfoMap(payload).containsKey(key);
    }

    private Object firstPayloadValue(Map<String, Object> payload, String key, Object fallback) {
        Map<String, Object> hostInfo = hostInfoMap(payload);
        if (hostInfo.containsKey(key)) {
            return hostInfo.get(key);
        }
        return payload.containsKey(key) ? payload.get(key) : fallback;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> hostInfoMap(Map<String, Object> payload) {
        Object hostInfo = payload.get("hostInfo");
        if (hostInfo instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return Map.of();
    }
}