package com.example.monitor.handler;

import java.util.Map;

import com.example.monitor.protocol.MonitorProtocol;
import com.example.monitor.registry.MonitorRegistry;

import com.fasterxml.jackson.core.type.TypeReference;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;

public final class MonitorTcpHandler extends SimpleChannelInboundHandler<String> {
    private final MonitorRegistry registry;

    public MonitorTcpHandler(MonitorRegistry registry) {
        this.registry = registry;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, String msg) {
        String line = msg == null ? "" : msg.trim();
        if (line.isBlank()) {
            return;
        }

        try {
            Map<String, Object> payload = MonitorProtocol.MAPPER.readValue(line, new TypeReference<Map<String, Object>>() { });
            String type = MonitorProtocol.stringValue(payload.get("type"));
            if (type.isBlank()) {
                writeTcp(ctx, MonitorProtocol.ack("ERROR", null, "MISSING_TYPE", "Missing message type"));
                return;
            }

            switch (type.toUpperCase()) {
                case "REGISTER" -> writeTcp(ctx, registry.handleRegister(ctx.channel(), payload));
                case "HEARTBEAT" -> writeTcp(ctx, registry.handleHeartbeat(ctx.channel(), payload));
                case "METRICS" -> writeTcp(ctx, registry.handleMetrics(ctx.channel(), payload));
                case "ALERT" -> writeTcp(ctx, registry.handleAlert(ctx.channel(), payload));
                case "OFFLINE" -> writeTcp(ctx, registry.handleOffline(ctx.channel(), payload));
                case "COMMAND_ACK" -> writeTcp(ctx, registry.handleCommandAck(ctx.channel(), payload));
                default -> writeTcp(ctx, MonitorProtocol.ack("ERROR", MonitorProtocol.stringValue(payload.get("serverId")), "UNKNOWN_TYPE", "Unknown message type"));
            }
        } catch (Exception ex) {
            writeTcp(ctx, MonitorProtocol.ack("ERROR", null, "INVALID_JSON", ex.getMessage()));
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        registry.markChannelOffline(ctx.channel());
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        registry.recordTransportError(ctx.channel(), cause == null ? "TCP_EXCEPTION" : cause.getMessage());
        ctx.close();
    }

    private void writeTcp(ChannelHandlerContext ctx, String message) {
        ctx.writeAndFlush(message + "\n");
    }
}