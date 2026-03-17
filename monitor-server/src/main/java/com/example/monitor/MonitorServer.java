package com.example.monitor;

import java.time.Duration;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import com.example.monitor.handler.MonitorHttpWebSocketHandler;
import com.example.monitor.handler.MonitorTcpHandler;
import com.example.monitor.registry.MonitorRegistry;
import com.example.monitor.store.JdbcHistoryStore;
import com.example.monitor.store.MonitorPersistenceConfig;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.LineBasedFrameDecoder;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.handler.codec.string.StringDecoder;
import io.netty.handler.codec.string.StringEncoder;
import io.netty.util.concurrent.DefaultEventExecutorGroup;
import io.netty.util.concurrent.EventExecutorGroup;

public class MonitorServer {
    private static final long HEARTBEAT_TIMEOUT_MS = 90_000L;
    private static final Duration HISTORY_RETENTION = Duration.ofDays(7);

    public static void main(String[] args) throws InterruptedException {
        int tcpPort = parseInt(args, 0, 9999);
        int httpPort = parseInt(args, 1, 9091);
        String controlToken = args.length > 2 && args[2] != null && !args[2].isBlank()
                ? args[2]
                : Optional.ofNullable(System.getenv("MONITOR_CONTROL_TOKEN")).filter(token -> !token.isBlank()).orElse("monitor-dev-token");

        MonitorPersistenceConfig persistenceConfig = MonitorPersistenceConfig.fromEnvironment();
        JdbcHistoryStore jdbcHistoryStore = new JdbcHistoryStore(persistenceConfig);
        MonitorRegistry registry = new MonitorRegistry(jdbcHistoryStore);
        registry.setControlToken(controlToken);

        EventExecutorGroup businessGroup = new DefaultEventExecutorGroup(Math.max(4, Runtime.getRuntime().availableProcessors()));
        EventLoopGroup bossGroup = new NioEventLoopGroup(1);
        EventLoopGroup workerGroup = new NioEventLoopGroup();
        Channel tcpChannel = null;
        Channel httpChannel = null;

        try {
            tcpChannel = startTcpServer(bossGroup, workerGroup, businessGroup, registry, tcpPort);
            httpChannel = startHttpServer(bossGroup, workerGroup, businessGroup, registry, httpPort);
            scheduleMaintenance(workerGroup, registry, jdbcHistoryStore);
            System.out.println("Monitor TCP server started on port " + tcpPort);
            System.out.println("Monitor HTTP API started on port " + httpPort);
            tcpChannel.closeFuture().sync();
            httpChannel.closeFuture().sync();
        } finally {
            if (tcpChannel != null) {
                tcpChannel.close();
            }
            if (httpChannel != null) {
                httpChannel.close();
            }
            bossGroup.shutdownGracefully();
            workerGroup.shutdownGracefully();
            businessGroup.shutdownGracefully();
            jdbcHistoryStore.close();
        }
    }

    private static int parseInt(String[] args, int index, int fallback) {
        if (args.length <= index) {
            return fallback;
        }
        try {
            return Integer.parseInt(args[index]);
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private static Channel startTcpServer(EventLoopGroup bossGroup,
                                          EventLoopGroup workerGroup,
                                          EventExecutorGroup businessGroup,
                                          MonitorRegistry registry,
                                          int tcpPort) throws InterruptedException {
        ServerBootstrap bootstrap = new ServerBootstrap();
        bootstrap.group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .childOption(ChannelOption.SO_KEEPALIVE, true)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline().addLast(new LineBasedFrameDecoder(65_536));
                        ch.pipeline().addLast(new StringDecoder(StandardCharsets.UTF_8));
                        ch.pipeline().addLast(new StringEncoder(StandardCharsets.UTF_8));
                        ch.pipeline().addLast(businessGroup, new MonitorTcpHandler(registry));
                    }
                });
        return bootstrap.bind(tcpPort).sync().channel();
    }

    private static Channel startHttpServer(EventLoopGroup bossGroup,
                                           EventLoopGroup workerGroup,
                                           EventExecutorGroup businessGroup,
                                           MonitorRegistry registry,
                                           int httpPort) throws InterruptedException {
        ServerBootstrap bootstrap = new ServerBootstrap();
        bootstrap.group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .childOption(ChannelOption.SO_KEEPALIVE, true)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline().addLast(new HttpServerCodec());
                        ch.pipeline().addLast(new HttpObjectAggregator(1_048_576));
                        ch.pipeline().addLast(new WebSocketServerProtocolHandler("/ws/monitor", null, true));
                        ch.pipeline().addLast(businessGroup, new MonitorHttpWebSocketHandler(registry));
                    }
                });
        return bootstrap.bind(httpPort).sync().channel();
    }

    private static void scheduleMaintenance(EventLoopGroup workerGroup, MonitorRegistry registry, JdbcHistoryStore jdbcHistoryStore) {
        workerGroup.scheduleAtFixedRate(() -> registry.markTimeouts(HEARTBEAT_TIMEOUT_MS), 10, 10, TimeUnit.SECONDS);
        workerGroup.scheduleAtFixedRate(
                () -> jdbcHistoryStore.deleteHistoryBefore(Instant.now().minus(HISTORY_RETENTION)),
                1,
                24,
                TimeUnit.HOURS);
    }
}
