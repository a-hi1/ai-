package com.example.ecommerce.service;

import java.time.Instant;
import java.time.Duration;
import java.util.ArrayList;
import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.example.ecommerce.config.AiProperties;
import com.example.ecommerce.dto.ChatAdvicePayload;
import com.example.ecommerce.dto.ChatInsightResponse;
import com.example.ecommerce.dto.ChatRecommendationResponse;
import com.example.ecommerce.model.ChatMessage;
import com.example.ecommerce.model.OrderItem;
import com.example.ecommerce.model.Product;
import com.example.ecommerce.repository.ChatMessageRepository;
import com.example.ecommerce.repository.OrderItemRepository;
import com.example.ecommerce.repository.ProductRepository;
import com.example.ecommerce.service.ai.AdvisorToolContext;
import com.example.ecommerce.service.ai.DirectShoppingAdvisorAiService;
import com.example.ecommerce.service.ai.ShoppingAdvisorAiService;
import com.example.ecommerce.service.ai.ShoppingAdvisorTools;

import dev.langchain4j.service.AiServices;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.openai.Http11OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@Service
public class AiShoppingAdvisorService {
    private static final Pattern BUDGET_PATTERN = Pattern.compile("(\\d{2,6})\\s*(元|块|rmb)", Pattern.CASE_INSENSITIVE);
    private static final Pattern FLEX_BUDGET_PATTERN = Pattern.compile("(?:预算|价位|控制在|不超过|以内)?\\s*(\\d{2,6})\\s*(?:元|块|rmb|人民币|以内)?", Pattern.CASE_INSENSITIVE);
    private static final String INTENT_LIFE_SUPPLIES = "\u751f\u6d3b\u7528\u54c1";
    private static final String INTENT_APPAREL = "服饰";
    private static final int MAX_RECOMMENDATIONS = 3;
    private static final int MIN_REALTIME_ATTEMPTS = 2;
    private static final int MAX_REALTIME_ATTEMPTS = 4;
    private static final Logger log = LoggerFactory.getLogger(AiShoppingAdvisorService.class);

    private final AiProperties aiProperties;
    private final RetrievalGatewayService retrievalGatewayService;
    private final ProductRecommendationService productRecommendationService;
    private final ChatMessageRepository chatMessageRepository;
    private final OrderItemRepository orderItemRepository;
    private final ProductRepository productRepository;
    private final ShoppingAdvisorTools shoppingAdvisorTools;
    private final AdvisorToolContext advisorToolContext;

    private volatile ChatLanguageModel chatLanguageModel;
    private volatile ShoppingAdvisorAiService shoppingAdvisorAiService;
    private volatile DirectShoppingAdvisorAiService directShoppingAdvisorAiService;
    private final AtomicReference<AiRuntimeStatus> runtimeStatusRef = new AtomicReference<>();
    private final AtomicInteger consecutiveFallbacks = new AtomicInteger();

    public AiShoppingAdvisorService(AiProperties aiProperties,
                                    RetrievalGatewayService retrievalGatewayService,
                                    ProductRecommendationService productRecommendationService,
                                    ChatMessageRepository chatMessageRepository,
                                    OrderItemRepository orderItemRepository,
                                    ProductRepository productRepository,
                                    ShoppingAdvisorTools shoppingAdvisorTools,
                                    AdvisorToolContext advisorToolContext) {
        this.aiProperties = aiProperties;
        this.retrievalGatewayService = retrievalGatewayService;
        this.productRecommendationService = productRecommendationService;
        this.chatMessageRepository = chatMessageRepository;
        this.orderItemRepository = orderItemRepository;
        this.productRepository = productRepository;
        this.shoppingAdvisorTools = shoppingAdvisorTools;
        this.advisorToolContext = advisorToolContext;
        this.runtimeStatusRef.set(AiRuntimeStatus.initial(currentProviderName(), activeModelName()));
    }

    public Mono<ChatAdvicePayload> advise(UUID userId, String message) {
        return Mono.zip(
                        retrievalGatewayService.search(message, 6, null, null).map(RetrievalGatewayService.RetrievalChunk::product).collectList(),
                        orderItemRepository.findAll().collectList(),
                        productRepository.findAll().collectList(),
                        chatMessageRepository.findByUserId(userId).collectList())
                .flatMap(tuple -> {
                    BigDecimal budget = extractBudget(message);
                    String intentCategory = detectIntentCategory(message);
                    String detectedIntent = formatIntentLabel(intentCategory, message);
                List<ChatMessage> history = tuple.getT4();
                List<Product> allCandidateProducts = mergeProducts(tuple.getT1(), tuple.getT3());
                    List<Product> intentProducts = filterByIntent(message, allCandidateProducts);
                    List<Product> budgetMatchedProducts = budget == null
                            ? intentProducts
                            : intentProducts.stream()
                                    .filter(product -> product.getPrice() != null && product.getPrice().compareTo(budget) <= 0)
                                    .toList();
                List<OrderItem> soldItems = tuple.getT2();
                    Map<UUID, Integer> sales = aggregateSales(soldItems);
                    boolean noProductWithinBudget = budget != null && budgetMatchedProducts.isEmpty();
                List<Product> fallbackBaseProducts = rankProducts(message,
                    budgetMatchedProducts.isEmpty() ? intentProducts : budgetMatchedProducts,
                    sales,
                    budget);

                if (fallbackBaseProducts.isEmpty()) {
                        return Mono.just(buildFallbackPayload(
                                "需求速览：当前商品库里还没有命中完全匹配的结果。\n\n建议你补充预算、用途、品牌偏好，或者直接描述具体场景，例如“通勤降噪耳机”“轻薄办公本”“运动健康手表”，我会继续按商品库为你缩小范围。",
                                List.of(),
                                List.of(),
                    buildInsights(detectedIntent, budget, List.of(), sales, noProductWithinBudget, message),
                                detectedIntent,
                                formatBudgetSummary(budget),
                                "NO_RANKED_PRODUCTS"));
                    }

                if (!supportsToolCalling()) {
                    return generateDirectRealtimeAdvice(message, fallbackBaseProducts, history, soldItems, budget, noProductWithinBudget)
                        .flatMap(reply -> productRecommendationService.recommendRelatedProducts(fallbackBaseProducts, 3)
                            .map(relatedRecommendations -> {
                                String normalizedReply = normalizeReply(reply);
                                List<ChatInsightResponse> bypassInsights = appendDirectAiBypassInsights(
                                    buildInsights(detectedIntent, budget, fallbackBaseProducts, sales, noProductWithinBudget, message),
                                    activeModelName());
                                List<ChatRecommendationResponse> recs = buildRecommendations(message, fallbackBaseProducts, sales, budget);
                                if (normalizedReply == null) {
                                    log.warn("AI direct path returned blank reply for provider={} model={}",
                                        currentProviderName(), activeModelName());
                                    return buildFallbackPayload(
                                        buildFallbackReply(message, fallbackBaseProducts, soldItems, budget, noProductWithinBudget),
                                        recs, relatedRecommendations, bypassInsights,
                                        detectedIntent, formatBudgetSummary(budget),
                                        "AI_RETURNED_EMPTY_REPLY");
                                }
                                return buildSuccessPayload(normalizedReply, recs, relatedRecommendations,
                                    bypassInsights, detectedIntent, formatBudgetSummary(budget));
                            }))
                        .onErrorResume(error -> productRecommendationService.recommendRelatedProducts(fallbackBaseProducts, 3)
                            .map(relatedRecommendations -> buildFallbackPayload(
                                buildFallbackReply(message, fallbackBaseProducts, soldItems, budget, noProductWithinBudget),
                                buildRecommendations(message, fallbackBaseProducts, sales, budget),
                                relatedRecommendations,
                                buildInsights(detectedIntent, budget, fallbackBaseProducts, sales, noProductWithinBudget, message),
                                detectedIntent,
                                formatBudgetSummary(budget),
                                "AI_DIRECT_CALL_FAILED: " + summarizeError(error))));
                }

                return generateRealtimeAdvice(userId, message)
                    .flatMap(execution -> {
                    List<Product> toolProducts = execution.snapshot().hasProducts()
                        ? mergeProducts(execution.snapshot().products(), fallbackBaseProducts)
                        : fallbackBaseProducts;
                    List<Product> rankedProducts = rankProducts(message, filterByIntent(message, toolProducts), sales, budget);
                    if (rankedProducts.isEmpty()) {
                        return Mono.just(buildFallbackPayload(
                            buildFallbackReply(message, fallbackBaseProducts, soldItems, budget, noProductWithinBudget),
                            buildRecommendations(message, fallbackBaseProducts, sales, budget),
                            List.of(),
                            appendToolInsights(buildInsights(detectedIntent, budget, fallbackBaseProducts, sales, noProductWithinBudget, message), execution.snapshot()),
                            detectedIntent,
                            formatBudgetSummary(budget),
                            "AI_TOOLS_RETURNED_NO_PRODUCTS"));
                    }

                        List<ChatRecommendationResponse> recommendations = buildRecommendations(
                            message,
                            preferStrictMainRecommendations(message, rankedProducts),
                            sales,
                            budget);
                    List<ChatInsightResponse> insights = appendToolInsights(
                        buildInsights(detectedIntent, budget, rankedProducts, sales, noProductWithinBudget, message),
                        execution.snapshot());

                    return productRecommendationService.recommendRelatedProducts(rankedProducts, 3)
                        .map(relatedRecommendations -> {
                            String normalizedReply = normalizeReply(execution.reply());
                            if (normalizedReply == null) {
                                log.warn("AI tool-call path returned blank reply for provider={} model={}",
                                    currentProviderName(), activeModelName());
                                return buildFallbackPayload(
                                    buildFallbackReply(message, rankedProducts, soldItems, budget, noProductWithinBudget),
                                    recommendations, relatedRecommendations, insights,
                                    detectedIntent, formatBudgetSummary(budget),
                                    "AI_RETURNED_EMPTY_REPLY");
                            }
                            return buildSuccessPayload(normalizedReply, recommendations, relatedRecommendations,
                                insights, detectedIntent, formatBudgetSummary(budget));
                        });
                    })
                    .onErrorResume(error -> {
                        invalidateAiCaches();
                        return generateDirectRealtimeAdvice(message, fallbackBaseProducts, history, soldItems, budget, noProductWithinBudget)
                        .flatMap(reply -> productRecommendationService.recommendRelatedProducts(fallbackBaseProducts, 3)
                            .map(relatedRecommendations -> {
                                String normalizedReply = normalizeReply(reply);
                                List<ChatInsightResponse> recoveryInsights = appendDirectAiRecoveryInsights(
                                    buildInsights(detectedIntent, budget, fallbackBaseProducts, sales, noProductWithinBudget, message),
                                    summarizeError(error));
                                List<ChatRecommendationResponse> recs = buildRecommendations(message, fallbackBaseProducts, sales, budget);
                                if (normalizedReply == null) {
                                    log.warn("AI recovery direct path also returned blank reply, falling to rule-based");
                                    return buildFallbackPayload(
                                        buildFallbackReply(message, fallbackBaseProducts, soldItems, budget, noProductWithinBudget),
                                        recs, relatedRecommendations, recoveryInsights,
                                        detectedIntent, formatBudgetSummary(budget),
                                        "AI_RETURNED_EMPTY_REPLY_IN_RECOVERY");
                                }
                                return buildSuccessPayload(normalizedReply, recs, relatedRecommendations,
                                    recoveryInsights, detectedIntent, formatBudgetSummary(budget));
                            }));
                    })
                    .onErrorResume(error -> productRecommendationService.recommendRelatedProducts(fallbackBaseProducts, 3)
                        .map(relatedRecommendations -> buildFallbackPayload(
                            buildFallbackReply(message, fallbackBaseProducts, soldItems, budget, noProductWithinBudget),
                            buildRecommendations(message, fallbackBaseProducts, sales, budget),
                            relatedRecommendations,
                            buildInsights(detectedIntent, budget, fallbackBaseProducts, sales, noProductWithinBudget, message),
                            detectedIntent,
                            formatBudgetSummary(budget),
                            "AI_TOOL_CALL_FAILED: " + summarizeError(error))));
                })
                .onErrorResume(error -> {
                    String reason = "AI_PIPELINE_FAILED: " + summarizeError(error);
                    log.error("AI advise failed, fallback as last resort. reason={}", reason, error);
                    BigDecimal budget = extractBudget(message);
                    String detectedIntent = formatIntentLabel(detectIntentCategory(message), message);
                    return Mono.just(buildFallbackPayload(
                            "需求速览：当前实时 AI 暂时不可用，已切换到兜底推荐结果。",
                            List.of(),
                            List.of(),
                            buildInsights(detectedIntent, budget, List.of(), Map.of(), false, message),
                            detectedIntent,
                            formatBudgetSummary(budget),
                            reason));
                });
    }

    public String getRuntimeStatusSummary() {
        AiRuntimeStatus status = runtimeStatusRef.get();
        StringBuilder builder = new StringBuilder();
        builder.append("AI Provider: ").append(status.provider()).append("\n");
        builder.append("模型名称: ").append(status.modelName()).append("\n");
        builder.append("当前模式: ").append("NOT_CALLED_YET".equals(status.reason()) ? "待首个请求" : (status.fallback() ? "规则兜底" : "实时模型")).append("\n");
        builder.append("最近原因: ").append(status.reason()).append("\n");
        builder.append("连续兜底次数: ").append(status.consecutiveFallbacks()).append("\n");
        builder.append("最近更新时间: ").append(status.updatedAt());
        return builder.toString();
    }

    public AiRuntimeStatus getRuntimeStatus() {
        return runtimeStatusRef.get();
    }

    public synchronized void reloadProviderConfiguration(String reason) {
        chatLanguageModel = null;
        shoppingAdvisorAiService = null;
        consecutiveFallbacks.set(0);

        String normalizedReason = isBlank(reason) ? previewConfigurationReason() : reason;
        boolean fallback = !"READY".equalsIgnoreCase(normalizedReason)
                && !"CONFIG_UPDATED".equalsIgnoreCase(normalizedReason)
                && !"PERSISTED_CONFIG_LOADED".equalsIgnoreCase(normalizedReason);

        runtimeStatusRef.set(new AiRuntimeStatus(
                currentProviderName(),
                activeModelName(),
                fallback,
                normalizedReason,
                Instant.now(),
                0));
    }

    private String buildPrompt(String message,
                               List<Product> products,
                               List<ChatMessage> history,
                               List<OrderItem> soldItems,
                               BigDecimal budget,
                               boolean noProductWithinBudget) {
        Map<UUID, Integer> sales = aggregateSales(soldItems);
        String demandSummary = buildDemandSummary(message, budget, history);

        String productContext = products.stream()
                .map(product -> String.format("- 商品：%s；价格：%s 元；标签：%s；描述：%s；历史成交件数：%d",
                        safe(product.getName()),
                        formatPrice(product.getPrice()),
                        safe(product.getTags()),
                        safe(product.getDescription()),
                        sales.getOrDefault(product.getId(), 0)))
                .collect(Collectors.joining("\n"));

        String historyContext = history.stream()
                .sorted(Comparator.comparing(ChatMessage::getCreatedAt))
                .skip(Math.max(0, history.size() - 6L))
                .map(item -> ("USER".equals(item.getRole()) ? "用户" : "助手") + "：" + item.getContent())
                .collect(Collectors.joining("\n"));

        return "你是中文电商导购助手，负责模拟线下专业导购，要求根据用户问题推荐商品。\n"
                + "输出要求：\n"
                + "1. 使用专业、清晰、有温度的中文导购语气。\n"
                + "2. 第一段写“需求速览”，总结用户场景、预算和偏好。\n"
                + "3. 第二段写“推荐清单”，从给定商品中推荐 2 到 3 个，每个商品必须写适合场景、核心卖点、价格。\n"
                + "4. 第三段写“购买建议”，明确主推款和不同人群适合的差异。\n"
                + "5. 只允许使用给定商品，不要虚构商品型号、价格、销量或库存。\n"
                + "6. 如果有历史成交数据，可作为受欢迎程度参考，但不要夸大。\n"
                + "7. 如果用户给了预算，优先推荐预算内商品；若预算内没有完全匹配的商品，必须明确提示并给出最接近预算的替代项。\n"
            + "8. 如果用户需求还不够完整，允许你在回答中明确写出你的理解前提，但不要脱离上下文乱猜。\n"
            + "9. 结尾提醒用户可以直接把推荐商品加入购物车，不要输出 Markdown 表格。\n\n"
                + "用户本轮问题：" + message + "\n\n"
            + "结构化需求摘要：\n" + demandSummary + "\n\n"
                + "预算信息：" + formatBudgetSummary(budget) + "\n"
                + "预算内是否有匹配商品：" + (noProductWithinBudget ? "没有，需要给替代方案" : "有") + "\n\n"
                + "最近聊天记录：\n" + (historyContext.isBlank() ? "暂无" : historyContext) + "\n\n"
                + "RAG 检索到的商品与成交信息：\n" + productContext;
    }

    private String buildFallbackReply(String message,
                                      List<Product> products,
                                      List<OrderItem> soldItems,
                                      BigDecimal budget,
                                      boolean noProductWithinBudget) {
        Map<UUID, Integer> sales = aggregateSales(soldItems);
        String sceneSummary = formatSceneSummary(message);

        String recommendations = products.stream()
                .limit(MAX_RECOMMENDATIONS)
            .map(product -> buildFallbackRecommendation(product, message, sales.getOrDefault(product.getId(), 0), budget, products.indexOf(product) == 0))
                .collect(Collectors.joining("\n"));

        String budgetNotice = noProductWithinBudget && budget != null
            ? "预算内暂无完全匹配款，以下给你优先放最接近需求、且更容易实际落地的替代方案。"
            : "以下推荐已经优先按你的场景、预算和已有成交热度做了排序。";

        return "需求速览：\n"
            + "- 需求类型：" + formatIntentLabel(detectIntentCategory(message), message) + "\n"
            + "- 核心场景：" + sceneSummary + "\n"
            + "- 预算范围：" + formatBudgetSummary(budget) + "\n"
            + "- 推荐策略：" + budgetNotice + "\n\n"
            + "推荐结论：\n"
                + recommendations
            + "\n\n购买建议：\n"
            + "- 主推款优先满足你当前最核心的场景诉求，适合直接进入对比或加购。\n"
            + "- 如果你更在意预算，我可以继续帮你压缩到更低价位；如果你更在意体验，我可以再按降噪、续航、便携或办公强度细分。\n"
            + "- 你也可以直接点开商品详情，我会继续基于当前商品给你做搭配推荐。";
    }

    private List<ChatRecommendationResponse> buildRecommendations(String message,
                                                                  List<Product> products,
                                                                  Map<UUID, Integer> sales,
                                                                  BigDecimal budget) {
        return products.stream()
                .limit(MAX_RECOMMENDATIONS)
                .map(product -> new ChatRecommendationResponse(
                        product.getId(),
                        safe(product.getName()),
                        safe(product.getDescription()),
                        safe(product.getImageUrl()),
                        product.getPrice(),
                        recommendationReason(message, product, sales.getOrDefault(product.getId(), 0), budget),
                        sales.getOrDefault(product.getId(), 0),
                        budget == null || (product.getPrice() != null && product.getPrice().compareTo(budget) <= 0),
                        parseTags(product.getTags())))
                .toList();
    }

    private List<Product> preferStrictMainRecommendations(String message, List<Product> products) {
        String intent = detectIntentCategory(message);
        if (intent == null || allowsCrossCategoryResults(message)) {
            return products;
        }

        List<Product> strictMatched = products.stream()
                .filter(product -> matchesStrictIntent(product, intent))
                .toList();

        return strictMatched.size() >= Math.min(2, products.size()) ? strictMatched : products;
    }

        private List<ChatInsightResponse> buildInsights(String detectedIntent,
                                BigDecimal budget,
                                List<Product> products,
                                Map<UUID, Integer> sales,
                                boolean noProductWithinBudget,
                                String message) {
        String topSeller = products.stream()
                .max(Comparator.comparingInt(product -> sales.getOrDefault(product.getId(), 0)))
                .map(product -> safe(product.getName()) + " / " + sales.getOrDefault(product.getId(), 0) + " 件成交")
                .orElse("暂无成交数据");

        return List.of(
                new ChatInsightResponse("需求类型", detectedIntent),
            new ChatInsightResponse("核心场景", formatSceneSummary(message)),
                new ChatInsightResponse("预算范围", formatBudgetSummary(budget)),
                new ChatInsightResponse("候选商品", products.size() + " 款"),
                new ChatInsightResponse("筛选状态", noProductWithinBudget ? "预算外替代推荐" : "预算内优先推荐"),
                new ChatInsightResponse("数据置信分层", buildConfidenceBreakdown(products)),
                new ChatInsightResponse("热度主推", topSeller));
    }

    private String buildConfidenceBreakdown(List<Product> products) {
        long high = products.stream().filter(p -> computeProductConfidenceScore(p) >= 0.75d).count();
        long medium = products.stream().filter(p -> {
            double score = computeProductConfidenceScore(p);
            return score >= 0.50d && score < 0.75d;
        }).count();
        long low = Math.max(0L, products.size() - high - medium);
        return "高 " + high + " / 中 " + medium + " / 低 " + low;
    }

    private String recommendationReason(String message, Product product, int soldCount, BigDecimal budget) {
        List<String> reasons = new ArrayList<>();
        reasons.add(fallbackReason(message, product));

        String scene = detectScene(message);
        if (scene != null) {
            reasons.add("匹配" + formatSceneTag(scene) + "使用场景");
        }

        String capability = extractCapabilityHighlight(message, product);
        if (!capability.isBlank()) {
            reasons.add(capability);
        }

        String budgetReason = buildBudgetReason(product, budget);
        if (!budgetReason.isBlank()) {
            reasons.add(budgetReason);
        }

        if (soldCount > 0) {
            reasons.add("历史成交 " + soldCount + " 件，热度稳定");
        }

        reasons.add("数据置信度：" + confidenceLabel(product));

        return reasons.stream()
                .filter(item -> item != null && !item.isBlank())
                .distinct()
                .collect(Collectors.joining("；"));
    }

    private String confidenceLabel(Product product) {
        double score = computeProductConfidenceScore(product);
        if (score >= 0.75d) {
            return "高";
        }
        if (score >= 0.50d) {
            return "中";
        }
        return "低";
    }

    private double computeProductConfidenceScore(Product product) {
        if (product == null) {
            return 0.0d;
        }

        double score = 0.35d;
        String dataSource = safe(product.getDataSource()).toLowerCase(Locale.ROOT);

        if (!dataSource.contains("crawler") && !dataSource.contains("jsonfile")) {
            score += 0.22d;
        }
        if (!safe(product.getSourceProductId()).isBlank()) {
            score += 0.10d;
        }
        if (!isGenericDescription(product.getDescription())) {
            score += 0.12d;
        }
        if (!isGenericDescription(product.getSellingPoints())) {
            score += 0.10d;
        }
        if (!safe(product.getSpecs()).isBlank() && safe(product.getSpecs()).length() >= 10) {
            score += 0.08d;
        }
        if (!safe(product.getPolicy()).isBlank()) {
            score += 0.05d;
        }
        if (isCategoryTagConsistent(product)) {
            score += 0.08d;
        } else {
            score -= 0.10d;
        }
        if (isLowQualityCrawlerProduct(product)) {
            score -= 0.28d;
        }

        return Math.max(0.0d, Math.min(1.0d, score));
    }

    private boolean isLowQualityCrawlerProduct(Product product) {
        String source = safe(product.getDataSource()).toLowerCase(Locale.ROOT);
        if (!source.contains("crawler") && !source.contains("jsonfile")) {
            return false;
        }

        String name = safe(product.getName()).trim();
        String description = safe(product.getDescription()).trim();
        String tags = safe(product.getTags()).trim().toLowerCase(Locale.ROOT);
        boolean missingCore = name.length() < 4 || description.length() < 8;
        boolean genericDescription = isGenericDescription(description);
        boolean genericTagsOnly = tags.isBlank() || tags.equals("crawler") || tags.equals("jsonfile") || tags.equals("crawler,jsonfile");
        return missingCore || genericDescription || genericTagsOnly;
    }

    private boolean isGenericDescription(String value) {
        String text = safe(value).toLowerCase(Locale.ROOT).trim();
        if (text.isBlank() || text.length() < 10) {
            return true;
        }
        return containsAnyKeyword(text, List.of(
                "时尚百搭",
                "舒适穿着",
                "智能便捷生活助手",
                "母婴护理安心之选",
                "高性价比日常办公神器",
                "官方正品",
                "标准款"));
    }

    private boolean isCategoryTagConsistent(Product product) {
        String content = (safe(product.getCategory()) + " " + safe(product.getTags()) + " " + safe(product.getName()))
                .toLowerCase(Locale.ROOT);
        if (content.isBlank()) {
            return false;
        }

        if (containsAnyKeyword(content, List.of("耳机", "headphone", "buds"))) {
            return !containsAnyKeyword(content, List.of("母婴", "宠物", "服饰"));
        }
        if (containsAnyKeyword(content, List.of("笔记本", "laptop", "电脑"))) {
            return !containsAnyKeyword(content, List.of("猫粮", "宠物", "纸巾"));
        }
        return true;
    }

    private List<Product> rankProducts(String message, List<Product> products, Map<UUID, Integer> sales, BigDecimal budget) {
        List<Product> scored = products.stream()
                .sorted(Comparator.comparingDouble((Product product) -> scoreProduct(message, product, sales, budget)).reversed())
                .toList();

        List<Product> highConfidence = scored.stream()
                .filter(product -> computeProductConfidenceScore(product) >= 0.50d)
                .toList();
        List<Product> lowConfidence = scored.stream()
                .filter(product -> computeProductConfidenceScore(product) < 0.50d)
                .toList();

        List<Product> prioritized = new ArrayList<>(6);
        for (Product product : highConfidence) {
            if (prioritized.size() >= 6) {
                break;
            }
            prioritized.add(product);
        }
        for (Product product : lowConfidence) {
            if (prioritized.size() >= 6) {
                break;
            }
            prioritized.add(product);
        }
        return prioritized;
    }

    private double scoreProduct(String message, Product product, Map<UUID, Integer> sales, BigDecimal budget) {
        String content = (safe(product.getName()) + " " + safe(product.getDescription()) + " " + safe(product.getTags())).toLowerCase(Locale.ROOT);
        double score = Math.min(sales.getOrDefault(product.getId(), 0), 20) * 0.08d;
        String scene = detectScene(message);
        String intent = detectIntentCategory(message);
        List<String> preferenceKeywords = extractPreferenceKeywords(message);
        double semanticRelevance = retrievalGatewayService.relevanceScore(message, product);
        double confidenceScore = computeProductConfidenceScore(product);
        boolean strictIntentMatch = intent != null && matchesStrictIntent(product, intent);

        score += semanticRelevance * 5.0d;

        if (scene != null && content.contains(scene)) {
            score += 1.4d;
        }
        if (strictIntentMatch) {
            score += 2.6d;
        }
        if (intent != null) {
            if (strictIntentMatch) {
                score += 3.2d;
            } else if (!allowsCrossCategoryResults(message)) {
                score -= 4.5d;
            }
        }
        String normalizedMessage = safe(message).toLowerCase(Locale.ROOT).trim();
        if (!normalizedMessage.isEmpty() && content.contains(normalizedMessage)) {
            score += 2.2d;
        }
        if (messageKeywordScore(message, content) > 0) {
            score += messageKeywordScore(message, content) * 0.55d;
        }
        long preferenceHits = preferenceKeywords.stream().filter(content::contains).count();
        if (preferenceHits > 0) {
            score += preferenceHits * 1.15d;
        }
        if (fallbackReason(message, product).contains("更适合")) {
            score += 1.8d;
        }
        if (budget != null && product.getPrice() != null) {
            if (product.getPrice().compareTo(budget) <= 0) {
                score += 1.2d;
                BigDecimal gap = budget.subtract(product.getPrice()).abs();
                score += Math.max(0.0d, 1.0d - gap.doubleValue() / Math.max(1.0d, budget.doubleValue()));
            } else {
                score -= 0.6d;
            }
        }

        // 数据质量分层：优先高置信商品进入前列，低质量爬虫描述降权。
        score += confidenceScore * 2.4d;
        if (isLowQualityCrawlerProduct(product)) {
            score -= 2.2d;
        }

        return score;
    }

    private long messageKeywordScore(String message, String content) {
        return Pattern.compile("[\\s,，。！？!?:：/]+").splitAsStream(safe(message).toLowerCase(Locale.ROOT))
                .map(String::trim)
                .filter(token -> token.length() >= 2)
                .distinct()
                .filter(content::contains)
                .count();
    }

    private String buildFallbackRecommendation(Product product, String message, int soldCount, BigDecimal budget, boolean primary) {
        String title = primary ? "1. 主推：" : "2. 备选：";
        if (!primary && soldCount < 1) {
            title = "3. 备选：";
        }
        String budgetLine = budget == null || product.getPrice() == null
                ? "预算判断：未限制预算"
                : (product.getPrice().compareTo(budget) <= 0 ? "预算判断：在当前预算内" : "预算判断：略高于当前预算");
        return title + safe(product.getName())
                + "\n   - 价格：" + formatPrice(product.getPrice()) + " 元"
                + "\n   - 推荐理由：" + recommendationReason(message, product, soldCount, budget)
                + "\n   - 适合场景：" + fallbackReason(message, product)
                + "\n   - " + budgetLine;
    }

    private List<String> parseTags(String tags) {
        if (isBlank(tags)) {
            return List.of();
        }
        return Pattern.compile("[,，]")
                .splitAsStream(tags)
                .map(String::trim)
                .filter(tag -> !tag.isEmpty())
                .limit(4)
                .toList();
    }

    /**
     * 清洗 AI 回复：剥离推理模型的 &lt;think&gt; 块，规范换行符。
     * 返回 null 表示回复为空（调用方应路由到 buildFallbackPayload）。
     */
    private String normalizeReply(String reply) {
        if (isBlank(reply)) return null;
        // 剥离 deepseek-reasoner 等推理模型的 <think>…</think> 思维链块
        String cleaned = reply.replaceAll("(?s)<think>.*?</think>\\s*", "")
                              .replace("\r\n", "\n")
                              .trim();
        return cleaned.isEmpty() ? null : cleaned;
    }

    private String fallbackReason(String message, Product product) {
        String content = (safe(product.getName()) + " " + safe(product.getDescription()) + " " + safe(product.getTags())).toLowerCase(Locale.ROOT);
        String query = safe(message).toLowerCase(Locale.ROOT);
        String intent = detectIntentCategory(message);
        if (intent != null && matchesStrictIntent(product, intent)) {
            return "更适合你当前明确提出的品类需求";
        }
        if ((query.contains("猫") || query.contains("宠物") || query.contains("猫粮") || query.contains("狗粮"))
                && containsAnyKeyword(content, List.of("宠物", "猫粮", "猫咪", "宠粮", "狗狗", "外出"))) {
            return "更适合当前宠物补货和外出用品场景";
        }
        if (query.contains("降噪") && content.contains("降噪")) {
            return "更适合通勤和安静聆听场景";
        }
        if ((query.contains("办公") || query.contains("笔记本")) && (content.contains("办公") || content.contains("轻薄"))) {
            return "更适合办公和便携使用场景";
        }
        if (query.contains("运动") && (content.contains("运动") || content.contains("健康") || content.contains("手表") || content.contains("手环"))) {
            return "更适合运动记录和健康监测";
        }
        if (query.contains("键盘") && content.contains("键盘")) {
            return "更适合提升输入效率和桌面体验";
        }
        return "和你的需求匹配度较高";
    }

    private List<Product> filterByIntent(String message, List<Product> products) {
        String intent = detectIntentCategory(message);
        if (intent == null) {
            return products;
        }

        List<Product> strictMatched = products.stream()
                .filter(product -> matchesStrictIntent(product, intent))
                .toList();

        if (!allowsCrossCategoryResults(message) && !strictMatched.isEmpty()) {
            return strictMatched;
        }

        List<String> keywords = intentKeywords(intent);

        List<Product> primaryMatched = products.stream()
                .filter(product -> containsAnyKeyword((safe(product.getName()) + " " + safe(product.getTags())).toLowerCase(Locale.ROOT), keywords))
                .toList();

        if (primaryMatched.size() >= Math.min(2, products.size())) {
            return primaryMatched;
        }

        List<Product> matched = products.stream()
                .filter(product -> {
                    String content = (safe(product.getName()) + " " + safe(product.getDescription()) + " " + safe(product.getTags()))
                            .toLowerCase(Locale.ROOT);
                    return containsAnyKeyword(content, keywords);
                })
                .toList();
        return matched.isEmpty() ? products : matched;
    }

    private boolean matchesStrictIntent(Product product, String intent) {
        String content = (safe(product.getName()) + " " + safe(product.getDescription()) + " " + safe(product.getTags()))
                .toLowerCase(Locale.ROOT);
        return switch (intent) {
            case "耳机" -> containsAnyKeyword(content, List.of("耳机", "headphone", "耳麦", "buds", "freebuds", "xm5", "airpods"))
                    && !containsAnyKeyword(content, List.of("充电器", "charger", "快充", "氮化镓", "电源"));
            case INTENT_LIFE_SUPPLIES -> containsAnyKeyword(content, lifeSuppliesKeywords())
                && !containsAnyKeyword(content, nonLifeSuppliesKeywords());
            case "笔记本" -> containsAnyKeyword(content, List.of("笔记本", "laptop", "轻薄本", "电脑", "notebook", "ultrabook", "办公本", "游戏本"))
                && !containsAnyKeyword(content, List.of("充电器", "charger", "快充", "氮化镓", "充电头", "电源", "鼠标", "mouse", "键盘", "keyboard", "显示器", "monitor", "支架", "扩展坞", "dock", "背包", "内胆包"));
            case "充电器" -> containsAnyKeyword(content, List.of("充电器", "charger", "快充", "氮化镓", "充电头"));
            case "手表" -> containsAnyKeyword(content, List.of("手表", "watch"));
            case "手环" -> containsAnyKeyword(content, List.of("手环", "band"));
            case "键盘" -> containsAnyKeyword(content, List.of("键盘", "keyboard"));
            case "游戏机" -> containsAnyKeyword(content, List.of("游戏机", "switch", "console", "掌机"));
            case INTENT_APPAREL -> containsAnyKeyword(content, List.of("卫衣", "t恤", "tee", "hoodie", "sweatshirt", "夹克", "外套", "衬衫", "服饰", "穿搭", "连帽"))
                    && !containsAnyKeyword(content, List.of("充电器", "charger", "键盘", "keyboard", "猫粮", "宠物", "手表", "手环"));
            case "宠物" -> containsAnyKeyword(content, List.of("宠物", "猫粮", "猫咪", "狗粮", "狗狗", "宠物包", "猫砂"));
            default -> containsAnyKeyword(content, intentKeywords(intent));
        };
    }

    private boolean allowsCrossCategoryResults(String message) {
        String text = safe(message).toLowerCase(Locale.ROOT);
        return text.contains("搭配")
                || text.contains("配件")
                || text.contains("套餐")
                || text.contains("套装")
                || text.contains("一起买")
                || text.contains("顺便")
                || text.contains("组合");
    }

    private List<Product> mergeProducts(List<Product> primaryProducts, List<Product> fallbackProducts) {
        return java.util.stream.Stream.concat(primaryProducts.stream(), fallbackProducts.stream())
                .collect(Collectors.toMap(Product::getId, product -> product, (left, right) -> left, java.util.LinkedHashMap::new))
                .values()
                .stream()
                .toList();
    }

    private String detectIntentCategory(String message) {
        String text = safe(message).toLowerCase(Locale.ROOT);
        if (text.contains("\u8033\u673a") || text.contains("\u964d\u566a") || text.contains("audio") || text.contains("headphone")) {
            return "\u8033\u673a"; // 耳机
        }
        if (containsAnyKeyword(text, lifeSuppliesIntentSignals())) {
            return INTENT_LIFE_SUPPLIES;
        }
        if (text.contains("猫粮") || text.contains("狗粮") || text.contains("宠粮") || text.contains("猫咪") || text.contains("宠物") || text.contains("猫砂") || text.contains("外出包")) {
            return "宠物";
        }
        if (text.contains("笔记本")
                || text.contains("电脑")
                || text.contains("laptop")
                || text.contains("notebook")
                || text.contains("ultrabook")
                || text.contains("轻薄本")
                || text.contains("办公本")
                || text.contains("游戏本")
                || text.contains("macbook")) {
            return "笔记本";
        }
        if (text.contains("充电器") || text.contains("充电头") || text.contains("快充") || text.contains("charger")) {
            return "充电器";
        }
        if (text.contains("手表") || text.contains("watch")) {
            return "手表";
        }
        if (text.contains("手环") || text.contains("band")) {
            return "手环";
        }
        if (text.contains("键盘") || text.contains("keyboard")) {
            return "键盘";
        }
        if (text.contains("游戏机") || text.contains("switch") || text.contains("console")) {
            return "游戏机";
        }
        if (containsAnyKeyword(text, List.of("卫衣", "t恤", "t-shirt", "tee", "夹克", "外套", "衬衫", "服装", "穿搭", "连帽", "hoodie", "sweatshirt"))) {
            return INTENT_APPAREL;
        }
        return null;
    }

    private List<String> intentKeywords(String intent) {
        return switch (intent) {
            case "耳机" -> List.of("耳机", "headphone", "freebuds", "xm5", "buds");
            case INTENT_LIFE_SUPPLIES -> lifeSuppliesKeywords();
            case INTENT_APPAREL -> List.of("卫衣", "t恤", "tee", "hoodie", "sweatshirt", "夹克", "外套", "衬衫", "服饰", "穿搭", "连帽");
            case "宠物" -> List.of("宠物", "猫粮", "猫咪", "宠粮", "狗粮", "狗狗", "外出", "宠物包", "猫砂");
            case "笔记本" -> List.of("笔记本", "laptop", "电脑", "轻薄本", "notebook", "ultrabook", "办公本", "游戏本");
            case "充电器" -> List.of("充电器", "charger", "快充", "氮化镓", "充电头");
            case "手表" -> List.of("手表", "watch");
            case "手环" -> List.of("手环", "band");
            case "键盘" -> List.of("键盘", "keyboard");
            case "游戏机" -> List.of("游戏机", "console", "switch");
            default -> List.of(intent.toLowerCase(Locale.ROOT));
        };
    }

    private boolean containsAnyKeyword(String content, List<String> keywords) {
        return keywords.stream().anyMatch(content::contains);
    }

    private String detectScene(String message) {
        String text = safe(message).toLowerCase(Locale.ROOT);
        if (text.contains("通勤") || text.contains("地铁")) {
            return "通勤";
        }
        if (text.contains("会议") || text.contains("视频") || text.contains("开会")) {
            return "会议";
        }
        if (text.contains("办公") || text.contains("桌面") || text.contains("写方案")) {
            return "办公";
        }
        if (text.contains("学习") || text.contains("上课") || text.contains("学生") || text.contains("宿舍学习")) {
            return "学习";
        }
        if (text.contains("游戏") || text.contains("打游戏") || text.contains("电竞")) {
            return "游戏";
        }
        if (text.contains("跑步") || text.contains("健身") || text.contains("训练")) {
            return "运动";
        }
        if (text.contains("宿舍") || text.contains("娱乐") || text.contains("影音")) {
            return "娱乐";
        }
        if (text.contains("出差") || text.contains("差旅") || text.contains("移动")) {
            return "差旅";
        }
        if (text.contains("猫") || text.contains("宠物") || text.contains("喂养") || text.contains("看诊")) {
            return "宠物";
        }
        return null;
    }

    private String formatSceneSummary(String message) {
        String scene = detectScene(message);
        if (scene == null) {
            return "未明确场景，按综合需求推荐";
        }
        return switch (scene) {
            case "通勤" -> "地铁通勤 / 日常外出";
            case "会议" -> "视频会议 / 通话协作";
            case "办公" -> "桌面办公 / 高效输入";
            case "学习" -> "学生学习 / 上课携带 / 资料整理";
            case "游戏" -> "游戏娱乐 / 性能优先";
            case "运动" -> "跑步训练 / 健康监测";
            case "娱乐" -> "宿舍娱乐 / 家庭影音";
            case "差旅" -> "差旅携带 / 移动办公";
            case "宠物" -> "宠物喂养 / 日常补货 / 外出看诊";
            default -> scene;
        };
    }

    private String formatIntentLabel(String intent, String message) {
        String base = formatIntent(intent);
        String scene = formatSceneSummary(message);
        if (scene.startsWith("未明确场景")) {
            return base;
        }
        return base + " / " + scene;
    }

    private Mono<AdvisorExecution> generateRealtimeAdvice(UUID userId, String message) {
        return Mono.fromCallable(() -> runRealtimeAdviceWithRetries(userId, message))
                .subscribeOn(Schedulers.boundedElastic());
    }

    private AdvisorExecution runRealtimeAdviceWithRetries(UUID userId, String message) {
        AiProperties.Provider provider = currentProvider();
        int maxAttempts = resolveRealtimeAttempts(provider);
        RuntimeException lastError = null;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            if (attempt > 1) {
                invalidateAiCaches();
                sleepBeforeRetry(attempt);
            }

            try {
                ShoppingAdvisorAiService aiService = getOrCreateShoppingAdvisorAiService();
                if (aiService == null) {
                    throw new IllegalStateException(getModelUnavailableReason());
                }
                return runAdvisorWithTools(userId, message, aiService);
            } catch (RuntimeException error) {
                lastError = error;
                String summary = summarizeError(error);
                boolean retryable = shouldRetryRealtimeAdvice(error, attempt, maxAttempts);
                log.warn("AI realtime attempt {}/{} failed on provider {}: {}", attempt, maxAttempts, currentProviderName(), summary);
                if (!retryable) {
                    throw error;
                }
            }
        }

        invalidateAiCaches();
        throw lastError == null ? new IllegalStateException("AI_REALTIME_RETRY_EXHAUSTED") : lastError;
    }

    private int resolveRealtimeAttempts(AiProperties.Provider provider) {
        int providerRetries = resolveMaxRetries(provider);
        int attempts = providerRetries + 1;
        return Math.max(MIN_REALTIME_ATTEMPTS, Math.min(MAX_REALTIME_ATTEMPTS, attempts));
    }

    private boolean shouldRetryRealtimeAdvice(RuntimeException error, int attempt, int maxAttempts) {
        if (attempt >= maxAttempts) {
            return false;
        }

        String summary = summarizeError(error).toLowerCase(Locale.ROOT);
        if (summary.contains("insufficient balance")
                || summary.contains("invalid api key")
                || summary.contains("incorrect api key")
                || summary.contains("model does not exist")
                || summary.contains("ai_api_key_missing")
                || summary.contains("ai_model_name_missing")) {
            return false;
        }

        return summary.contains("timeout")
                || summary.contains("timed_out")
                || summary.contains("interrupted")
                || summary.contains("connection reset")
                || summary.contains("connection refused")
                || summary.contains("read timed out")
                || summary.contains("temporarily unavailable")
                || summary.contains("rate limit")
                || summary.contains("too many requests")
                || summary.contains("server error")
                || summary.contains("502")
                || summary.contains("503")
                || summary.contains("504")
                || summary.contains("unexpected end")
                || summary.contains("ssl")
                || summary.contains("network")
                || summary.contains("io exception")
                || summary.contains("interruptedioexception")
                || summary.contains("http2");
    }

    private void sleepBeforeRetry(int attempt) {
        long delayMillis = Math.min(1800L, 350L * attempt);
        try {
            Thread.sleep(delayMillis);
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("AI_RETRY_INTERRUPTED", interruptedException);
        }
    }

    private void invalidateAiCaches() {
        chatLanguageModel = null;
        shoppingAdvisorAiService = null;
        directShoppingAdvisorAiService = null;
    }

    private AdvisorExecution runAdvisorWithTools(UUID userId, String message, ShoppingAdvisorAiService aiService) {
        advisorToolContext.begin(userId, message);
        try {
            String reply = aiService.advise(buildToolAdvisorInput(userId, message));
            AdvisorToolContext.AdvisorToolSnapshot snapshot = advisorToolContext.finish();
            return new AdvisorExecution(reply, snapshot);
        } catch (RuntimeException error) {
            advisorToolContext.clear();
            throw error;
        }
    }

    private String buildToolAdvisorInput(UUID userId, String message) {
        BigDecimal budget = extractBudget(message);
        List<ChatMessage> history = chatMessageRepository.findByUserId(userId)
                .collectList()
                .blockOptional(Duration.ofSeconds(3))
                .orElse(List.of());
        return "用户原始问题：" + message + "\n"
                + "结构化需求摘要：\n" + buildDemandSummary(message, budget, history) + "\n"
                + "工具调用提示：\n" + buildToolRoutingHints(message) + "\n"
                + "请先基于上述需求理解用户，再调用工具检索并给出推荐。";
    }

    private String buildToolRoutingHints(String message) {
        String text = safe(message).toLowerCase(Locale.ROOT);
        List<String> hints = new ArrayList<>();
        hints.add("商品推荐前至少调用一次商品检索工具。");

        if (containsAnyKeyword(text, List.of("天气", "下雨", "气温", "温度", "冷不冷", "热不热"))) {
            hints.add("如果涉及天气，请优先调用 getWeatherSummary(city)。");
        }
        if (containsAnyKeyword(text, List.of("日期", "哪天", "周末", "周六", "周日", "出差", "出行", "节假日", "安排"))) {
            hints.add("如果涉及日期、节假日或出行安排，请优先调用 getDateTravelContext(dateText, city)。");
        }
        if (containsAnyKeyword(text, List.of("怎么穿", "穿搭", "穿什么", "鞋", "衣服", "外套", "通勤穿搭", "运动风"))) {
            hints.add("如果涉及穿搭、鞋服或出行着装，请优先调用 getOutfitAdvice(city, scene, stylePreference)。");
        }

        return String.join("\n", hints);
    }

    private String buildDemandSummary(String message, BigDecimal budget, List<ChatMessage> history) {
        String intent = formatIntentLabel(detectIntentCategory(message), message);
        String scene = formatSceneSummary(message);
        String preferences = formatPreferenceSummary(message);
        String constraints = formatConstraintSummary(message, budget);
        String historySummary = history.stream()
                .sorted(Comparator.comparing(ChatMessage::getCreatedAt))
                .skip(Math.max(0, history.size() - 4L))
                .map(item -> ("USER".equals(item.getRole()) ? "用户" : "助手") + "：" + safe(item.getContent()))
                .collect(Collectors.joining(" | "));

        return "- 品类/需求类型：" + intent + "\n"
                + "- 使用场景：" + scene + "\n"
                + "- 预算：" + formatBudgetSummary(budget) + "\n"
                + "- 偏好信号：" + preferences + "\n"
                + "- 约束与风险：" + constraints + "\n"
                + "- 最近上下文：" + (historySummary.isBlank() ? "暂无" : historySummary);
    }

    private List<String> extractPreferenceKeywords(String message) {
        String text = safe(message).toLowerCase(Locale.ROOT);
        List<String> preferences = new ArrayList<>();
        if (containsAnyKeyword(text, List.of("轻便", "便携", "小巧", "轻薄", "方便带"))) preferences.add("轻薄");
        if (containsAnyKeyword(text, List.of("续航", "耐用", "电池", "待机"))) preferences.add("续航");
        if (containsAnyKeyword(text, List.of("降噪", "安静", "隔音"))) preferences.add("降噪");
        if (containsAnyKeyword(text, List.of("音质", "听歌", "声音"))) preferences.add("音质");
        if (containsAnyKeyword(text, List.of("通话", "开会", "会议", "麦克风"))) preferences.add("通话");
        if (containsAnyKeyword(text, List.of("性能", "流畅", "游戏", "剪辑", "渲染"))) preferences.add("性能");
        if (containsAnyKeyword(text, List.of("办公", "文档", "表格", "码字", "学习"))) preferences.add("办公");
        if (containsAnyKeyword(text, List.of("舒适", "佩戴", "不夹头"))) preferences.add("舒适");
        return preferences.stream().distinct().toList();
    }

    private String formatPreferenceSummary(String message) {
        List<String> preferences = extractPreferenceKeywords(message);
        return preferences.isEmpty() ? "未明确偏好，按综合体验理解" : String.join(" / ", preferences);
    }

    private String formatConstraintSummary(String message, BigDecimal budget) {
        List<String> constraints = new ArrayList<>();
        if (budget != null) {
            constraints.add("优先预算内");
        }
        String text = safe(message).toLowerCase(Locale.ROOT);
        if (text.contains("不要") || text.contains("别") || text.contains("不能")) {
            constraints.add("存在明确排斥条件，推荐时避免反向理解");
        }
        if (text.contains("性价比") || text.contains("划算")) {
            constraints.add("价格敏感");
        }
        if (text.contains("专业") || text.contains("高端") || text.contains("旗舰")) {
            constraints.add("体验优先");
        }
        return constraints.isEmpty() ? "未提到明显限制" : String.join(" / ", constraints);
    }

    private Mono<String> generateDirectRealtimeAdvice(String message,
                                                      List<Product> products,
                                                      List<ChatMessage> history,
                                                      List<OrderItem> soldItems,
                                                      BigDecimal budget,
                                                      boolean noProductWithinBudget) {
        return Mono.fromCallable(() -> runDirectRealtimeAdvice(message, products, history, soldItems, budget, noProductWithinBudget))
                .subscribeOn(Schedulers.boundedElastic());
    }

    private String runDirectRealtimeAdvice(String message,
                                           List<Product> products,
                                           List<ChatMessage> history,
                                           List<OrderItem> soldItems,
                                           BigDecimal budget,
                                           boolean noProductWithinBudget) {
        DirectShoppingAdvisorAiService directAiService = getOrCreateDirectShoppingAdvisorAiService();
        if (directAiService == null) {
            throw new IllegalStateException(getModelUnavailableReason());
        }

        String prompt = buildPrompt(message, products, history, soldItems, budget, noProductWithinBudget);
        return directAiService.advise(prompt);
    }

    private ChatAdvicePayload buildSuccessPayload(String reply,
                                                 List<ChatRecommendationResponse> recommendations,
                                                 List<ChatRecommendationResponse> relatedRecommendations,
                                                 List<ChatInsightResponse> insights,
                                                 String detectedIntent,
                                                 String budgetSummary) {
        recordSuccess();
        String alignedReply = alignReplyWithRecommendations(reply, recommendations, detectedIntent, budgetSummary);
        return new ChatAdvicePayload(
            alignedReply,
                recommendations,
                relatedRecommendations,
                enrichInsights(insights, false, activeModelLabel()),
                detectedIntent,
                budgetSummary,
                false);
    }

    private ChatAdvicePayload buildFallbackPayload(String reply,
                                                  List<ChatRecommendationResponse> recommendations,
                                                  List<ChatRecommendationResponse> relatedRecommendations,
                                                  List<ChatInsightResponse> insights,
                                                  String detectedIntent,
                                                  String budgetSummary,
                                                  String fallbackReason) {
        recordFallback(fallbackReason);
        String enhancedReply = appendFallbackDiagnostics(reply, fallbackReason);
        return new ChatAdvicePayload(
            enhancedReply,
                recommendations,
                relatedRecommendations,
                enrichInsights(insights, true, fallbackReason),
                detectedIntent,
                budgetSummary,
                true);
    }

    private List<ChatInsightResponse> enrichInsights(List<ChatInsightResponse> insights, boolean fallback, String detail) {
        List<ChatInsightResponse> enriched = new ArrayList<>();
        enriched.add(new ChatInsightResponse("AI模式", fallback ? "规则兜底" : "实时模型"));
        enriched.add(new ChatInsightResponse(fallback ? "兜底原因" : "模型信息", detail));
        if (fallback) {
            List<String> solutions = resolveFallbackSolutions(detail);
            enriched.add(new ChatInsightResponse("恢复建议", String.join("；", solutions)));
        }
        enriched.addAll(insights);
        return List.copyOf(enriched);
    }

    private String appendFallbackDiagnostics(String reply, String fallbackReason) {
        List<String> solutions = resolveFallbackSolutions(fallbackReason);
        String reasonLine = "兜底原因：" + safe(fallbackReason);
        String solutionLine = "建议处理：" + String.join("；", solutions);
        return safe(reply) + "\n\n" + reasonLine + "\n" + solutionLine;
    }

    private List<String> resolveFallbackSolutions(String reason) {
        String normalized = safe(reason).toLowerCase(Locale.ROOT);
        if (normalized.contains("timeout") || normalized.contains("timed_out") || normalized.contains("interrupted") || normalized.contains("read timed out")) {
            return List.of(
                    "将模型超时时间提升到 120-180 秒",
                    "检查代理与网络稳定性，避免 TLS/网关抖动",
                    "降低提示词长度并减少上下文冗余");
        }
        if (normalized.contains("insufficient balance")) {
            return List.of(
                    "补充模型服务余额",
                    "切换到可用的备用模型",
                    "增加余额不足的监控告警");
        }
        if (normalized.contains("invalid api key") || normalized.contains("incorrect api key") || normalized.contains("ai_api_key_missing")) {
            return List.of(
                    "检查并更新 API Key",
                    "确认运行环境已注入密钥",
                    "重启服务使新配置生效");
        }
        if (normalized.contains("model does not exist") || normalized.contains("ai_model_name_missing")) {
            return List.of(
                    "确认模型名是否可用",
                    "切换到已验证可用模型",
                    "保存配置后重启服务并复测");
        }
        if (normalized.contains("connection refused") || normalized.contains("network") || normalized.contains("ssl") || normalized.contains("http2")) {
            return List.of(
                    "检查出口网络和代理配置",
                    "确认模型服务地址可达",
                    "必要时改用更稳定线路或供应商");
        }
        if (normalized.contains("no_ranked_products") || normalized.contains("no_products")) {
            return List.of(
                    "补充商品数据并提高类目覆盖",
                    "检查检索关键词和类目映射",
                    "确认向量索引已刷新完成");
        }
        return List.of(
                "检查 AI 服务可用性和响应时延",
                "查看后端日志定位失败环节",
                "按错误码重试并观察是否可恢复");
    }

    private String alignReplyWithRecommendations(String reply,
                                                 List<ChatRecommendationResponse> recommendations,
                                                 String detectedIntent,
                                                 String budgetSummary) {
        if (recommendations == null || recommendations.isEmpty()) {
            return safe(reply);
        }
        String normalizedReply = safe(reply).trim();
        if (normalizedReply.isBlank()) {
            normalizedReply = "需求速览：已根据你的场景和预算完成检索与排序。";
        }

        String alignedSection = buildCardAlignedRecommendationSection(recommendations);
        return normalizedReply
                + "\n\n商品卡一致推荐（最终候选）：\n"
                + "- 需求类型：" + safe(detectedIntent) + "\n"
                + "- 预算范围：" + safe(budgetSummary) + "\n"
                + alignedSection
                + "\n\n补充建议：若你希望更省预算、指定品牌、或更看重某一指标（如续航/舒适/性能），我可在这 3 款内继续精排。";
    }

    private String extractSummarySection(String reply) {
        String normalized = safe(reply).replace("\r\n", "\n").trim();
        if (normalized.isBlank()) {
            return "用户偏好信息较少，已按当前需求做稳妥推荐";
        }
        String oneLine = normalized.replaceAll("[\\n\\t]+", " ").replaceAll("\\s+", " ").trim();
        if (oneLine.length() > 90) {
            return oneLine.substring(0, 90) + "...";
        }
        return oneLine;
    }

    private String buildCardAlignedRecommendationSection(List<ChatRecommendationResponse> recommendations) {
        String lines = recommendations.stream()
                .limit(MAX_RECOMMENDATIONS)
                .map(item -> "- " + safe(item.name())
                        + "（" + formatPrice(item.price()) + "元，"
                        + (item.withinBudget() ? "预算内" : "预算外")
                        + "）\n"
                        + "  理由：" + safe(item.reason())
                        + (item.tags() == null || item.tags().isEmpty() ? "" : "\n  标签：" + String.join(" / ", item.tags()))
                        + "\n  热度：" + Math.max(0, item.salesCount()) + " 件成交")
                .collect(Collectors.joining("\n"));
        return "推荐清单（与商品卡严格一致）：\n" + lines;
    }

    private String buildBudgetReason(Product product, BigDecimal budget) {
        if (budget == null || product.getPrice() == null) {
            return "";
        }
        if (product.getPrice().compareTo(budget) <= 0) {
            BigDecimal margin = budget.subtract(product.getPrice()).max(BigDecimal.ZERO);
            return "在预算内，预算余量约 " + formatPrice(margin) + " 元";
        }
        BigDecimal exceed = product.getPrice().subtract(budget).abs();
        return "超预算约 " + formatPrice(exceed) + " 元，但综合匹配度较高";
    }

    private String formatSceneTag(String scene) {
        return switch (scene) {
            case "通勤" -> "通勤外出";
            case "会议" -> "会议通话";
            case "办公" -> "办公学习";
            case "学习" -> "学习携带";
            case "游戏" -> "游戏娱乐";
            case "运动" -> "运动健康";
            case "娱乐" -> "影音娱乐";
            case "差旅" -> "差旅移动";
            case "宠物" -> "宠物喂养";
            default -> scene;
        };
    }

    private String extractCapabilityHighlight(String message, Product product) {
        String text = safe(message).toLowerCase(Locale.ROOT);
        String content = (safe(product.getName()) + " " + safe(product.getDescription()) + " "
                + safe(product.getSellingPoints()) + " " + safe(product.getTags())).toLowerCase(Locale.ROOT);

        if (containsAnyKeyword(text, List.of("降噪", "安静", "隔音")) && containsAnyKeyword(content, List.of("降噪", "主动降噪", "anc"))) {
            return "降噪能力与需求一致";
        }
        if (containsAnyKeyword(text, List.of("续航", "待机", "电池")) && containsAnyKeyword(content, List.of("续航", "电池", "待机", "长续航"))) {
            return "续航表现更贴合你的关注点";
        }
        if (containsAnyKeyword(text, List.of("轻便", "便携", "轻薄")) && containsAnyKeyword(content, List.of("轻", "薄", "便携", "小巧"))) {
            return "便携和日常携带体验更友好";
        }
        if (containsAnyKeyword(text, List.of("性能", "流畅", "游戏", "剪辑")) && containsAnyKeyword(content, List.of("性能", "高刷", "旗舰", "流畅", "游戏"))) {
            return "性能与流畅度更符合高负载需求";
        }
        return "";
    }

    private List<ChatInsightResponse> appendToolInsights(List<ChatInsightResponse> insights,
                                                         AdvisorToolContext.AdvisorToolSnapshot snapshot) {
        List<ChatInsightResponse> enriched = new ArrayList<>(insights);
        enriched.add(new ChatInsightResponse("Tool命中商品", String.valueOf(snapshot.products().size())));
        enriched.add(new ChatInsightResponse("Tool调用轨迹", snapshot.traces().isEmpty() ? "无" : String.join(" | ", snapshot.traces())));
        return List.copyOf(enriched);
    }

    private List<ChatInsightResponse> appendDirectAiRecoveryInsights(List<ChatInsightResponse> insights, String reason) {
        List<ChatInsightResponse> enriched = new ArrayList<>(insights);
        enriched.add(new ChatInsightResponse("AI恢复链路", "Tools 协议失败后自动切换到直连模型生成"));
        enriched.add(new ChatInsightResponse("Tools失败原因", reason));
        return List.copyOf(enriched);
    }

    private List<ChatInsightResponse> appendDirectAiBypassInsights(List<ChatInsightResponse> insights, String modelName) {
        List<ChatInsightResponse> enriched = new ArrayList<>(insights);
        enriched.add(new ChatInsightResponse("AI链路", "当前模型跳过 Tools，直接走实时生成"));
        enriched.add(new ChatInsightResponse("模型信息", currentProviderName() + " / " + modelName));
        return List.copyOf(enriched);
    }

    private void recordSuccess() {
        consecutiveFallbacks.set(0);
        runtimeStatusRef.set(new AiRuntimeStatus(currentProviderName(), activeModelName(), false, "OK", Instant.now(), 0));
    }

    private void recordFallback(String reason) {
        int fallbackCount = consecutiveFallbacks.incrementAndGet();
        runtimeStatusRef.set(new AiRuntimeStatus(currentProviderName(), activeModelName(), true, reason, Instant.now(), fallbackCount));
        log.warn("AI advisor fallback triggered: {}", reason);
    }

    private String getModelUnavailableReason() {
        AiProperties.Provider provider = currentProvider();
        if (provider == null) {
            return "AI_PROVIDER_NOT_SUPPORTED";
        }
        if (isBlank(provider.getApiKey())) {
            return "AI_API_KEY_MISSING";
        }
        if (isBlank(provider.getBaseUrl())) {
            return "AI_BASE_URL_MISSING";
        }
        if (isBlank(resolveModelName(provider))) {
            return "AI_MODEL_NAME_MISSING";
        }
        return "AI_MODEL_INIT_FAILED";
    }

    private String previewConfigurationReason() {
        AiProperties.Provider provider = currentProvider();
        if (provider == null) {
            return "AI_PROVIDER_NOT_SUPPORTED";
        }
        if (isBlank(provider.getApiKey())) {
            return "AI_API_KEY_MISSING";
        }
        if (isBlank(provider.getBaseUrl())) {
            return "AI_BASE_URL_MISSING";
        }
        if (isBlank(resolveModelName(provider))) {
            return "AI_MODEL_NAME_MISSING";
        }
        return "READY";
    }

    private String summarizeError(Throwable error) {
        Throwable rootCause = error;
        while (rootCause != null && rootCause.getCause() != null && rootCause.getCause() != rootCause) {
            rootCause = rootCause.getCause();
        }

        if (rootCause instanceof java.io.InterruptedIOException) {
            return "AI_REQUEST_INTERRUPTED_OR_TIMED_OUT";
        }

        String fullMessage = safe(error == null ? "" : error.toString()).toLowerCase(Locale.ROOT);
        if (fullMessage.contains("insufficient balance")) {
            return "AI_PROVIDER_INSUFFICIENT_BALANCE";
        }

        String message = error == null ? "UNKNOWN" : safe(error.getMessage()).replace('\n', ' ').replace('\r', ' ').trim();
        if (message.isEmpty()) {
            message = error == null ? "UNKNOWN" : error.getClass().getSimpleName();
        }
        String summary = error == null ? "AI_MODEL_CALL_FAILED" : error.getClass().getSimpleName() + ": " + message;
        return summary.length() > 180 ? summary.substring(0, 180) : summary;
    }

    private AiProperties.Provider currentProvider() {
        String providerName = currentProviderName().toLowerCase(Locale.ROOT);
        return switch (providerName) {
            case "chatgpt", "openai", "openai-compatible" -> aiProperties.getChatgpt();
            case "siliconflow" -> aiProperties.getSiliconflow();
            case "deepseek" -> aiProperties.getDeepseek();
            default -> null;
        };
    }

    private String currentProviderName() {
        return isBlank(aiProperties.getProvider()) ? "deepseek" : aiProperties.getProvider();
    }

    private String activeModelName() {
        AiProperties.Provider provider = currentProvider();
        String modelName = resolveModelName(provider);
        if (provider == null || isBlank(modelName)) {
            return "unknown";
        }
        return modelName;
    }

    private String activeModelLabel() {
        return currentProviderName() + " / " + activeModelName();
    }

    private boolean supportsToolCalling() {
        String provider = currentProviderName().toLowerCase(Locale.ROOT);
        String modelName = activeModelName().toLowerCase(Locale.ROOT);
        // DeepSeek 推理模型不支持 Function Calling（回复在 reasoning_content 而非 content）
        if ("deepseek".equals(provider) && modelName.contains("reasoner")) {
            log.warn("Model {} does not support tool calling, switching to direct path", modelName);
            return false;
        }
        // o1/o3 系列也不支持 Function Calling
        if (("openai".equals(provider) || "chatgpt".equals(provider))
                && (modelName.startsWith("o1") || modelName.startsWith("o3"))) {
            return false;
        }
        return true;
    }

    private ChatLanguageModel getOrCreateModel() {
        ChatLanguageModel cached = chatLanguageModel;
        if (cached != null) {
            return cached;
        }

        synchronized (this) {
            if (chatLanguageModel != null) {
                return chatLanguageModel;
            }

            AiProperties.Provider provider = currentProvider();
            if (provider == null || isBlank(provider.getApiKey()) || isBlank(provider.getBaseUrl())) {
                return null;
            }

            if ("deepseek".equals(currentProviderName().toLowerCase(Locale.ROOT))) {
                chatLanguageModel = Http11OpenAiChatModel.builder()
                        .apiKey(provider.getApiKey())
                        .baseUrl(provider.getBaseUrl())
                        .modelName(resolveModelName(provider))
                        .timeout(resolveTimeout(provider))
                        .maxRetries(resolveMaxRetries(provider))
                        .logRequests(provider.isLogRequests())
                        .logResponses(provider.isLogResponses())
                        .temperature(0.35)
                        .build();
            } else {
                chatLanguageModel = OpenAiChatModel.builder()
                        .apiKey(provider.getApiKey())
                        .baseUrl(provider.getBaseUrl())
                        .modelName(resolveModelName(provider))
                        .timeout(resolveTimeout(provider))
                        .maxRetries(resolveMaxRetries(provider))
                        .logRequests(provider.isLogRequests())
                        .logResponses(provider.isLogResponses())
                        .temperature(0.35)
                        .build();
            }
            return chatLanguageModel;
        }
    }

    private Duration resolveTimeout(AiProperties.Provider provider) {
        if (provider == null || provider.getTimeout() == null || provider.getTimeout().isZero() || provider.getTimeout().isNegative()) {
            return Duration.ofSeconds(150);
        }
        return provider.getTimeout().compareTo(Duration.ofSeconds(120)) < 0
                ? Duration.ofSeconds(120)
                : provider.getTimeout();
    }

    private Integer resolveMaxRetries(AiProperties.Provider provider) {
        if (provider == null || provider.getMaxRetries() == null || provider.getMaxRetries() < 0) {
            return 2;
        }
        return Math.max(2, provider.getMaxRetries());
    }

    private String resolveModelName(AiProperties.Provider provider) {
        if (provider != null && !isBlank(provider.getModelName())) {
            return provider.getModelName();
        }

        return switch (currentProviderName().toLowerCase(Locale.ROOT)) {
            case "chatgpt", "openai", "openai-compatible" -> "gpt-4o-mini";
            case "deepseek" -> "deepseek-chat";
            case "siliconflow" -> "";
            default -> "";
        };
    }

    private ShoppingAdvisorAiService getOrCreateShoppingAdvisorAiService() {
        ShoppingAdvisorAiService cached = shoppingAdvisorAiService;
        if (cached != null) {
            return cached;
        }

        synchronized (this) {
            if (shoppingAdvisorAiService != null) {
                return shoppingAdvisorAiService;
            }

            ChatLanguageModel model = getOrCreateModel();
            if (model == null) {
                return null;
            }

            shoppingAdvisorAiService = AiServices.builder(ShoppingAdvisorAiService.class)
                    .chatLanguageModel(model)
                    .tools(shoppingAdvisorTools)
                    .build();
            return shoppingAdvisorAiService;
        }
    }

    private DirectShoppingAdvisorAiService getOrCreateDirectShoppingAdvisorAiService() {
        DirectShoppingAdvisorAiService cached = directShoppingAdvisorAiService;
        if (cached != null) {
            return cached;
        }

        synchronized (this) {
            if (directShoppingAdvisorAiService != null) {
                return directShoppingAdvisorAiService;
            }

            ChatLanguageModel model = getOrCreateModel();
            if (model == null) {
                return null;
            }

            directShoppingAdvisorAiService = AiServices.builder(DirectShoppingAdvisorAiService.class)
                    .chatLanguageModel(model)
                    .build();
            return directShoppingAdvisorAiService;
        }
    }

    private Map<UUID, Integer> aggregateSales(List<OrderItem> soldItems) {
        return soldItems.stream()
                .filter(item -> item.getProductId() != null)
                .collect(Collectors.groupingBy(OrderItem::getProductId, Collectors.summingInt(OrderItem::getQuantity)));
    }

    private String formatPrice(BigDecimal value) {
        return value == null ? "0" : value.stripTrailingZeros().toPlainString();
    }

    private String formatBudgetSummary(BigDecimal budget) {
        return budget == null ? "未限制预算" : ("¥" + formatPrice(budget) + " 以内");
    }

    private String formatIntent(String intent) {
        if (intent == null) {
            return "综合导购";
        }
        return switch (intent) {
            case "耳机" -> "音频与降噪";
            case INTENT_LIFE_SUPPLIES -> "家居日用与生活补货";
            case "宠物" -> "宠物喂养与外出";
            case "笔记本" -> "办公与生产力";
            case "充电器" -> "充电与电源配件";
            case "手表" -> "智能手表";
            case "手环" -> "运动手环";
            case "键盘" -> "键盘外设";
            case "游戏机" -> "娱乐主机";
            default -> intent;
        };
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private List<String> lifeSuppliesIntentSignals() {
        return List.of(
                "\u751f\u6d3b\u7528\u54c1",
                "\u65e5\u7528\u54c1",
                "\u5c45\u5bb6\u7528\u54c1",
                "\u5bb6\u5c45\u7528\u54c1",
                "\u5bb6\u5ead\u8865\u8d27",
                "\u65e5\u5e38\u8865\u8d27",
                "\u751f\u6d3b\u65e5\u7528",
                "\u62bd\u7eb8",
                "\u7eb8\u5dfe",
                "\u6e7f\u5dfe",
                "\u6d17\u8863",
                "\u51dd\u73e0",
                "\u6e05\u6d01",
                "\u65e5\u5316",
                "\u5e8a\u54c1",
                "\u56db\u4ef6\u5957",
                "\u6795\u5934",
                "\u9505",
                "\u7092\u9505",
                "\u53a8\u5177",
                "\u6d01\u9762",
                "\u6da6\u80a4",
                "\u62a4\u80a4",
                "\u6d17\u62a4",
                "\u5bb6\u7eba",
                "\u5bb6\u5c45");
    }

    private List<String> lifeSuppliesKeywords() {
        return List.of(
                "\u65e5\u7528",
                "\u5bb6\u5c45",
                "\u5bb6\u7eba",
                "\u7eb8\u54c1",
                "\u62bd\u7eb8",
                "\u7eb8\u5dfe",
                "\u6e05\u6d01",
                "\u6d17\u8863",
                "\u51dd\u73e0",
                "\u65e5\u5316",
                "\u5e8a\u54c1",
                "\u56db\u4ef6\u5957",
                "\u6795\u5934",
                "\u53a8\u623f",
                "\u53a8\u5177",
                "\u9505\u5177",
                "\u7092\u9505",
                "\u51c0\u996e",
                "\u6e7f\u5dfe",
                "\u6d01\u9762",
                "\u62a4\u80a4",
                "\u6d17\u62a4");
    }

    private List<String> nonLifeSuppliesKeywords() {
        return List.of(
                "\u8033\u673a",
                "headphone",
                "\u7b14\u8bb0\u672c",
                "laptop",
                "\u7535\u8111",
                "keyboard",
                "\u952e\u76d8",
                "watch",
                "\u624b\u8868",
                "band",
                "\u624b\u73af",
                "\u5145\u7535\u5668",
                "charger",
                "\u663e\u793a\u5668",
                "monitor",
                "\u5e73\u677f",
                "tablet",
                "\u6295\u5f71\u4eea",
                "projector",
                "\u76f8\u673a",
                "camera",
                "\u9f20\u6807",
                "mouse",
                "\u5ba0\u7269",
                "\u732b\u7cae",
                "\u72d7\u7cae",
                "\u6bcd\u5a74",
                "\u5a74\u513f",
                "\u7eb8\u5c3f\u88e4",
                "\u98df\u54c1",
                "\u96f6\u98df",
                "\u6587\u5177");
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private BigDecimal extractBudget(String message) {
        if (isBlank(message)) {
            return null;
        }

        Matcher matcher = BUDGET_PATTERN.matcher(message);
        if (matcher.find()) {
            return new BigDecimal(matcher.group(1));
        }

        Matcher flexibleMatcher = FLEX_BUDGET_PATTERN.matcher(message);
        while (flexibleMatcher.find()) {
            String numeric = flexibleMatcher.group(1);
            if (numeric == null || numeric.isBlank()) {
                continue;
            }

            BigDecimal candidate = new BigDecimal(numeric);
            if (candidate.compareTo(BigDecimal.valueOf(50)) >= 0) {
                return candidate;
            }
        }
        return null;
    }

    public record AiRuntimeStatus(String provider,
                                  String modelName,
                                  boolean fallback,
                                  String reason,
                                  Instant updatedAt,
                                  int consecutiveFallbacks) {
        private static AiRuntimeStatus initial(String provider, String modelName) {
            return new AiRuntimeStatus(provider, modelName, false, "NOT_CALLED_YET", Instant.now(), 0);
        }
    }

    private record AdvisorExecution(String reply, AdvisorToolContext.AdvisorToolSnapshot snapshot) {
    }
}
