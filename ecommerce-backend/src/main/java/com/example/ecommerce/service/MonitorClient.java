package com.example.ecommerce.service;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Service;

import com.example.ecommerce.config.MonitorClientProperties;
import com.example.ecommerce.dto.monitor.HostInfo;
import com.example.ecommerce.dto.monitor.MonitorAckMessage;
import com.example.ecommerce.dto.monitor.MonitorAgentCommand;
import com.example.ecommerce.dto.monitor.MonitorAgentMessage;
import com.example.ecommerce.util.ServerInfoUtil;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

@Service
public class MonitorClient implements ApplicationRunner, DisposableBean {
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final MonitorClientProperties properties;
    private final ScheduledExecutorService scheduler;
    private final AtomicBoolean registered = new AtomicBoolean(false);
    private final AtomicBoolean shutdownScheduled = new AtomicBoolean(false);
    private final AtomicReference<String> serviceIdRef = new AtomicReference<>("");
    private final AtomicReference<Socket> socketRef = new AtomicReference<>();
    private final AtomicReference<BufferedWriter> writerRef = new AtomicReference<>();
    private final AtomicReference<BufferedReader> readerRef = new AtomicReference<>();
    private final AtomicLong lastObservedGcCount = new AtomicLong(-1L);
    private final AtomicReference<Instant> lastGcAtRef = new AtomicReference<>();
    private final Instant startupTime = Instant.now();
    private final MemoryMXBean memoryMxBean = ManagementFactory.getMemoryMXBean();
    private final ThreadMXBean threadMxBean = ManagementFactory.getThreadMXBean();
    private final List<GarbageCollectorMXBean> garbageCollectorMxBeans = ManagementFactory.getGarbageCollectorMXBeans();
    private final ServerInfoUtil serverInfoUtil = new ServerInfoUtil();

    public MonitorClient(MonitorClientProperties properties) {
        this.properties = properties;
        this.scheduler = Executors.newScheduledThreadPool(2, new MonitorThreadFactory());
    }

    @Override
    public void run(ApplicationArguments args) {
        String serviceId = properties.getServiceId();
        if (serviceId == null || serviceId.isBlank()) {
            serviceId = buildStableServiceId();
        }
        serviceIdRef.set(serviceId);

        scheduler.execute(this::connectAndRegister);
        scheduler.scheduleWithFixedDelay(this::sendHeartbeatAndMetrics, properties.getHeartbeatIntervalMs(), properties.getHeartbeatIntervalMs(), TimeUnit.MILLISECONDS);
    }

    private String buildStableServiceId() {
        String serviceName = properties.getServiceName() == null || properties.getServiceName().isBlank()
                ? "service"
                : properties.getServiceName().trim();
        String host = properties.getAdvertiseHost() == null || properties.getAdvertiseHost().isBlank()
                ? "127.0.0.1"
                : properties.getAdvertiseHost().trim();
        int port = properties.getAdvertisePort();
        return serviceName + '@' + host + ':' + port;
    }

    private void connectAndRegister() {
        if (isConnected()) {
            return;
        }

        closeConnection();

        try {
            Socket socket = new Socket();
            socket.connect(new InetSocketAddress(properties.getHost(), properties.getPort()), 4000);
            socket.setKeepAlive(true);
            BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
            BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));

            socketRef.set(socket);
            writerRef.set(writer);
            readerRef.set(reader);
            registered.set(false);

                MonitorAgentMessage registerMessage = buildAgentMessage(
                    "REGISTER",
                    baseHostInfo("ONLINE"),
                    "ONLINE",
                    null,
                    null,
                    null,
                    null);

            send(registerMessage);
            String response = reader.readLine();
            if (response != null && !response.isBlank()) {
                MonitorAckMessage ack = MAPPER.readValue(response, MonitorAckMessage.class);
                registered.set("OK".equalsIgnoreCase(ack.status()));
            }

            if (registered.get()) {
                scheduler.execute(this::listenForCommands);
            }
        } catch (Exception ex) {
            registered.set(false);
            closeConnection();
        }
    }

    private void listenForCommands() {
        while (isConnected()) {
            try {
                BufferedReader reader = readerRef.get();
                if (reader == null) {
                    break;
                }
                String line = reader.readLine();
                if (line == null || line.isBlank()) {
                    break;
                }
                MonitorAgentCommand command = MAPPER.readValue(line, MonitorAgentCommand.class);
                if (!"COMMAND".equalsIgnoreCase(command.type())) {
                    continue;
                }
                handleCommand(command);
            } catch (Exception ex) {
                break;
            }
        }
        registered.set(false);
        closeConnection();
    }

    private void handleCommand(MonitorAgentCommand command) {
        String action = command.action() == null ? "UNKNOWN" : command.action().toUpperCase();
        String result = "IGNORED";
        String message = "Unsupported action";
        boolean shouldShutdown = false;

        if ("RESTART".equals(action)) {
            RestartOutcome restartOutcome = launchRestartScript();
            result = restartOutcome.success() ? "SUCCESS" : "ERROR";
            message = restartOutcome.message();
            shouldShutdown = restartOutcome.success();
        } else if ("CLOSE".equals(action)) {
            result = "SUCCESS";
            message = "Close command acknowledged; application shutdown scheduled.";
            shouldShutdown = true;
        } else if ("UPGRADE".equals(action)) {
            result = "SUCCESS";
            message = command.artifactUrl() == null || command.artifactUrl().isBlank()
                    ? "Upgrade command received without artifact URL."
                    : "Upgrade command acknowledged for artifact: " + command.artifactUrl();
        } else if ("FORCE_OFFLINE".equals(action)) {
            result = "SUCCESS";
            message = "Force offline acknowledged by agent.";
        }

        sendSafely(buildAgentMessage(
            "COMMAND_ACK",
            baseHostInfo("ONLINE"),
            "ONLINE",
            message,
            action,
            result,
            command.commandId()));

        if (shouldShutdown) {
            scheduleProcessShutdown(action);
        }
    }

    private RestartOutcome launchRestartScript() {
        try {
            Path restartScript = resolveRestartScript();
            if (restartScript == null) {
                return new RestartOutcome(false, "Restart script not found. Configure MONITOR_RESTART_SCRIPT first.");
            }

            List<String> command = buildRestartCommand(restartScript);
            ProcessBuilder processBuilder = new ProcessBuilder(command);
            Path workingDirectory = restartScript.getParent();
            if (workingDirectory != null && Files.exists(workingDirectory)) {
                processBuilder.directory(workingDirectory.toFile());
            }
            processBuilder.start();
            return new RestartOutcome(true, "Restart script launched: " + restartScript);
        } catch (Exception ex) {
            return new RestartOutcome(false, "Restart script launch failed: " + ex.getMessage());
        }
    }

    private List<String> buildRestartCommand(Path restartScript) {
        String script = restartScript.toAbsolutePath().normalize().toString();
        if (isWindows()) {
            return List.of("cmd", "/c", "start", "", "powershell", "-ExecutionPolicy", "Bypass", "-File", script);
        }
        return List.of("sh", script);
    }

    private Path resolveRestartScript() {
        if (properties.getRestartScript() != null && !properties.getRestartScript().isBlank()) {
            Path configured = Path.of(properties.getRestartScript()).toAbsolutePath().normalize();
            if (Files.exists(configured)) {
                return configured;
            }
        }

        Path current = Path.of(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
        for (int depth = 0; depth < 4 && current != null; depth++) {
            Path candidate = current.resolve("start-ecommerce-backend.ps1");
            if (Files.exists(candidate)) {
                return candidate;
            }
            current = current.getParent();
        }
        return null;
    }

    private void scheduleProcessShutdown(String action) {
        if (!shutdownScheduled.compareAndSet(false, true)) {
            return;
        }

        long delayMs = Math.max(500L, properties.getCommandShutdownDelayMs());
        scheduler.schedule(() -> {
            closeConnection();
            System.exit(0);
        }, delayMs, TimeUnit.MILLISECONDS);
    }

    private boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private void sendHeartbeatAndMetrics() {
        if (!isConnected() || !registered.get()) {
            connectAndRegister();
            return;
        }

        try {
            int latency = measureLatency();
            MetricsSnapshot snapshot = sampleMetrics();
                send(buildAgentMessage(
                    "HEARTBEAT",
                    snapshotHostInfo(snapshot, latency, "ONLINE"),
                    "ONLINE",
                    null,
                    null,
                    null,
                    null));

                send(buildAgentMessage(
                    "METRICS",
                    snapshotHostInfo(snapshot, latency, "ONLINE"),
                    "ONLINE",
                    null,
                    null,
                    null,
                    null));
        } catch (Exception ex) {
            registered.set(false);
                sendSafely(buildAgentMessage(
                    "ALERT",
                    baseHostInfo("OFFLINE"),
                    "OFFLINE",
                    ex.getMessage(),
                    null,
                    null,
                    null));
            closeConnection();
        }
    }

            private MonitorAgentMessage buildAgentMessage(
                String type,
                HostInfo hostInfo,
                String status,
                String message,
                String action,
                String result,
                String commandId) {
            return new MonitorAgentMessage(
                type,
                serviceIdRef.get(),
                properties.getServerType(),
                properties.getServiceName(),
                hostInfo,
                properties.getAdvertiseHost(),
                properties.getAdvertisePort(),
                startupTime,
                hostInfo == null ? null : hostInfo.cpuUsage(),
                hostInfo == null ? null : hostInfo.memoryUsage(),
                hostInfo == null ? null : hostInfo.jvmHeapUsage(),
                hostInfo == null ? null : hostInfo.diskUsage(),
                hostInfo == null ? null : hostInfo.networkLatency(),
                hostInfo == null ? null : hostInfo.networkThroughputMbps(),
                hostInfo == null ? null : hostInfo.threadCount(),
                hostInfo == null ? null : hostInfo.daemonThreadCount(),
                hostInfo == null ? null : hostInfo.gcCount(),
                hostInfo == null ? null : hostInfo.gcPauseMs(),
                hostInfo == null ? null : hostInfo.heapUsedMb(),
                hostInfo == null ? null : hostInfo.heapMaxMb(),
                hostInfo == null ? null : hostInfo.systemLoad(),
                hostInfo == null ? null : hostInfo.jvmStackSummary(),
                hostInfo == null ? null : hostInfo.lastGcAt(),
                status,
                message,
                action,
                result,
                commandId);
            }

            private HostInfo baseHostInfo(String status) {
            return new HostInfo(
                properties.getAdvertiseHost(),
                properties.getAdvertisePort(),
                startupTime,
                status,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null);
            }

            private HostInfo snapshotHostInfo(MetricsSnapshot snapshot, Integer latency, String status) {
            return new HostInfo(
                properties.getAdvertiseHost(),
                properties.getAdvertisePort(),
                startupTime,
                status,
                snapshot == null ? null : snapshot.cpuUsage(),
                snapshot == null ? null : snapshot.memoryUsage(),
                snapshot == null ? null : snapshot.jvmHeapUsage(),
                snapshot == null ? null : snapshot.diskUsage(),
                latency,
                snapshot == null ? null : snapshot.networkThroughputMbps(),
                snapshot == null ? null : snapshot.threadCount(),
                snapshot == null ? null : snapshot.daemonThreadCount(),
                snapshot == null ? null : snapshot.gcCount(),
                snapshot == null ? null : snapshot.gcPauseMs(),
                snapshot == null ? null : snapshot.heapUsedMb(),
                snapshot == null ? null : snapshot.heapMaxMb(),
                snapshot == null ? null : snapshot.systemLoad(),
                snapshot == null ? null : snapshot.jvmStackSummary(),
                snapshot == null ? null : snapshot.lastGcAt());
            }

    private void send(MonitorAgentMessage payload) throws Exception {
        BufferedWriter writer = writerRef.get();
        if (writer == null) {
            throw new IllegalStateException("Monitor writer not connected");
        }
        writer.write(MAPPER.writeValueAsString(payload));
        writer.write("\n");
        writer.flush();
    }

    private void sendSafely(MonitorAgentMessage payload) {
        try {
            if (isConnected()) {
                send(payload);
            }
        } catch (Exception ignored) {
        }
    }

    private boolean isConnected() {
        Socket socket = socketRef.get();
        return socket != null && socket.isConnected() && !socket.isClosed();
    }

    private int measureLatency() {
        long start = System.nanoTime();
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(properties.getHost(), properties.getPort()), 1500);
            return (int) TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        } catch (Exception ex) {
            return -1;
        }
    }

    private double jvmHeapUsage() {
        MemoryUsage heap = memoryMxBean.getHeapMemoryUsage();
        if (heap.getMax() <= 0) {
            return 0.0d;
        }
        return round(((double) heap.getUsed() / heap.getMax()) * 100.0d);
    }

    private MetricsSnapshot sampleMetrics() {
        ServerInfoUtil.ServerSnapshot serverSnapshot = serverInfoUtil.snapshot();
        MemoryUsage heap = memoryMxBean.getHeapMemoryUsage();
        double heapUsedMb = round(megabytes(heap.getUsed()));
        double heapMaxMb = heap.getMax() > 0 ? round(megabytes(heap.getMax())) : 0.0d;

        long gcCount = 0L;
        long gcTimeMs = 0L;
        for (GarbageCollectorMXBean garbageCollectorMXBean : garbageCollectorMxBeans) {
            if (garbageCollectorMXBean.getCollectionCount() > 0) {
                gcCount += garbageCollectorMXBean.getCollectionCount();
            }
            if (garbageCollectorMXBean.getCollectionTime() > 0) {
                gcTimeMs += garbageCollectorMXBean.getCollectionTime();
            }
        }

        Instant lastGcAt = lastGcAtRef.get();
        long previousGcCount = lastObservedGcCount.getAndSet(gcCount);
        if (previousGcCount >= 0 && gcCount > previousGcCount) {
            lastGcAt = Instant.now();
            lastGcAtRef.set(lastGcAt);
        }

        double gcPauseMs = gcCount > 0 ? round((double) gcTimeMs / (double) gcCount) : 0.0d;

        return new MetricsSnapshot(
                serverSnapshot.cpuUsage(),
                serverSnapshot.memoryUsage(),
                jvmHeapUsage(),
                serverSnapshot.diskUsage(),
                serverSnapshot.networkThroughputMbps(),
                threadMxBean.getThreadCount(),
                threadMxBean.getDaemonThreadCount(),
                gcCount,
                gcPauseMs,
                heapUsedMb,
                heapMaxMb,
                serverSnapshot.systemLoad(),
                buildJvmStackSummary(),
                lastGcAt);
    }

    private String buildJvmStackSummary() {
        try {
            ThreadInfo[] infos = threadMxBean.dumpAllThreads(false, false);
            if (infos == null || infos.length == 0) {
                return "暂无线程快照";
            }

            int runnable = 0;
            int blocked = 0;
            int waiting = 0;
            int timedWaiting = 0;
            List<String> highlights = new ArrayList<>();

            for (ThreadInfo info : infos) {
                if (info == null) {
                    continue;
                }
                Thread.State state = info.getThreadState();
                if (state == Thread.State.RUNNABLE) {
                    runnable++;
                } else if (state == Thread.State.BLOCKED) {
                    blocked++;
                } else if (state == Thread.State.WAITING) {
                    waiting++;
                } else if (state == Thread.State.TIMED_WAITING) {
                    timedWaiting++;
                }

                String threadName = info.getThreadName();
                if (threadName == null || threadName.startsWith("monitor-agent-")) {
                    continue;
                }
                String topFrame = info.getStackTrace() != null && info.getStackTrace().length > 0
                        ? info.getStackTrace()[0].toString()
                        : "no-frame";
                highlights.add(String.format(Locale.ROOT, "%s [%s] @ %s", threadName, state, topFrame));
            }

            highlights.sort(Comparator.naturalOrder());
            if (highlights.size() > 5) {
                highlights = new ArrayList<>(highlights.subList(0, 5));
            }

            StringBuilder summary = new StringBuilder();
            summary.append("RUNNABLE ").append(runnable)
                    .append(" | BLOCKED ").append(blocked)
                    .append(" | WAITING ").append(waiting)
                    .append(" | TIMED_WAITING ").append(timedWaiting);
            if (!highlights.isEmpty()) {
                summary.append("\n重点线程:\n");
                for (String highlight : highlights) {
                    summary.append("- ").append(highlight).append("\n");
                }
            }
            return summary.toString().trim();
        } catch (Exception ex) {
            return "线程快照采样失败: " + ex.getMessage();
        }
    }

    private double megabytes(long bytes) {
        return bytes / 1024.0d / 1024.0d;
    }

    private double round(double value) {
        return Math.round(value * 100.0d) / 100.0d;
    }

    private void closeConnection() {
        tryClose(readerRef.getAndSet(null));
        tryClose(writerRef.getAndSet(null));
        tryClose(socketRef.getAndSet(null));
    }

    private void tryClose(AutoCloseable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Exception ignored) {
        }
    }

    @Override
    public void destroy() {
        sendSafely(buildAgentMessage(
            "OFFLINE",
            baseHostInfo("OFFLINE"),
            "OFFLINE",
            "Application shutdown",
            null,
            null,
            null));
        scheduler.shutdownNow();
        closeConnection();
    }

    private static class MonitorThreadFactory implements ThreadFactory {
        private int index = 1;

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "monitor-agent-" + index++);
            thread.setDaemon(true);
            return thread;
        }
    }

    private record MetricsSnapshot(double cpuUsage, double memoryUsage, double jvmHeapUsage,
                                   double diskUsage, Double networkThroughputMbps,
                                   int threadCount, int daemonThreadCount,
                                   long gcCount, double gcPauseMs,
                                   double heapUsedMb, double heapMaxMb,
                                   double systemLoad, String jvmStackSummary,
                                   Instant lastGcAt) {
    }

    private record RestartOutcome(boolean success, String message) {
    }
}