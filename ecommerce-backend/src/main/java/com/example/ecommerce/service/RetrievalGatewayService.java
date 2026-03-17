package com.example.ecommerce.service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.example.ecommerce.model.Product;

import reactor.core.publisher.Flux;

@Service
public class RetrievalGatewayService {
    private final ProductVectorStoreService productVectorStoreService;

    public RetrievalGatewayService(ProductVectorStoreService productVectorStoreService) {
        this.productVectorStoreService = productVectorStoreService;
    }

    public Flux<RetrievalChunk> search(String query, int limit, String categoryFilter, BigDecimal maxPrice) {
        int safeLimit = Math.max(1, limit);
        String normalizedCategory = normalize(categoryFilter);
        int candidateLimit = Math.max(safeLimit * 4, safeLimit + 8);

        return productVectorStoreService.search(query, candidateLimit)
                .filter(product -> categoryMatches(product, normalizedCategory))
                .filter(product -> priceMatches(product, maxPrice))
                .map(product -> toChunk(query, product))
                .sort(Comparator.comparingDouble(RetrievalChunk::score).reversed())
                .take(safeLimit);
    }

    public double relevanceScore(String query, Product product) {
        return productVectorStoreService.relevanceScore(query, product);
    }

    private RetrievalChunk toChunk(String query, Product product) {
        Instant updateTime = product.getUpdatedAt() == null ? product.getCreatedAt() : product.getUpdatedAt();
        double semanticScore = productVectorStoreService.relevanceScore(query, product);
        double keywordScore = keywordOverlapScore(query, product);
        double freshness = freshnessBoost(updateTime);
        double score = semanticScore * 0.72d + keywordScore * 0.18d + freshness * 0.10d;
        String chunk = buildChunk(product, updateTime);
        return new RetrievalChunk(
                product.getId(),
                product.getName(),
                product.getCategory(),
                product.getDataSource(),
                product.getPrice(),
                updateTime,
                chunk,
                score,
                product);
    }

    private double keywordOverlapScore(String query, Product product) {
        Set<String> queryTokens = tokenize(query);
        if (queryTokens.isEmpty()) {
            return 0.0d;
        }
        String content = normalize(String.join(" ",
                safe(product.getName()),
                safe(product.getCategory()),
                safe(product.getTags()),
                safe(product.getSellingPoints())));
        long hits = queryTokens.stream().filter(content::contains).count();
        return (double) hits / queryTokens.size();
    }

    private Set<String> tokenize(String value) {
        return Pattern.compile("[\\s,，。！？!?:：/\\-]+")
                .splitAsStream(normalize(value))
                .map(String::trim)
                .filter(token -> token.length() >= 2)
                .collect(java.util.stream.Collectors.toSet());
    }

    private double freshnessBoost(Instant updateTime) {
        if (updateTime == null) {
            return 0.0d;
        }
        long hours = Math.max(0L, Duration.between(updateTime, Instant.now()).toHours());
        if (hours <= 24L) {
            return 1.0d;
        }
        if (hours <= 24L * 7L) {
            return 0.6d;
        }
        return 0.2d;
    }

    private String buildChunk(Product product, Instant updateTime) {
        String policy = blankToDefault(product.getPolicy(), "官方正品，7天无理由");
        String sellingPoints = blankToDefault(product.getSellingPoints(), abbreviate(product.getDescription(), 160));
        String specs = blankToDefault(product.getSpecs(), "规格：标准款");
        String category = blankToDefault(product.getCategory(), "unknown");
        String source = blankToDefault(product.getDataSource(), "MANUAL");
        String imageUrl = blankToDefault(product.getImageUrl(), "");

        return String.join("\n",
                "product_id=" + safe(product.getId() == null ? "" : product.getId().toString()),
                "update_time=" + safe(updateTime == null ? "" : updateTime.toString()),
                "title=" + safe(product.getName()),
                "category=" + category,
                "price=" + safe(product.getPrice() == null ? "" : product.getPrice().toPlainString()) + "元",
                "specs=" + specs,
                "selling_points=" + sellingPoints,
                "policy=" + policy,
                "source=" + source,
                "image_url=" + imageUrl);
    }

    private boolean categoryMatches(Product product, String normalizedCategory) {
        if (normalizedCategory.isBlank()) {
            return true;
        }
        String category = normalize(product.getCategory());
        return category.contains(normalizedCategory);
    }

    private boolean priceMatches(Product product, BigDecimal maxPrice) {
        if (maxPrice == null || product.getPrice() == null) {
            return true;
        }
        return product.getPrice().compareTo(maxPrice) <= 0;
    }

    private String normalize(String value) {
        return safe(value).toLowerCase(Locale.ROOT).trim();
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private String blankToDefault(String value, String fallback) {
        String normalized = safe(value).trim();
        return normalized.isBlank() ? fallback : normalized;
    }

    private String abbreviate(String value, int maxLength) {
        String normalized = safe(value).replaceAll("\\s+", " ").trim();
        if (normalized.length() <= maxLength) {
            return normalized;
        }
        return normalized.substring(0, maxLength) + "...";
    }

    public record RetrievalChunk(
            UUID productId,
            String title,
            String category,
            String source,
            BigDecimal price,
            Instant updateTime,
            String chunk,
            double score,
            Product product) {
    }
}
