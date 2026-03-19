package com.example.ecommerce.service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;

import com.example.ecommerce.model.Product;

import reactor.core.publisher.Mono;

@Service
public class SkillRegistry {
    private static final Pattern PRICE_UNDER_PATTERN = Pattern.compile("^/price-under\\s+(\\d{2,6})(?:\\s+(.+))?$", Pattern.CASE_INSENSITIVE);

    private final ProductVectorStoreService productVectorStoreService;
    private final AiShoppingAdvisorService aiShoppingAdvisorService;
    private final List<String> skills = List.of(
            "/skills 查看当前 AI 导购能力",
            "/ai-status 查看 AI 实时模型状态",
            "/catalog 查看商品知识库状态",
            "/latest-products 查看最近同步的商品",
            "/product 关键词 精准查商品",
            "/price-under 金额 需求 按预算筛商品",
            "/refresh-products 立即强制刷新商品知识库",
            "search_products",
            "cart_add",
            "cart_remove",
            "cart_view");

    public SkillRegistry(ProductVectorStoreService productVectorStoreService,
                         AiShoppingAdvisorService aiShoppingAdvisorService) {
        this.productVectorStoreService = productVectorStoreService;
        this.aiShoppingAdvisorService = aiShoppingAdvisorService;
    }

    public List<String> listSkills() {
        return skills;
    }

    public Mono<String> handleSkill(String message) {
        if (message == null) {
            return Mono.empty();
        }
        String normalized = message.trim();
        String lower = normalized.toLowerCase(Locale.ROOT);
        if (lower.startsWith("/skills")) {
            return Mono.just("当前可用技能：\n- " + String.join("\n- ", skills));
        }
        if (lower.startsWith("/ai-status")) {
            return Mono.just(aiShoppingAdvisorService.getRuntimeStatusSummary());
        }
        if (lower.startsWith("/catalog")) {
            return productVectorStoreService.getCatalogStats()
                    .map(stats -> formatCatalogReply("当前", stats));
        }
        if (lower.startsWith("/refresh-products")) {
            return productVectorStoreService.refreshCatalog()
                    .map(stats -> formatCatalogReply("刷新完成", stats));
        }
        if (lower.startsWith("/latest-products")) {
            return productVectorStoreService.getCatalogStats()
                    .map(stats -> {
                        if (stats.latestProducts().isEmpty()) {
                            return "当前商品知识库为空。";
                        }
                        String body = stats.latestProducts().stream()
                                .map(this::formatProductLine)
                                .reduce((left, right) -> left + "\n" + right)
                                .orElse("暂无商品");
                        return "最近同步的商品：\n" + body;
                    });
        }
        if (lower.startsWith("/product ")) {
            String keyword = normalized.substring("/product".length()).trim();
            if (keyword.isEmpty()) {
                return Mono.just("用法：/product 商品关键词");
            }
            return productVectorStoreService.search(keyword, 5)
                    .collectList()
                    .map(products -> formatSearchReply(keyword, products));
        }
        Matcher priceMatcher = PRICE_UNDER_PATTERN.matcher(normalized);
        if (priceMatcher.matches()) {
            BigDecimal budget = new BigDecimal(priceMatcher.group(1));
            String keyword = priceMatcher.group(2) == null ? "" : priceMatcher.group(2).trim();
            return productVectorStoreService.search(keyword, 12)
                    .filter(product -> product.getPrice() != null && product.getPrice().compareTo(budget) <= 0)
                    .take(5)
                    .collectList()
                    .map(products -> {
                        if (products.isEmpty()) {
                            return "预算 " + budget.stripTrailingZeros().toPlainString() + " 元内暂时没有匹配商品。";
                        }
                        return "预算 " + budget.stripTrailingZeros().toPlainString() + " 元内的候选商品：\n"
                                + products.stream().map(this::formatProductLine).reduce((left, right) -> left + "\n" + right).orElse("");
                    });
        }
        return Mono.empty();
    }

    private String formatCatalogReply(String prefix, ProductVectorStoreService.CatalogStats stats) {
        String latest = stats.latestProducts().isEmpty()
                ? "暂无"
                : stats.latestProducts().stream().limit(3).map(Product::getName).reduce((left, right) -> left + "、" + right).orElse("暂无");
        return prefix + "商品知识库成功，当前共同步 " + stats.productCount() + " 个商品。最近商品：" + latest;
    }

    private String formatSearchReply(String keyword, List<Product> products) {
        if (products.isEmpty()) {
            return "没有检索到和“" + keyword + "”相关的商品。";
        }
        return "和“" + keyword + "”相关的商品：\n"
                + products.stream().map(this::formatProductLine).reduce((left, right) -> left + "\n" + right).orElse("");
    }

    private String formatProductLine(Product product) {
        String price = product.getPrice() == null ? "待定" : product.getPrice().stripTrailingZeros().toPlainString() + " 元";
        return "- " + product.getName() + " | " + price + " | " + (product.getTags() == null ? "无标签" : product.getTags());
    }
}
