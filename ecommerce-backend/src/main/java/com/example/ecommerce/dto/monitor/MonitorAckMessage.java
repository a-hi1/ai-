package com.example.ecommerce.dto.monitor;

public record MonitorAckMessage(
        String type,
        String status,
        String serverId,
        String code,
        String message
) {
}