package com.example.ecommerce.util;

import java.util.List;
import java.util.Locale;

import oshi.SystemInfo;
import oshi.hardware.CentralProcessor;
import oshi.hardware.GlobalMemory;
import oshi.hardware.HardwareAbstractionLayer;
import oshi.hardware.NetworkIF;
import oshi.software.os.FileSystem;
import oshi.software.os.OSFileStore;
import oshi.software.os.OperatingSystem;

public final class ServerInfoUtil {
    private final HardwareAbstractionLayer hardware;
    private final CentralProcessor processor;
    private final GlobalMemory memory;
    private final OperatingSystem operatingSystem;
    private long[] previousSystemTicks;
    private long lastObservedNetworkBytes = -1L;
    private long lastObservedNetworkSampleAt = -1L;

    public ServerInfoUtil() {
        SystemInfo systemInfo = new SystemInfo();
        this.hardware = systemInfo.getHardware();
        this.processor = hardware.getProcessor();
        this.memory = hardware.getMemory();
        this.operatingSystem = systemInfo.getOperatingSystem();
        this.previousSystemTicks = processor.getSystemCpuLoadTicks();
    }

    public synchronized ServerSnapshot snapshot() {
        return new ServerSnapshot(
                cpuUsage(),
                memoryUsage(),
                diskUsage(),
                networkThroughputMbps(),
                systemLoad());
    }

    private double cpuUsage() {
        long[] currentTicks = processor.getSystemCpuLoadTicks();
        double load = processor.getSystemCpuLoadBetweenTicks(previousSystemTicks);
        previousSystemTicks = currentTicks;
        if (load < 0.0d) {
            return 0.0d;
        }
        return round(load * 100.0d);
    }

    private double memoryUsage() {
        long total = memory.getTotal();
        long available = memory.getAvailable();
        if (total <= 0L) {
            return 0.0d;
        }
        return round(((double) (total - available) / (double) total) * 100.0d);
    }

    private double diskUsage() {
        try {
            FileSystem fileSystem = operatingSystem.getFileSystem();
            List<OSFileStore> fileStores = fileSystem.getFileStores();
            long total = 0L;
            long usable = 0L;
            for (OSFileStore fileStore : fileStores) {
                if (fileStore == null) {
                    continue;
                }
                total += Math.max(0L, fileStore.getTotalSpace());
                usable += Math.max(0L, fileStore.getUsableSpace());
            }
            if (total <= 0L) {
                return 0.0d;
            }
            return round(((double) (total - usable) / (double) total) * 100.0d);
        } catch (Exception ignored) {
            return 0.0d;
        }
    }

    private double networkThroughputMbps() {
        try {
            List<NetworkIF> networkInterfaces = hardware.getNetworkIFs();
            long currentBytes = 0L;
            for (NetworkIF networkIF : networkInterfaces) {
                if (networkIF == null) {
                    continue;
                }
                networkIF.updateAttributes();
                String interfaceName = String.valueOf(networkIF.getName()).toLowerCase(Locale.ROOT);
                String displayName = String.valueOf(networkIF.getDisplayName()).toLowerCase(Locale.ROOT);
                if (interfaceName.contains("loopback") || displayName.contains("loopback") || interfaceName.equals("lo")) {
                    continue;
                }
                currentBytes += Math.max(0L, networkIF.getBytesRecv());
                currentBytes += Math.max(0L, networkIF.getBytesSent());
            }

            long now = System.currentTimeMillis();
            long previousBytes = lastObservedNetworkBytes;
            long previousAt = lastObservedNetworkSampleAt;
            lastObservedNetworkBytes = currentBytes;
            lastObservedNetworkSampleAt = now;

            if (previousBytes < 0L || previousAt < 0L || now <= previousAt || currentBytes < previousBytes) {
                return 0.0d;
            }

            long deltaBytes = currentBytes - previousBytes;
            long deltaMillis = now - previousAt;
            if (deltaMillis <= 0L) {
                return 0.0d;
            }

            double mbps = (deltaBytes * 8.0d) / deltaMillis / 1000.0d;
            return round(Math.max(0.0d, mbps));
        } catch (Exception ignored) {
            return 0.0d;
        }
    }

    private double systemLoad() {
        double[] loadAverage = processor.getSystemLoadAverage(1);
        int logicalProcessors = Math.max(1, processor.getLogicalProcessorCount());
        if (loadAverage == null || loadAverage.length == 0 || loadAverage[0] < 0.0d) {
            return 0.0d;
        }
        return round(Math.min(100.0d, (loadAverage[0] / logicalProcessors) * 100.0d));
    }

    private double round(double value) {
        return Math.round(value * 100.0d) / 100.0d;
    }

    public record ServerSnapshot(double cpuUsage,
                                 double memoryUsage,
                                 double diskUsage,
                                 double networkThroughputMbps,
                                 double systemLoad) {
    }
}