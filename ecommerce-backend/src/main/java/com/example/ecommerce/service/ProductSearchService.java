package com.example.ecommerce.service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;

import com.example.ecommerce.model.Product;
import com.example.ecommerce.repository.ProductRepository;

import reactor.core.publisher.Flux;

@Service
public class ProductSearchService {
    private static final Map<String, List<String>> SYNONYMS = Map.ofEntries(
            Map.entry("耳机", List.of("headphone", "audio", "noise cancelling", "降噪")),
            Map.entry("生活用品", List.of("日用品", "家居", "家纺", "纸品", "抽纸", "纸巾", "清洁", "洗衣", "凝珠", "日化", "床品", "四件套", "枕头", "厨房", "厨具", "锅具", "炒锅", "净饮", "湿巾", "洁面", "护肤", "洗护")),
            Map.entry("降噪", List.of("noise cancelling", "headphone", "audio", "静音")),
            Map.entry("笔记本", List.of("laptop", "notebook", "office", "轻薄")),
            Map.entry("电脑", List.of("computer", "laptop", "notebook", "办公")),
            Map.entry("手表", List.of("watch", "wearable", "智能手表", "健康")),
            Map.entry("手环", List.of("band", "wearable", "fitness", "智能手环")),
            Map.entry("键盘", List.of("keyboard", "mechanical", "gaming", "机械键盘")),
            Map.entry("游戏机", List.of("game", "console", "switch", "娱乐")),
            Map.entry("办公", List.of("office", "productivity", "轻薄", "keyboard", "laptop")),
            Map.entry("运动", List.of("fitness", "health", "wearable", "watch", "band")),
                Map.entry("通勤", List.of("commute", "noise cancelling", "headphone", "lightweight")),
                Map.entry("轻薄", List.of("lightweight", "laptop", "office", "笔记本")),
                Map.entry("厨房", List.of("厨具", "锅具", "净饮", "家居", "kitchen")),
                Map.entry("家居", List.of("home", "家纺", "枕头", "四件套", "纸品")),
                Map.entry("清洁", List.of("纸巾", "洗衣", "居家", "clean", "laundry")),
                Map.entry("洗护", List.of("个护", "护肤", "保湿", "洁面", "care")),
                Map.entry("母婴", List.of("婴儿", "宝宝", "湿巾", "纸尿裤", "baby")),
                Map.entry("宠物", List.of("cat", "dog", "宠粮", "猫粮", "pet")),
                Map.entry("户外", List.of("outdoor", "旅行", "冲锋衣", "露营", "运动")),
                Map.entry("咖啡", List.of("coffee", "饮品", "零食", "提神")),
                Map.entry("食品", List.of("snack", "零食", "坚果", "饮品", "礼盒")),
                Map.entry("文具", List.of("stationery", "学习", "办公", "书写"))
    );

    private final ProductRepository productRepository;

    public ProductSearchService(ProductRepository productRepository) {
        this.productRepository = productRepository;
    }

    public Flux<Product> search(String query) {
        if (query == null || query.isBlank()) {
            return productRepository.findAll();
        }

        Set<String> tokens = expandTokens(query);
        return productRepository.findAll()
                .filter(product -> matches(product, tokens));
    }

    private Set<String> expandTokens(String query) {
        Set<String> tokens = new LinkedHashSet<>();
        String normalized = normalize(query);
        tokens.add(normalized);

        for (String part : normalized.split("\\s+")) {
            if (!part.isBlank()) {
                tokens.add(part);
            }
        }

        for (Map.Entry<String, List<String>> entry : SYNONYMS.entrySet()) {
            if (normalized.contains(entry.getKey())) {
                tokens.add(entry.getKey());
                tokens.addAll(entry.getValue());
            }

            for (String alias : entry.getValue()) {
                if (normalized.contains(normalize(alias))) {
                    tokens.add(entry.getKey());
                    tokens.addAll(entry.getValue());
                }
            }
        }
        return tokens;
    }

    private boolean matches(Product product, Set<String> tokens) {
        String haystack = normalize(String.join(" ",
                valueOrEmpty(product.getName()),
                valueOrEmpty(product.getDescription()),
            valueOrEmpty(product.getTags()),
            valueOrEmpty(product.getCategory()),
            valueOrEmpty(product.getSpecs()),
            valueOrEmpty(product.getSellingPoints()),
            valueOrEmpty(product.getPolicy())));

        return tokens.stream().anyMatch(token -> !token.isBlank() && haystack.contains(normalize(token)));
    }

    private String valueOrEmpty(String value) {
        return value == null ? "" : value;
    }

    private String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT)
                .replace('-', ' ')
                .replace('_', ' ')
                .replace(',', ' ')
                .replace('，', ' ')
                .trim();
    }
}