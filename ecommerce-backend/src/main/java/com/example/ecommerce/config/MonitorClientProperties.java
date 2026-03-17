package com.example.ecommerce.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "monitor.client")
public class MonitorClientProperties {
    private String host = "localhost";
    private int port = 9999;
    private String serviceId = "";
    private String serviceName = "ecommerce-backend";
    private String serverType = "product";
    private String advertiseHost = "127.0.0.1";
    private int advertisePort = 8080;
    private long heartbeatIntervalMs = 30000;
    private String controlToken = "monitor-dev-token";
    private String restartScript = "";
    private long commandShutdownDelayMs = 1500;

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public String getServiceId() {
        return serviceId;
    }

    public void setServiceId(String serviceId) {
        this.serviceId = serviceId;
    }

    public String getServiceName() {
        return serviceName;
    }

    public void setServiceName(String serviceName) {
        this.serviceName = serviceName;
    }

    public String getServerType() {
        return serverType;
    }

    public void setServerType(String serverType) {
        this.serverType = serverType;
    }

    public String getAdvertiseHost() {
        return advertiseHost;
    }

    public void setAdvertiseHost(String advertiseHost) {
        this.advertiseHost = advertiseHost;
    }

    public int getAdvertisePort() {
        return advertisePort;
    }

    public void setAdvertisePort(int advertisePort) {
        this.advertisePort = advertisePort;
    }

    public long getHeartbeatIntervalMs() {
        return heartbeatIntervalMs;
    }

    public void setHeartbeatIntervalMs(long heartbeatIntervalMs) {
        this.heartbeatIntervalMs = heartbeatIntervalMs;
    }

    public String getControlToken() {
        return controlToken;
    }

    public void setControlToken(String controlToken) {
        this.controlToken = controlToken;
    }

    public String getRestartScript() {
        return restartScript;
    }

    public void setRestartScript(String restartScript) {
        this.restartScript = restartScript;
    }

    public long getCommandShutdownDelayMs() {
        return commandShutdownDelayMs;
    }

    public void setCommandShutdownDelayMs(long commandShutdownDelayMs) {
        this.commandShutdownDelayMs = commandShutdownDelayMs;
    }
}
