package com.example.ecommerce.service;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StreamUtils;

import com.example.ecommerce.dto.DataAdminActionResponse;
import com.example.ecommerce.dto.DataAdminOverviewResponse;
import com.example.ecommerce.dto.DataAdminStatsResponse;
import com.example.ecommerce.dto.ManagedProductResponse;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Service
public class DataAdminService {
    private static final String DEMO_EMAIL = "demo@aishop.local";
    private static final String SAMPLE_RESOURCE = "classpath:sample-data.sql";

    private final R2dbcEntityTemplate entityTemplate;
    private final ResourceLoader resourceLoader;

    public DataAdminService(R2dbcEntityTemplate entityTemplate, ResourceLoader resourceLoader) {
        this.entityTemplate = entityTemplate;
        this.resourceLoader = resourceLoader;
    }

    public Mono<DataAdminOverviewResponse> getOverview() {
        Mono<Long> totalProducts = count("SELECT COUNT(*) FROM products");
        Mono<Long> sampleProducts = count("SELECT COUNT(*) FROM products WHERE COALESCE(data_source, 'MANUAL') = 'SAMPLE'");
        Mono<Long> crawlerProducts = count("SELECT COUNT(*) FROM products WHERE COALESCE(data_source, 'MANUAL') = 'CRAWLER'");
        Mono<Long> manualProducts = count("SELECT COUNT(*) FROM products WHERE COALESCE(data_source, 'MANUAL') = 'MANUAL'");
        Mono<Long> userCount = count("SELECT COUNT(*) FROM users");
        Mono<Long> orderCount = count("SELECT COUNT(*) FROM orders");
        Mono<Long> cartItemCount = count("SELECT COUNT(*) FROM cart_items");
        Mono<Long> chatMessageCount = count("SELECT COUNT(*) FROM chat_messages");
        Mono<Long> productViewCount = count("SELECT COUNT(*) FROM product_views");

        Mono<List<ManagedProductResponse>> recentProducts = entityTemplate.getDatabaseClient()
                .sql("SELECT id, name, price, COALESCE(data_source, 'MANUAL') AS data_source, created_at FROM products ORDER BY created_at DESC LIMIT 12")
                .map((row, metadata) -> new ManagedProductResponse(
                        row.get("id", UUID.class),
                        row.get("name", String.class),
                        row.get("price", BigDecimal.class),
                        row.get("data_source", String.class),
                        toInstant(row.get("created_at"))))
                .all()
                .collectList();

        return Mono.zip(objects -> new DataAdminOverviewResponse(
                new DataAdminStatsResponse(
                    (Long) objects[0],
                    (Long) objects[1],
                    (Long) objects[2],
                    (Long) objects[3],
                    (Long) objects[4],
                    (Long) objects[5],
                    (Long) objects[6],
                    (Long) objects[7],
                    (Long) objects[8]),
                (List<ManagedProductResponse>) objects[9]),
            totalProducts,
            sampleProducts,
            crawlerProducts,
            manualProducts,
            userCount,
            orderCount,
            cartItemCount,
            chatMessageCount,
            productViewCount,
            recentProducts);
    }

    public Mono<DataAdminActionResponse> clear(String scope) {
        String normalizedScope = normalizeScope(scope);
        Mono<Void> action = switch (normalizedScope) {
            case "SAMPLE" -> clearSampleData();
            case "CRAWLER" -> clearCrawlerData();
            case "ALL" -> clearAllData();
            default -> Mono.error(new IllegalArgumentException("Unsupported scope: " + scope));
        };

        String message = switch (normalizedScope) {
            case "SAMPLE" -> "示例数据已清空";
            case "CRAWLER" -> "爬虫数据已清空";
            default -> "全部业务数据已清空";
        };

        return action.then(getOverview()).map(overview -> new DataAdminActionResponse(message, overview));
    }

    public Mono<DataAdminActionResponse> rebuildSampleData() {
        return clearSampleData()
                .then(executeSqlScript(SAMPLE_RESOURCE))
                .then(getOverview())
                .map(overview -> new DataAdminActionResponse("示例数据已重建", overview));
    }

    private Mono<Void> clearSampleData() {
        return Flux.concat(
                execute("DELETE FROM order_items WHERE order_id IN (SELECT id FROM orders WHERE user_id IN (SELECT id FROM users WHERE email = 'demo@aishop.local'))"),
                execute("DELETE FROM cart_items WHERE user_id IN (SELECT id FROM users WHERE email = 'demo@aishop.local') OR product_id IN (SELECT id FROM products WHERE COALESCE(data_source, 'MANUAL') = 'SAMPLE')"),
                execute("DELETE FROM product_views WHERE user_id IN (SELECT id FROM users WHERE email = 'demo@aishop.local') OR product_id IN (SELECT id FROM products WHERE COALESCE(data_source, 'MANUAL') = 'SAMPLE')"),
                execute("DELETE FROM product_vectors WHERE product_id IN (SELECT id FROM products WHERE COALESCE(data_source, 'MANUAL') = 'SAMPLE')"),
                execute("DELETE FROM chat_messages WHERE user_id IN (SELECT id FROM users WHERE email = 'demo@aishop.local')"),
                execute("DELETE FROM orders WHERE user_id IN (SELECT id FROM users WHERE email = 'demo@aishop.local')"),
                execute("DELETE FROM users WHERE email = 'demo@aishop.local'"),
                execute("DELETE FROM products WHERE COALESCE(data_source, 'MANUAL') = 'SAMPLE'"))
                .then();
    }

    private Mono<Void> clearCrawlerData() {
        return Flux.concat(
                execute("DELETE FROM cart_items WHERE product_id IN (SELECT id FROM products WHERE COALESCE(data_source, 'MANUAL') = 'CRAWLER')"),
                execute("DELETE FROM product_views WHERE product_id IN (SELECT id FROM products WHERE COALESCE(data_source, 'MANUAL') = 'CRAWLER')"),
                execute("DELETE FROM product_vectors WHERE source = 'CRAWLER' OR product_id IN (SELECT id FROM products WHERE COALESCE(data_source, 'MANUAL') = 'CRAWLER')"),
                execute("DELETE FROM products WHERE COALESCE(data_source, 'MANUAL') = 'CRAWLER'"))
                .then();
    }

    private Mono<Void> clearAllData() {
        return Flux.concat(
                execute("DELETE FROM order_items"),
                execute("DELETE FROM orders"),
                execute("DELETE FROM cart_items"),
                execute("DELETE FROM product_views"),
                execute("DELETE FROM chat_messages"),
                execute("DELETE FROM product_vectors"),
                execute("DELETE FROM products"),
                execute("DELETE FROM users"),
                execute("DELETE FROM service_nodes"))
                .then();
    }

    private Mono<Void> executeSqlScript(String resourceLocation) {
        return Mono.fromCallable(() -> loadStatements(resourceLocation))
                .flatMapMany(Flux::fromIterable)
                .concatMap(this::execute)
                .then();
    }

    private List<String> loadStatements(String resourceLocation) throws IOException {
        Resource resource = resourceLoader.getResource(resourceLocation);
        String script;
        try (var inputStream = resource.getInputStream()) {
            script = StreamUtils.copyToString(inputStream, StandardCharsets.UTF_8);
        }

        return Arrays.stream(script.split(";"))
                .map(String::trim)
                .filter(statement -> !statement.isBlank())
                .toList();
    }

    private Mono<Void> execute(String sql) {
        return entityTemplate.getDatabaseClient()
                .sql(sql)
                .fetch()
                .rowsUpdated()
                .then();
    }

    private Mono<Long> count(String sql) {
        return entityTemplate.getDatabaseClient()
                .sql(sql)
                .map((row, metadata) -> {
                    Object value = row.get(0);
                    return value instanceof Number number ? number.longValue() : 0L;
                })
                .one()
                .defaultIfEmpty(0L);
    }

    private String normalizeScope(String scope) {
        if (scope == null || scope.isBlank()) {
            return "SAMPLE";
        }
        return scope.trim().toUpperCase(Locale.ROOT);
    }

    private Instant toInstant(Object value) {
        if (value instanceof Instant instant) {
            return instant;
        }
        if (value instanceof LocalDateTime localDateTime) {
            return localDateTime.atZone(ZoneId.systemDefault()).toInstant();
        }
        return null;
    }
}