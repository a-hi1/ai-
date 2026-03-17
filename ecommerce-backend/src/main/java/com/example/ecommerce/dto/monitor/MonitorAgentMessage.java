package com.example.ecommerce.dto.monitor;

import java.time.Instant;

public record MonitorAgentMessage(
        String type,
        String serverId,
        String serverType,
        String serviceName,
        HostInfo hostInfo,
        String host,
        Integer port,
        Instant startupTime,
        Double cpuUsage,
        Double memoryUsage,
        Double jvmHeapUsage,
        Double diskUsage,
        Integer networkLatency,
        Double networkThroughputMbps,
        Integer threadCount,
        Integer daemonThreadCount,
        Long gcCount,
        Double gcPauseMs,
        Double heapUsedMb,
        Double heapMaxMb,
        Double systemLoad,
        String jvmStackSummary,
        Instant lastGcAt,
        String status,
        String message,
        String action,
        String result,
        String commandId
) {
}