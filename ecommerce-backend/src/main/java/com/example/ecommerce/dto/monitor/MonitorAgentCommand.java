package com.example.ecommerce.dto.monitor;

import java.util.Map;

public record MonitorAgentCommand(
        String type,
        String commandId,
        String action,
        String artifactUrl,
        Map<String, String> args
) {
}