package com.example.ecommerce.model;

import java.time.Instant;
import java.util.UUID;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

@Table("service_nodes")
public class ServiceNode {
    @Id
    private UUID id;
    private String serviceName;
    private String host;
    private int port;
    private String status;
    private Instant lastHeartbeat;

    public ServiceNode() {
    }

    public ServiceNode(UUID id, String serviceName, String host, int port, String status, Instant lastHeartbeat) {
        this.id = id;
        this.serviceName = serviceName;
        this.host = host;
        this.port = port;
        this.status = status;
        this.lastHeartbeat = lastHeartbeat;
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getServiceName() {
        return serviceName;
    }

    public void setServiceName(String serviceName) {
        this.serviceName = serviceName;
    }

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

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Instant getLastHeartbeat() {
        return lastHeartbeat;
    }

    public void setLastHeartbeat(Instant lastHeartbeat) {
        this.lastHeartbeat = lastHeartbeat;
    }
}
