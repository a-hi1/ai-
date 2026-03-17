package com.example.monitor.handler;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import com.example.monitor.protocol.MonitorProtocol;
import com.example.monitor.protocol.MonitorProtocol.AlertAckRequest;
import com.example.monitor.protocol.MonitorProtocol.CommandRequest;
import com.example.monitor.protocol.MonitorProtocol.NotificationConfigUpdate;
import com.example.monitor.protocol.MonitorProtocol.ServiceDetail;
import com.example.monitor.registry.MonitorRegistry;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.QueryStringDecoder;
import io.netty.handler.codec.http.websocketx.PingWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PongWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;

public final class MonitorHttpWebSocketHandler extends SimpleChannelInboundHandler<Object> {
    private final MonitorRegistry registry;

    public MonitorHttpWebSocketHandler(MonitorRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        registry.registerWebChannel(ctx.channel());
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        registry.unregisterWebChannel(ctx.channel());
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (msg instanceof FullHttpRequest request) {
            handleHttpRequest(ctx, request);
            return;
        }

        if (msg instanceof PingWebSocketFrame frame) {
            ctx.writeAndFlush(new PongWebSocketFrame(frame.content().retain()));
            return;
        }

        if (msg instanceof TextWebSocketFrame frame) {
            handleWebSocketFrame(ctx, frame.text());
        }
    }

    private void handleHttpRequest(ChannelHandlerContext ctx, FullHttpRequest request) throws Exception {
        QueryStringDecoder decoder = new QueryStringDecoder(request.uri());
        String path = decoder.path();

        if (HttpMethod.OPTIONS.equals(request.method())) {
            writeJsonResponse(ctx, HttpResponseStatus.NO_CONTENT, "");
            return;
        }

        if (matches(path, "/health")) {
            writeJsonResponse(ctx, HttpResponseStatus.OK, MonitorProtocol.MAPPER.writeValueAsString(Map.of("status", "UP")));
            return;
        }

        if (HttpMethod.GET.equals(request.method()) && matches(path, "/api/monitor/list", "/monitor/list")) {
            writeJsonResponse(ctx, HttpResponseStatus.OK, MonitorProtocol.MAPPER.writeValueAsString(registry.listServices()));
            return;
        }

        if (HttpMethod.GET.equals(request.method()) && matches(path, "/api/monitor/overview", "/monitor/overview")) {
            writeJsonResponse(ctx, HttpResponseStatus.OK, MonitorProtocol.MAPPER.writeValueAsString(registry.overview()));
            return;
        }

        if (HttpMethod.GET.equals(request.method()) && matches(path, "/api/monitor/alerts", "/monitor/alerts")) {
            writeJsonResponse(ctx, HttpResponseStatus.OK, MonitorProtocol.MAPPER.writeValueAsString(registry.listAlerts()));
            return;
        }

        if (HttpMethod.GET.equals(request.method()) && matches(path, "/api/monitor/notifications", "/monitor/notifications")) {
            writeJsonResponse(ctx, HttpResponseStatus.OK, MonitorProtocol.MAPPER.writeValueAsString(registry.notificationTargets()));
            return;
        }

        if (HttpMethod.GET.equals(request.method()) && matches(path, "/api/monitor/alert-policies", "/monitor/alert-policies")) {
            writeJsonResponse(ctx, HttpResponseStatus.OK, MonitorProtocol.MAPPER.writeValueAsString(registry.listAlertPolicies()));
            return;
        }

        if (HttpMethod.GET.equals(request.method()) && (path.startsWith("/api/monitor/detail/") || path.startsWith("/monitor/detail/"))) {
            String serverId = path.substring(path.lastIndexOf('/') + 1);
            ServiceDetail detail = registry.getServiceDetail(serverId);
            if (detail == null) {
                writeJsonResponse(ctx, HttpResponseStatus.NOT_FOUND, MonitorProtocol.MAPPER.writeValueAsString(Map.of("message", "NOT_FOUND")));
                return;
            }
            writeJsonResponse(ctx, HttpResponseStatus.OK, MonitorProtocol.MAPPER.writeValueAsString(detail));
            return;
        }

        if (HttpMethod.POST.equals(request.method()) && matches(path, "/api/monitor/command", "/monitor/command")) {
            CommandRequest commandRequest = MonitorProtocol.MAPPER.readValue(request.content().toString(StandardCharsets.UTF_8), CommandRequest.class);
            var result = registry.dispatchCommand(commandRequest);
            HttpResponseStatus status = result.accepted() ? HttpResponseStatus.OK : HttpResponseStatus.BAD_REQUEST;
            writeJsonResponse(ctx, status, MonitorProtocol.MAPPER.writeValueAsString(result));
            return;
        }

        if (HttpMethod.POST.equals(request.method()) && matches(path, "/api/monitor/alerts/ack", "/monitor/alerts/ack")) {
            AlertAckRequest alertAckRequest = MonitorProtocol.MAPPER.readValue(request.content().toString(StandardCharsets.UTF_8), AlertAckRequest.class);
            var result = registry.acknowledgeAlert(alertAckRequest == null ? null : alertAckRequest.alertId());
            HttpResponseStatus status = result.acknowledged() ? HttpResponseStatus.OK : HttpResponseStatus.BAD_REQUEST;
            writeJsonResponse(ctx, status, MonitorProtocol.MAPPER.writeValueAsString(result));
            return;
        }

        if (HttpMethod.POST.equals(request.method()) && matches(path, "/api/monitor/notifications", "/monitor/notifications")) {
            NotificationConfigUpdate update = MonitorProtocol.MAPPER.readValue(request.content().toString(StandardCharsets.UTF_8), NotificationConfigUpdate.class);
            writeJsonResponse(ctx, HttpResponseStatus.OK, MonitorProtocol.MAPPER.writeValueAsString(registry.updateNotificationTargets(update)));
            return;
        }

        if (HttpMethod.POST.equals(request.method()) && matches(path, "/api/monitor/service/purge", "/monitor/service/purge")) {
            @SuppressWarnings("unchecked")
            Map<String, Object> payload = MonitorProtocol.MAPPER.readValue(request.content().toString(StandardCharsets.UTF_8), Map.class);
            String serverId = MonitorProtocol.stringValue(payload == null ? null : payload.get("serverId"));
            String token = MonitorProtocol.stringValue(payload == null ? null : payload.get("token"));
            if (token.isBlank()) {
                token = MonitorProtocol.stringValue(request.headers().get("X-Monitor-Token"));
            }
            var result = registry.purgeOfflineServiceHistory(serverId, token);
            HttpResponseStatus status = result.accepted() ? HttpResponseStatus.OK : HttpResponseStatus.BAD_REQUEST;
            writeJsonResponse(ctx, status, MonitorProtocol.MAPPER.writeValueAsString(result));
            return;
        }

        writeJsonResponse(
            ctx,
            HttpResponseStatus.NOT_FOUND,
            MonitorProtocol.MAPPER.writeValueAsString(Map.of(
                "message", "Not Found",
                "method", request.method().name(),
                "path", path)));
    }

    private void handleWebSocketFrame(ChannelHandlerContext ctx, String text) throws Exception {
        if ("PING".equalsIgnoreCase(text)) {
            ctx.writeAndFlush(new TextWebSocketFrame("PONG"));
            return;
        }
        if ("SNAPSHOT".equalsIgnoreCase(text)) {
            ctx.writeAndFlush(new TextWebSocketFrame(MonitorProtocol.MAPPER.writeValueAsString(registry.snapshotEvent())));
        }
    }

    private boolean matches(String path, String... candidates) {
        for (String candidate : candidates) {
            if (candidate.equals(path)) {
                return true;
            }
        }
        return false;
    }

    private void writeJsonResponse(ChannelHandlerContext ctx, HttpResponseStatus status, String body) {
        ByteBuf payload = Unpooled.copiedBuffer(body == null ? "" : body, StandardCharsets.UTF_8);
        FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status, payload);
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json; charset=utf-8");
        response.headers().set(HttpHeaderNames.CONTENT_LENGTH, payload.readableBytes());
        response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, "*");
        response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_METHODS, "GET, POST, OPTIONS");
        response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_HEADERS, "Content-Type, X-Monitor-Token");
        response.headers().set(HttpHeaderNames.CACHE_CONTROL, HttpHeaderValues.NO_CACHE);
        ctx.writeAndFlush(response);
    }
}