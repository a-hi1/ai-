package com.example.ecommerce.dto.monitor;

import java.time.Instant;

public record HostInfo(
        String host,
        Integer port,
        Instant startupTime,
        String status,
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
        Instant lastGcAt
) {
}