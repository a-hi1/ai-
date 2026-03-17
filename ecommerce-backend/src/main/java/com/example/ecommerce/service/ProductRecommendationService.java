package com.example.ecommerce.service;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.example.ecommerce.dto.ChatRecommendationResponse;
import com.example.ecommerce.model.OrderItem;
import com.example.ecommerce.model.Product;
import com.example.ecommerce.repository.OrderItemRepository;
import com.example.ecommerce.repository.ProductRepository;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Service
public class ProductRecommendationService {
    private static final Map<String, List<String>> CATEGORY_ALIASES = Map.of(
            "audio", List.of("耳机", "headphone", "audio", "降噪", "耳麦", "buds", "freebuds", "airpods", "xm5"),
            "life", List.of("日用", "家居", "家纺", "纸品", "抽纸", "纸巾", "清洁", "洗衣", "凝珠", "日化", "床品", "四件套", "枕头", "厨房", "厨具", "锅具", "炒锅", "净饮", "湿巾", "洁面", "护肤", "洗护"),
            "laptop", List.of("笔记本", "laptop", "轻薄本", "电脑"),
            "charger", List.of("充电器", "charger", "快充", "氮化镓", "充电头", "电源"),
            "watch", List.of("手表", "watch"),
            "band", List.of("手环", "band"),
            "keyboard", List.of("键盘", "keyboard"),
            "console", List.of("游戏机", "console", "switch", "掌机"),
            "pet", List.of("宠物", "猫粮", "猫咪", "狗粮", "狗狗", "宠物包", "猫砂"));

    private final ProductRepository productRepository;
    private final OrderItemRepository orderItemRepository;

    public ProductRecommendationService(ProductRepository productRepository,
                                        OrderItemRepository orderItemRepository) {
        this.productRepository = productRepository;
        this.orderItemRepository = orderItemRepository;
    }

    public Mono<List<ChatRecommendationResponse>> recommendRelatedProducts(List<Product> baseProducts, int limit) {
        if (baseProducts == null || baseProducts.isEmpty()) {
            return Mono.just(List.of());
        }

        Set<UUID> excludedIds = baseProducts.stream()
                .map(Product::getId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<String> primaryTags = baseProducts.stream()
                .flatMap(product -> parseTags(product.getTags()).stream())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<String> primaryFamilies = inferCategoryFamilies(baseProducts);
        BigDecimal averagePrice = averagePrice(baseProducts);

        return Mono.zip(productRepository.findAll().collectList(), orderItemRepository.findAll().collectList())
                .map(tuple -> {
                    List<Product> allProducts = tuple.getT1();
                    Map<UUID, Integer> sales = aggregateSales(tuple.getT2());

                    return allProducts.stream()
                            .filter(product -> product.getId() != null && !excludedIds.contains(product.getId()))
                            .map(product -> new RankedProduct(product, rank(primaryTags, primaryFamilies, averagePrice, product, sales.getOrDefault(product.getId(), 0)), sales.getOrDefault(product.getId(), 0)))
                            .filter(ranked -> ranked.score() > 0.20d)
                            .sorted(Comparator.comparingDouble(RankedProduct::score).reversed())
                            .limit(limit)
                            .map(ranked -> toResponse(ranked.product(), ranked.salesCount(), averagePrice, primaryTags, primaryFamilies))
                            .toList();
                });
    }

    public Mono<List<ChatRecommendationResponse>> recommendForProduct(UUID productId, int limit) {
        return productRepository.findById(productId)
                .flatMap(product -> recommendRelatedProducts(List.of(product), limit))
                .defaultIfEmpty(List.of());
    }

    private double rank(Set<String> primaryTags, Set<String> primaryFamilies, BigDecimal averagePrice, Product product, int salesCount) {
        Set<String> candidateTags = parseTags(product.getTags());
        Set<String> candidateFamilies = inferCategoryFamilies(List.of(product));
        long overlap = primaryTags.stream().filter(candidateTags::contains).count();
        long familyOverlap = primaryFamilies.stream().filter(candidateFamilies::contains).count();

        if (!primaryFamilies.isEmpty() && familyOverlap == 0 && overlap == 0) {
            return -1.0d;
        }

        double priceScore = 0.0d;
        if (averagePrice != null && product.getPrice() != null && averagePrice.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal diff = averagePrice.subtract(product.getPrice()).abs();
            priceScore = 1.0d - Math.min(1.0d, diff.doubleValue() / Math.max(averagePrice.doubleValue(), 1.0d));
        }

        double salesScore = Math.min(1.0d, salesCount / 12.0d);
        return overlap * 0.42d + familyOverlap * 0.30d + priceScore * 0.16d + salesScore * 0.12d;
    }

    private ChatRecommendationResponse toResponse(Product product, int salesCount, BigDecimal averagePrice, Set<String> primaryTags, Set<String> primaryFamilies) {
        Set<String> tags = parseTags(product.getTags());
        return new ChatRecommendationResponse(
                product.getId(),
                safe(product.getName()),
                safe(product.getDescription()),
                safe(product.getImageUrl()),
                product.getPrice(),
                buildReason(product, salesCount, averagePrice, primaryTags, primaryFamilies, tags),
                salesCount,
                averagePrice == null || product.getPrice() == null || product.getPrice().compareTo(averagePrice) <= 0,
                tags.stream().limit(4).toList());
    }

    private String buildReason(Product product, int salesCount, BigDecimal averagePrice, Set<String> primaryTags, Set<String> primaryFamilies, Set<String> candidateTags) {
        long overlap = primaryTags.stream().filter(candidateTags::contains).count();
        boolean sameFamily = primaryFamilies.stream().anyMatch(inferCategoryFamilies(List.of(product))::contains);

        StringBuilder builder = new StringBuilder();
        if (overlap > 0) {
            builder.append("与当前主商品同属相近品类或场景");
        } else if (sameFamily) {
            builder.append("与当前主商品属于同一购买方向");
        } else {
            builder.append("与当前主商品相近");
        }

        if (averagePrice != null && product.getPrice() != null) {
            if (product.getPrice().compareTo(averagePrice) <= 0) {
                builder.append("，价格带更容易一起下单");
            } else {
                builder.append("，可作为升级型搭配选择");
            }
        }

        if (salesCount > 0) {
            builder.append("，历史成交 ").append(salesCount).append(" 件");
        }
        return builder.toString();
    }

    private Set<String> inferCategoryFamilies(List<Product> products) {
        return products.stream()
                .map(product -> (safe(product.getName()) + " " + safe(product.getDescription()) + " " + safe(product.getTags())).toLowerCase(Locale.ROOT))
                .flatMap(content -> CATEGORY_ALIASES.entrySet().stream()
                        .filter(entry -> entry.getValue().stream().anyMatch(content::contains))
                        .map(Map.Entry::getKey))
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private Map<UUID, Integer> aggregateSales(List<OrderItem> soldItems) {
        return soldItems.stream()
                .filter(item -> item.getProductId() != null)
                .collect(Collectors.groupingBy(OrderItem::getProductId, Collectors.summingInt(OrderItem::getQuantity)));
    }

    private Set<String> parseTags(String tags) {
        if (tags == null || tags.isBlank()) {
            return Set.of();
        }
        return List.of(tags.split("[,，]"))
                .stream()
                .map(tag -> tag.trim().toLowerCase(Locale.ROOT))
                .filter(tag -> !tag.isBlank())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private BigDecimal averagePrice(List<Product> products) {
        List<BigDecimal> prices = products.stream()
                .map(Product::getPrice)
                .filter(price -> price != null)
                .toList();
        if (prices.isEmpty()) {
            return null;
        }
        BigDecimal sum = prices.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        return sum.divide(BigDecimal.valueOf(prices.size()), 2, java.math.RoundingMode.HALF_UP);
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private record RankedProduct(Product product, double score, int salesCount) {
    }
}
