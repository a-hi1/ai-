package com.example.ecommerce.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.time.Instant;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.example.ecommerce.model.Product;
import com.example.ecommerce.repository.ProductRepository;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Service
public class ProductVectorStoreService {
    private static final int VECTOR_SIZE = 256;
        private static final Map<String, List<String>> QUERY_ALIASES = Map.ofEntries(
            Map.entry("耳机", List.of("headphone", "audio", "降噪", "耳麦", "buds", "freebuds", "airpods", "xm5")),
            Map.entry("生活用品", List.of("日用品", "家居", "家纺", "纸品", "抽纸", "纸巾", "清洁", "洗衣", "凝珠", "日化", "床品", "四件套", "枕头", "厨房", "厨具", "锅具", "炒锅", "净饮", "湿巾", "洁面", "护肤", "洗护")),
            Map.entry("笔记本", List.of("laptop", "电脑", "轻薄本", "notebook", "ultrabook", "办公本", "游戏本")),
            Map.entry("充电器", List.of("charger", "快充", "氮化镓", "充电头", "电源")),
            Map.entry("手表", List.of("watch", "wearable")),
            Map.entry("手环", List.of("band", "wearable", "fitness")),
            Map.entry("键盘", List.of("keyboard", "机械键盘")),
            Map.entry("游戏机", List.of("switch", "console", "掌机")),
            Map.entry("宠物", List.of("猫粮", "猫咪", "狗粮", "狗狗", "宠物包", "猫砂")),
            Map.entry("卫衣", List.of("hoodie", "sweatshirt", "连帽", "加绒")),
            Map.entry("T恤", List.of("t shirt", "tee", "短袖", "纯棉")),
            Map.entry("夹克", List.of("jacket", "外套", "防风", "冲锋")),
            Map.entry("运动鞋", List.of("sneaker", "running", "trainer", "跑鞋")),
            Map.entry("背包", List.of("backpack", "双肩包", "通勤包")),
            Map.entry("数码办公", List.of("办公", "数码", "键鼠", "显示器", "电脑")),
            Map.entry("智能穿戴", List.of("穿戴", "手环", "手表", "健康监测")));

    private final ProductRepository productRepository;
    private final R2dbcEntityTemplate entityTemplate;
    private final AtomicReference<CatalogSnapshot> snapshotRef = new AtomicReference<>(CatalogSnapshot.empty());

    public ProductVectorStoreService(ProductRepository productRepository, R2dbcEntityTemplate entityTemplate) {
        this.productRepository = productRepository;
        this.entityTemplate = entityTemplate;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void warmUpCatalog() {
        refreshCatalog().subscribe();
    }

    @Scheduled(fixedDelayString = "${ai.catalog.refresh-interval-ms:15000}", initialDelayString = "${ai.catalog.refresh-initial-delay-ms:5000}")
    public void refreshCatalogPeriodically() {
        refreshCatalog().subscribe();
    }

    public Mono<CatalogStats> refreshCatalog() {
        return productRepository.findAll()
                .collectList()
                .map(this::buildSnapshot)
                .doOnNext(snapshotRef::set)
                .map(CatalogSnapshot::toStats);
    }

    public Mono<CatalogStats> getCatalogStats() {
        CatalogSnapshot snapshot = snapshotRef.get();
        if (snapshot.isEmpty()) {
            return refreshCatalog();
        }
        return Mono.just(snapshot.toStats());
    }

    public Mono<Product> syncProduct(Product product) {
        if (product == null || product.getId() == null) {
            return Mono.just(product);
        }

        if (snapshotRef.get().isEmpty()) {
            return refreshCatalog().thenReturn(product);
        }

        snapshotRef.updateAndGet(snapshot -> snapshot.withProduct(index(product)));
        return Mono.just(product);
    }

    public Flux<Product> search(String query, int limit) {
        int safeLimit = Math.max(1, limit);
        if (query != null && !query.isBlank()) {
            int recallLimit = Math.max(safeLimit * 4, safeLimit + 10);
            return recallFromVectorTable(query, recallLimit)
                    .collectList()
                    .flatMapMany(candidates -> {
                        if (candidates.isEmpty()) {
                            return searchFromSnapshot(query, safeLimit);
                        }
                        return Flux.fromIterable(rerankCandidates(query, candidates, safeLimit));
                    })
                    .switchIfEmpty(searchFromSnapshot(query, safeLimit));
        }

        return searchFromSnapshot(query, safeLimit);
    }

    private Flux<Product> searchFromSnapshot(String query, int limit) {
        int safeLimit = Math.max(1, limit);
        CatalogSnapshot snapshot = snapshotRef.get();
        if (snapshot.isEmpty()) {
            return productRepository.findAll()
                    .collectList()
                    .flatMapMany(products -> {
                        CatalogSnapshot loaded = buildSnapshot(products);
                        snapshotRef.set(loaded);
                        return Flux.fromIterable(searchSnapshot(loaded, query, safeLimit))
                                .map(IndexedProduct::product);
                    });
        }

        return Flux.fromIterable(searchSnapshot(snapshot, query, safeLimit))
                .map(IndexedProduct::product);
    }

    private Flux<Product> recallFromVectorTable(String query, int limit) {
        int safeLimit = Math.max(1, limit);
        String sql = """
                SELECT p.*
                FROM products p
                JOIN (
                  SELECT pv.product_id, MAX(pv.updated_at) AS latest_time
                  FROM product_vectors pv
                        WHERE LOWER(pv.chunk_text) LIKE LOWER(CONCAT('%%', :q, '%%'))
                            OR LOWER(pv.metadata_json) LIKE LOWER(CONCAT('%%', :q, '%%'))
                  GROUP BY pv.product_id
                  ORDER BY latest_time DESC
                  LIMIT %d
                ) recall ON recall.product_id = p.id
                ORDER BY recall.latest_time DESC
                """.formatted(safeLimit);

        return entityTemplate.getDatabaseClient()
                .sql(sql)
                .bind("q", query)
                .map((row, metadata) -> {
                    Product product = new Product();
                    product.setId(row.get("id", UUID.class));
                    product.setName(row.get("name", String.class));
                    product.setDescription(row.get("description", String.class));
                    product.setPrice(row.get("price", java.math.BigDecimal.class));
                    product.setImageUrl(row.get("image_url", String.class));
                    product.setTags(row.get("tags", String.class));
                    product.setCategory(row.get("category", String.class));
                    product.setSpecs(row.get("specs", String.class));
                    product.setSellingPoints(row.get("selling_points", String.class));
                    product.setPolicy(row.get("policy", String.class));
                    product.setSourceProductId(row.get("source_product_id", String.class));
                    product.setDataSource(row.get("data_source", String.class));
                    product.setCreatedAt(toInstant(row.get("created_at")));
                    product.setUpdatedAt(toInstant(row.get("updated_at")));
                    return product;
                })
                .all();
    }

    private List<Product> rerankCandidates(String query, List<Product> candidates, int limit) {
        String expandedQuery = expandAliases(query);
        String normalizedQuery = normalize(expandedQuery);
        float[] queryVector = vectorize(expandedQuery);
        Set<String> queryTokens = tokenize(expandedQuery);

        return candidates.stream()
                .filter(product -> product != null && product.getId() != null)
                .map(this::index)
                .map(indexed -> new ScoredProduct(indexed, score(normalizedQuery, queryVector, queryTokens, indexed)))
                .sorted(Comparator.comparingDouble(ScoredProduct::score).reversed())
                .limit(Math.max(1, limit))
                .map(scored -> scored.product().product())
                .toList();
    }

    public double relevanceScore(String query, Product product) {
        if (product == null) {
            return 0.0d;
        }

        CatalogSnapshot snapshot = snapshotRef.get();
        IndexedProduct indexedProduct = snapshot.productsById().get(product.getId());
        if (indexedProduct == null) {
            indexedProduct = index(product);
        }

        if (query == null || query.isBlank()) {
            return 0.0d;
        }

        String expandedQuery = expandAliases(query);
        String normalizedQuery = normalize(expandedQuery);
        float[] queryVector = vectorize(expandedQuery);
        Set<String> queryTokens = tokenize(expandedQuery);
        return score(normalizedQuery, queryVector, queryTokens, indexedProduct);
    }

    private List<IndexedProduct> searchSnapshot(CatalogSnapshot snapshot, String query, int limit) {
        if (query == null || query.isBlank()) {
            return snapshot.products().stream()
                    .sorted(Comparator.comparing((IndexedProduct product) -> product.product().getCreatedAt(), Comparator.nullsLast(Comparator.reverseOrder())))
                    .limit(limit)
                    .toList();
        }

        String expandedQuery = expandAliases(query);
        String normalizedQuery = normalize(expandedQuery);
        float[] queryVector = vectorize(expandedQuery);
        Set<String> queryTokens = tokenize(expandedQuery);

        return snapshot.products().stream()
            .map(product -> new ScoredProduct(product, score(normalizedQuery, queryVector, queryTokens, product)))
                .sorted(Comparator.comparingDouble(ScoredProduct::score).reversed())
                .limit(limit)
                .map(ScoredProduct::product)
                .toList();
    }

    private CatalogSnapshot buildSnapshot(List<Product> products) {
        List<IndexedProduct> indexedProducts = products.stream()
                .map(this::index)
                .sorted(Comparator.comparing((IndexedProduct product) -> product.product().getCreatedAt(), Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();

        Map<UUID, IndexedProduct> productsById = new HashMap<>();
        for (IndexedProduct indexedProduct : indexedProducts) {
            productsById.put(indexedProduct.product().getId(), indexedProduct);
        }

        return new CatalogSnapshot(indexedProducts, productsById);
    }

    private IndexedProduct index(Product product) {
        String content = searchableContent(product);
        return new IndexedProduct(product, content, tokenize(content), vectorize(content));
    }

    private String searchableContent(Product product) {
        String name = safe(product.getName());
        String description = safe(product.getDescription());
        String tags = safe(product.getTags());
        String category = safe(product.getCategory());
        String specs = safe(product.getSpecs());
        String sellingPoints = safe(product.getSellingPoints());
        String policy = safe(product.getPolicy());
        String productIdMeta = "product_id=" + safe(product.getId() == null ? "" : product.getId().toString());
        Instant updateTime = product.getUpdatedAt() == null ? product.getCreatedAt() : product.getUpdatedAt();
        String updateTimeMeta = "update_time=" + safe(updateTime == null ? "" : updateTime.toString());
        String aliases = expandAliases(String.join(" ", name, tags, description, category, specs, sellingPoints, policy));
        return String.join(" ",
                name,
                name,
                name,
            category,
                tags,
                tags,
                description,
            specs,
            sellingPoints,
            policy,
            productIdMeta,
            updateTimeMeta,
                aliases,
                safe(product.getDataSource()));
    }

    private double score(String normalizedQuery, float[] queryVector, Set<String> queryTokens, IndexedProduct product) {
        double cosine = cosine(queryVector, product.vector());
        double lexical = lexicalScore(queryTokens, product.tokens());
        double exact = containsNormalizedPhrase(product.content(), normalizedQuery) ? 1.0d : 0.0d;
        double coverage = keywordCoverage(queryTokens, product.tokens());
        double fieldBoost = fieldMatchBoost(queryTokens, product);
        return cosine * 0.42d + lexical * 0.21d + exact * 0.19d + coverage * 0.10d + fieldBoost * 0.08d;
    }

    private double fieldMatchBoost(Set<String> queryTokens, IndexedProduct indexedProduct) {
        if (queryTokens.isEmpty()) {
            return 0.0d;
        }

        Product product = indexedProduct.product();
        String name = normalize(product.getName());
        String category = normalize(product.getCategory());
        String tags = normalize(product.getTags());
        String sellingPoints = normalize(product.getSellingPoints());

        double score = 0.0d;
        for (String token : queryTokens) {
            if (token.length() < 2) {
                continue;
            }
            if (name.contains(token)) {
                score += 1.0d;
            }
            if (category.contains(token)) {
                score += 0.8d;
            }
            if (tags.contains(token)) {
                score += 0.6d;
            }
            if (sellingPoints.contains(token)) {
                score += 0.4d;
            }
        }
        return Math.min(score / Math.max(1.0d, queryTokens.size()), 1.0d);
    }

    private double lexicalScore(Set<String> queryTokens, Set<String> productTokens) {
        if (queryTokens.isEmpty() || productTokens.isEmpty()) {
            return 0.0d;
        }

        long matches = queryTokens.stream().filter(productTokens::contains).count();
        return (double) matches / queryTokens.size();
    }

    private double keywordCoverage(Set<String> queryTokens, Set<String> productTokens) {
        if (queryTokens.isEmpty() || productTokens.isEmpty()) {
            return 0.0d;
        }

        long covered = queryTokens.stream()
                .filter(token -> productTokens.stream().anyMatch(productToken -> productToken.contains(token) || token.contains(productToken)))
                .count();
        return (double) covered / queryTokens.size();
    }

    private boolean containsNormalizedPhrase(String content, String normalizedQuery) {
        if (normalizedQuery == null || normalizedQuery.isBlank()) {
            return false;
        }
        String normalizedContent = normalize(content).replace(" ", "");
        String compactQuery = normalizedQuery.replace(" ", "");
        return !compactQuery.isBlank() && normalizedContent.contains(compactQuery);
    }

    private float[] vectorize(String text) {
        float[] vector = new float[VECTOR_SIZE];
        String expanded = expandAliases(text);
        List<String> features = new ArrayList<>(tokenize(expanded));
        String normalized = normalize(expanded);

        for (int index = 0; index < normalized.length() - 1; index++) {
            String bigram = normalized.substring(index, index + 2).trim();
            if (!bigram.isBlank()) {
                features.add(bigram);
            }
        }

        for (int index = 0; index < normalized.length() - 2; index++) {
            String trigram = normalized.substring(index, index + 3).trim();
            if (!trigram.isBlank()) {
                features.add(trigram);
            }
        }

        for (String feature : features) {
            int bucket = Math.abs(feature.hashCode()) % VECTOR_SIZE;
            vector[bucket] += 1.0f;
        }

        float norm = 0.0f;
        for (float value : vector) {
            norm += value * value;
        }
        norm = (float) Math.sqrt(norm);
        if (norm == 0.0f) {
            return vector;
        }
        for (int index = 0; index < vector.length; index++) {
            vector[index] = vector[index] / norm;
        }
        return vector;
    }

    private double cosine(float[] left, float[] right) {
        double dot = 0.0d;
        for (int index = 0; index < left.length; index++) {
            dot += left[index] * right[index];
        }
        return dot;
    }

    private Set<String> tokenize(String value) {
        String normalized = normalize(value);
        Set<String> tokens = new LinkedHashSet<>();
        for (String token : normalized.split("\\s+")) {
            if (!token.isBlank()) {
                tokens.add(token);
            }
        }
        String compact = normalized.replace(" ", "");
        for (int index = 0; index < compact.length() - 1; index++) {
            String bigram = compact.substring(index, index + 2).trim();
            if (!bigram.isBlank()) {
                tokens.add(bigram);
            }
        }
        return tokens;
    }

    private String expandAliases(String value) {
        String normalized = normalize(value);
        StringBuilder builder = new StringBuilder(normalized);
        QUERY_ALIASES.forEach((keyword, aliases) -> {
            if (normalized.contains(keyword) || aliases.stream().anyMatch(normalized::contains)) {
                builder.append(' ').append(keyword);
                aliases.forEach(alias -> builder.append(' ').append(alias));
            }
        });
        return builder.toString();
    }

    private String normalize(String value) {
        return safe(value)
                .toLowerCase(Locale.ROOT)
                .replace('-', ' ')
                .replace('_', ' ')
                .replace(',', ' ')
                .replace('，', ' ')
                .replace('。', ' ')
                .replace('、', ' ')
                .trim();
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private Instant toInstant(Object value) {
        if (value instanceof Instant instant) {
            return instant;
        }
        if (value instanceof java.time.LocalDateTime localDateTime) {
            return localDateTime.atZone(java.time.ZoneId.systemDefault()).toInstant();
        }
        return null;
    }

    public record CatalogStats(int productCount, List<Product> latestProducts) {
    }

    private record CatalogSnapshot(List<IndexedProduct> products, Map<UUID, IndexedProduct> productsById) {
        private static CatalogSnapshot empty() {
            return new CatalogSnapshot(List.of(), Map.of());
        }

        private boolean isEmpty() {
            return products.isEmpty();
        }

        private CatalogStats toStats() {
            return new CatalogStats(products.size(), products.stream().limit(5).map(IndexedProduct::product).toList());
        }

        private CatalogSnapshot withProduct(IndexedProduct product) {
            Map<UUID, IndexedProduct> updated = new HashMap<>(productsById);
            updated.put(product.product().getId(), product);
            List<IndexedProduct> indexedProducts = updated.values().stream()
                    .sorted(Comparator.comparing((IndexedProduct item) -> item.product().getCreatedAt(), Comparator.nullsLast(Comparator.reverseOrder())))
                    .toList();
            return new CatalogSnapshot(indexedProducts, updated);
        }
    }

    private record IndexedProduct(Product product, String content, Set<String> tokens, float[] vector) {
    }

    private record ScoredProduct(IndexedProduct product, double score) {
    }
}