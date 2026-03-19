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
    private static final Map<String, List<String>> CATEGORY_ALIASES = Map.ofEntries(
        Map.entry("audio", List.of("耳机", "headphone", "audio", "降噪", "耳麦", "buds", "freebuds", "airpods", "xm5")),
        Map.entry("laptop", List.of("笔记本", "laptop", "轻薄本", "电脑", "办公本", "游戏本", "notebook")),
        Map.entry("tablet", List.of("平板", "tablet", "ipad")),
        Map.entry("monitor", List.of("显示器", "monitor", "4k", "桌面")),
        Map.entry("keyboard", List.of("键盘", "keyboard")),
        Map.entry("mouse", List.of("鼠标", "mouse")),
        Map.entry("charger", List.of("充电器", "charger", "快充", "氮化镓", "充电头", "电源")),
        Map.entry("watch", List.of("手表", "watch")),
        Map.entry("band", List.of("手环", "band")),
        Map.entry("console", List.of("游戏机", "console", "switch", "掌机")),
        Map.entry("skincare", List.of("护肤", "护肤品", "保湿", "修护", "乳液", "面霜", "精华", "面膜")),
        Map.entry("cleanser", List.of("洁面", "清洁", "洗护", "洗面", "氨基酸洁面")),
        Map.entry("pet_food", List.of("猫粮", "狗粮", "宠粮", "冻干")),
        Map.entry("pet_travel", List.of("宠物包", "外出", "看诊", "背包")),
        Map.entry("kitchen", List.of("厨房", "锅具", "炒锅", "净饮", "饮水机", "厨具")),
        Map.entry("coffee", List.of("咖啡", "coffee", "饮品", "提神")),
        Map.entry("snack", List.of("零食", "坚果", "礼盒", "早餐")),
        Map.entry("life", List.of("日用", "家居", "家纺", "纸品", "抽纸", "纸巾", "洗衣", "凝珠", "日化", "床品", "四件套", "枕头", "湿巾")));

    private static final Map<String, List<String>> COMPLEMENTARY_FAMILIES = Map.ofEntries(
        Map.entry("audio", List.of("charger")),
        Map.entry("laptop", List.of("mouse", "keyboard", "charger", "monitor")),
        Map.entry("tablet", List.of("keyboard", "charger", "audio")),
        Map.entry("monitor", List.of("laptop", "mouse", "keyboard")),
        Map.entry("keyboard", List.of("mouse", "laptop")),
        Map.entry("mouse", List.of("keyboard", "laptop")),
        Map.entry("charger", List.of("audio", "laptop", "tablet", "watch", "band")),
        Map.entry("console", List.of("audio")),
        Map.entry("skincare", List.of("cleanser")),
        Map.entry("cleanser", List.of("skincare")),
        Map.entry("pet_food", List.of("pet_travel")),
        Map.entry("pet_travel", List.of("pet_food")),
        Map.entry("coffee", List.of("snack")),
        Map.entry("snack", List.of("coffee")));

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
        Set<String> preferredBundleFamilies = resolvePreferredBundleFamilies(primaryFamilies);
        BigDecimal averagePrice = averagePrice(baseProducts);

        return Mono.zip(productRepository.findAll().collectList(), orderItemRepository.findAll().collectList())
                .map(tuple -> {
                    List<Product> allProducts = tuple.getT1();
                    Map<UUID, Integer> sales = aggregateSales(tuple.getT2());

                    return allProducts.stream()
                            .filter(product -> product.getId() != null && !excludedIds.contains(product.getId()))
                            .map(product -> new RankedProduct(product, rank(primaryTags, primaryFamilies, preferredBundleFamilies, averagePrice, product, sales.getOrDefault(product.getId(), 0)), sales.getOrDefault(product.getId(), 0)))
                            .filter(ranked -> ranked.score() > 0.18d)
                            .sorted(Comparator.comparingDouble(RankedProduct::score).reversed())
                            .limit(limit)
                            .map(ranked -> toResponse(ranked.product(), ranked.salesCount(), averagePrice, primaryTags, primaryFamilies, preferredBundleFamilies))
                            .toList();
                });
    }

    public Mono<List<ChatRecommendationResponse>> recommendForProduct(UUID productId, int limit) {
        return productRepository.findById(productId)
                .flatMap(product -> recommendRelatedProducts(List.of(product), limit))
                .defaultIfEmpty(List.of());
    }

    private double rank(Set<String> primaryTags,
                        Set<String> primaryFamilies,
                        Set<String> preferredBundleFamilies,
                        BigDecimal averagePrice,
                        Product product,
                        int salesCount) {
        Set<String> candidateTags = parseTags(product.getTags());
        Set<String> candidateFamilies = inferCategoryFamilies(List.of(product));
        long overlap = primaryTags.stream().filter(candidateTags::contains).count();
        long familyOverlap = primaryFamilies.stream().filter(candidateFamilies::contains).count();
        long bundleOverlap = preferredBundleFamilies.stream().filter(candidateFamilies::contains).count();

        if (!preferredBundleFamilies.isEmpty() && bundleOverlap == 0 && familyOverlap == 0 && overlap == 0) {
            return -1.0d;
        }

        if (preferredBundleFamilies.isEmpty() && !primaryFamilies.isEmpty() && familyOverlap == 0 && overlap == 0) {
            return -1.0d;
        }

        double priceScore = 0.0d;
        if (averagePrice != null && product.getPrice() != null && averagePrice.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal diff = averagePrice.subtract(product.getPrice()).abs();
            priceScore = 1.0d - Math.min(1.0d, diff.doubleValue() / Math.max(averagePrice.doubleValue(), 1.0d));
        }

        double salesScore = Math.min(1.0d, salesCount / 12.0d);
        if (bundleOverlap > 0) {
            return bundleOverlap * 0.58d + overlap * 0.18d + familyOverlap * 0.08d + priceScore * 0.08d + salesScore * 0.08d;
        }

        return overlap * 0.34d + familyOverlap * 0.28d + priceScore * 0.22d + salesScore * 0.16d;
    }

    private ChatRecommendationResponse toResponse(Product product,
                                                 int salesCount,
                                                 BigDecimal averagePrice,
                                                 Set<String> primaryTags,
                                                 Set<String> primaryFamilies,
                                                 Set<String> preferredBundleFamilies) {
        Set<String> tags = parseTags(product.getTags());
        return new ChatRecommendationResponse(
                product.getId(),
                safe(product.getName()),
                safe(product.getDescription()),
                safe(product.getImageUrl()),
                product.getPrice(),
                buildReason(product, salesCount, averagePrice, primaryTags, primaryFamilies, preferredBundleFamilies, tags),
                salesCount,
                averagePrice == null || product.getPrice() == null || product.getPrice().compareTo(averagePrice) <= 0,
                tags.stream().limit(4).toList());
    }

    private String buildReason(Product product,
                               int salesCount,
                               BigDecimal averagePrice,
                               Set<String> primaryTags,
                               Set<String> primaryFamilies,
                               Set<String> preferredBundleFamilies,
                               Set<String> candidateTags) {
        long overlap = primaryTags.stream().filter(candidateTags::contains).count();
        Set<String> candidateFamilies = inferCategoryFamilies(List.of(product));
        boolean sameFamily = primaryFamilies.stream().anyMatch(candidateFamilies::contains);
        boolean complementary = preferredBundleFamilies.stream().anyMatch(candidateFamilies::contains);

        StringBuilder builder = new StringBuilder();
        if (complementary) {
            builder.append(resolveBundleReason(primaryFamilies, candidateFamilies));
        } else if (overlap > 0) {
            builder.append("与当前主商品在使用场景上有关联，可一起考虑");
        } else if (sameFamily) {
            builder.append("与当前主商品属于相近购买方向，可作为补充比较");
        } else {
            builder.append("与当前主商品形成顺手可用的补充链路");
        }

        if (averagePrice != null && product.getPrice() != null) {
            if (product.getPrice().compareTo(averagePrice) <= 0) {
                builder.append("，价格带更容易一起下单");
            } else {
                builder.append("，适合作为升级型搭配选择");
            }
        }

        if (salesCount > 0) {
            builder.append("，历史成交 ").append(salesCount).append(" 件");
        }
        return builder.toString();
    }

    private Set<String> resolvePreferredBundleFamilies(Set<String> primaryFamilies) {
        return primaryFamilies.stream()
                .flatMap(family -> COMPLEMENTARY_FAMILIES.getOrDefault(family, List.of()).stream())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private String resolveBundleReason(Set<String> primaryFamilies, Set<String> candidateFamilies) {
        if (hasFamily(primaryFamilies, "laptop") && hasFamily(candidateFamilies, "mouse")) {
            return "补齐笔记本的高频操作链路，长时间办公更顺手";
        }
        if (hasFamily(primaryFamilies, "laptop") && hasFamily(candidateFamilies, "keyboard")) {
            return "补齐笔记本桌面输入体验，适合固定工位或长时间码字";
        }
        if ((hasFamily(primaryFamilies, "laptop") || hasFamily(primaryFamilies, "tablet")) && hasFamily(candidateFamilies, "charger")) {
            return "方便出差和移动办公时统一补电，搭配逻辑更完整";
        }
        if (hasFamily(primaryFamilies, "monitor") && (hasFamily(candidateFamilies, "mouse") || hasFamily(candidateFamilies, "keyboard"))) {
            return "围绕桌面办公场景补齐操作链路，和显示器一起用更顺手";
        }
        if (hasFamily(primaryFamilies, "audio") && hasFamily(candidateFamilies, "charger")) {
            return "适合耳机、手机等设备一起补电，通勤和差旅场景更省心";
        }
        if (hasFamily(primaryFamilies, "console") && hasFamily(candidateFamilies, "audio")) {
            return "补齐沉浸式游戏音频体验，和主设备搭配更合理";
        }
        if (hasFamily(primaryFamilies, "skincare") && hasFamily(candidateFamilies, "cleanser")) {
            return "先清洁再保湿修护，护肤步骤更完整";
        }
        if (hasFamily(primaryFamilies, "cleanser") && hasFamily(candidateFamilies, "skincare")) {
            return "洁面后补上保湿修护，日常护理链路更完整";
        }
        if (hasFamily(primaryFamilies, "pet_food") && hasFamily(candidateFamilies, "pet_travel")) {
            return "围绕宠物日常喂养延伸到外出场景，搭配更实用";
        }
        if (hasFamily(primaryFamilies, "pet_travel") && hasFamily(candidateFamilies, "pet_food")) {
            return "外出装备之外顺手补齐日常喂养需求，组合更完整";
        }
        if (hasFamily(primaryFamilies, "coffee") && hasFamily(candidateFamilies, "snack")) {
            return "适合作为咖啡场景的轻食补充，办公室和早餐都更顺手";
        }
        if (hasFamily(primaryFamilies, "snack") && hasFamily(candidateFamilies, "coffee")) {
            return "适合作为零食场景的饮品搭配，组合逻辑自然";
        }
        return "围绕主推商品补齐使用链路，属于逻辑成立的补充搭配";
    }

    private boolean hasFamily(Set<String> families, String family) {
        return families.contains(family);
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
