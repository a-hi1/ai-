package com.example.ecommerce.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.service.AiServices;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@Service
public class AiShoppingAdvisorService {
    private static final Logger log = LoggerFactory.getLogger(AiShoppingAdvisorService.class);

    private static final Pattern STRICT_BUDGET_PATTERN = Pattern.compile("(?:预算|价位|控制在|不超过|以内)?\\s*(\\d{2,6})\\s*(?:元|块|rmb|人民币|以内)?", Pattern.CASE_INSENSITIVE);
    private static final int MAX_MAIN_RECOMMENDATIONS = 3;
    private static final String HEADPHONE_INTENT = "耳机";
    private static final List<String> GENERIC_ACCEPT_WORDS = List.of("都可以", "随便", "无所谓", "你定", "你决定", "没要求", "一般就行");
    private static final List<String> DIRECT_RECOMMEND_WORDS = List.of("直接推荐", "直接看推荐", "直接给我", "跳过提问", "不用问了", "不想回答", "先给推荐");
    private static final int MIN_REQUIRED_DIMENSIONS_TO_RECOMMEND = 3;
    private static final int MIN_REQUIRED_DIMENSIONS_WITHOUT_AI = 4;
    private static final int MAX_CLARIFICATION_TURNS = 3;
    private static final int MIN_CLARIFICATION_TURNS = 1;
    private static final int CLARIFICATION_DIMENSION_COOLDOWN = 3;
    private static final int RECOMMENDATION_PRODUCT_COOLDOWN = 10;
    private static final List<String> FOLLOW_UP_WORDS = List.of("这个", "这个吧", "就这个", "可以", "行", "好", "按你说的", "继续", "嗯", "对", "是的");
    private static final List<String> CATEGORY_SWITCH_SIGNALS = List.of("换个", "改要", "还想要", "另外要", "再要", "还要一个", "我再问", "对了", "顺便问", "帮我也");
        private static final Map<String, List<String>> BRAND_KEYWORDS = Map.ofEntries(
            Map.entry("苹果", List.of("苹果", "apple", "airpods")),
            Map.entry("索尼", List.of("索尼", "sony", "xm")),
            Map.entry("华为", List.of("华为", "huawei", "freebuds")),
            Map.entry("小米", List.of("小米", "redmi", "redmibuds")),
            Map.entry("荣耀", List.of("荣耀", "honor")),
            Map.entry("JBL", List.of("jbl")),
            Map.entry("漫步者", List.of("漫步者", "edifier")),
            Map.entry("倍思", List.of("倍思", "baseus")),
            Map.entry("Anker", List.of("anker", "soundcore"))
        );
        private static final Map<String, List<String>> HEADPHONE_TYPE_KEYWORDS = Map.ofEntries(
            Map.entry("入耳式", List.of("入耳", "入耳式", "tws")),
            Map.entry("半入耳式", List.of("半入耳", "半开放")),
            Map.entry("头戴式", List.of("头戴", "头戴式", "over-ear")),
            Map.entry("挂耳式", List.of("挂耳", "耳挂")),
            Map.entry("骨传导", List.of("骨传导", "open-ear", "开放式"))
        );
        private static final List<String> COLOR_KEYWORDS = List.of(
            "黑色", "白色", "银色", "灰色", "蓝色", "红色", "紫色", "粉色", "绿色", "金色", "深空灰", "午夜色"
        );

    private static final Map<String, List<String>> INTENT_KEYWORDS = Map.ofEntries(
            Map.entry("耳机", List.of("耳机", "headphone", "audio", "buds", "airpods", "freebuds", "降噪")),
            Map.entry("笔记本", List.of("笔记本", "电脑", "laptop", "notebook", "轻薄本", "游戏本", "办公本")),
            Map.entry("手机", List.of("手机", "phone", "smartphone", "iphone", "安卓", "鸿蒙")),
            Map.entry("平板", List.of("平板", "tablet", "ipad")),
            Map.entry("背包", List.of("背包", "双肩包", "书包", "通勤包", "backpack", "rucksack")),
            Map.entry("箱包配饰", List.of("箱包", "行李箱", "挎包", "斜挎", "手提包", "钱包", "配饰")),
            Map.entry("键盘", List.of("键盘", "keyboard")),
            Map.entry("鼠标", List.of("鼠标", "mouse")),
            Map.entry("手表", List.of("手表", "watch")),
            Map.entry("手环", List.of("手环", "band")),
            Map.entry("充电器", List.of("充电器", "快充", "charger", "氮化镓", "充电头")),
            Map.entry("播放器", List.of("mp3", "播放器", "随身听", "音乐播放器")),
            Map.entry("游戏机", List.of("游戏机", "console", "switch", "掌机")),
            Map.entry("宠物", List.of("宠物", "猫粮", "狗粮", "宠粮", "猫砂", "宠物包")),
            Map.entry("生活用品", List.of("生活用品", "日用", "清洁", "纸品", "抽纸", "纸巾", "洗衣液", "洗洁精")),
            Map.entry("食品生鲜", List.of("食品", "生鲜", "零食", "饮料", "牛奶", "咖啡", "粮油", "水果")),
            Map.entry("家居家具", List.of("家居", "家具", "收纳", "床品", "厨具")),
            Map.entry("个护美妆", List.of("个护", "美妆", "护肤", "彩妆", "洗护", "面膜", "精华", "口红")),
            Map.entry("鞋靴", List.of("鞋", "跑鞋", "球鞋", "皮鞋", "靴")),
            Map.entry("服饰", List.of("衣服", "外套", "t恤", "服饰", "穿搭", "夹克", "衬衫")),
            Map.entry("电子数码", List.of("数码", "外设", "音箱", "显示器", "蓝牙", "type-c")),
            Map.entry("个护母婴", List.of("母婴", "奶粉", "纸尿裤", "湿巾", "婴儿", "宝宝")),
            Map.entry("母婴玩具", List.of("母婴", "婴儿", "宝宝", "玩具", "启蒙", "安抚"))
    );

    private final AiProperties aiProperties;
    private final RetrievalGatewayService retrievalGatewayService;
    private final ProductRecommendationService productRecommendationService;
    private final AdvisorKnowledgeBaseService advisorKnowledgeBaseService;
    private final ChatMessageRepository chatMessageRepository;
    private final OrderItemRepository orderItemRepository;
    private final ProductRepository productRepository;
    private final ShoppingAdvisorTools shoppingAdvisorTools;
    @SuppressWarnings("unused")
    private final AdvisorToolContext advisorToolContext;
    private final ObjectMapper objectMapper;
    private final WebClient.Builder webClientBuilder;

    private volatile ChatLanguageModel chatLanguageModel;
    private volatile ShoppingAdvisorAiService shoppingAdvisorAiService;
    private volatile DirectShoppingAdvisorAiService directShoppingAdvisorAiService;

    private final ConcurrentMap<String, ChatMemory> shoppingAdvisorMemories = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, ChatMemory> directAdvisorMemories = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Deque<String>> recentClarificationDimensionsBySession = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, SessionRequirementProfile> requirementProfileBySession = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Deque<UUID>> recentRecommendedProductsBySession = new ConcurrentHashMap<>();

    private final ChatMemoryProvider shoppingAdvisorMemoryProvider = memoryId -> shoppingAdvisorMemories
            .computeIfAbsent(String.valueOf(memoryId), ignored -> MessageWindowChatMemory.withMaxMessages(20));
    private final ChatMemoryProvider directAdvisorMemoryProvider = memoryId -> directAdvisorMemories
            .computeIfAbsent(String.valueOf(memoryId), ignored -> MessageWindowChatMemory.withMaxMessages(20));

    private final AtomicReference<AiRuntimeStatus> runtimeStatusRef = new AtomicReference<>();
    private final AtomicInteger consecutiveFallbacks = new AtomicInteger();

    public AiShoppingAdvisorService(AiProperties aiProperties,
                                    RetrievalGatewayService retrievalGatewayService,
                                    ProductRecommendationService productRecommendationService,
                                    AdvisorKnowledgeBaseService advisorKnowledgeBaseService,
                                    ChatMessageRepository chatMessageRepository,
                                    OrderItemRepository orderItemRepository,
                                    ProductRepository productRepository,
                                    ShoppingAdvisorTools shoppingAdvisorTools,
                                    AdvisorToolContext advisorToolContext,
                                    ObjectMapper objectMapper,
                                    WebClient.Builder webClientBuilder) {
        this.aiProperties = aiProperties;
        this.retrievalGatewayService = retrievalGatewayService;
        this.productRecommendationService = productRecommendationService;
        this.advisorKnowledgeBaseService = advisorKnowledgeBaseService;
        this.chatMessageRepository = chatMessageRepository;
        this.orderItemRepository = orderItemRepository;
        this.productRepository = productRepository;
        this.shoppingAdvisorTools = shoppingAdvisorTools;
        this.advisorToolContext = advisorToolContext;
        this.objectMapper = objectMapper;
        this.webClientBuilder = webClientBuilder;
        runtimeStatusRef.set(AiRuntimeStatus.initial(currentProviderName(), activeModelName()));
    }

    public Mono<ChatAdvicePayload> advise(UUID userId, String message, String sessionId) {
        // 🔒 会话隔离：按 sessionId 查询消息，防止新会话使用旧会话的历史记录
        String effectiveSessionId = (sessionId == null || sessionId.trim().isEmpty()) ? "default" : sessionId;
        
        return Mono.zip(
                        // ✅ 改为按 sessionId 过滤，确保新会话不会继承旧会话的数据
                        chatMessageRepository.findByUserIdAndSessionId(userId, effectiveSessionId).collectList(),
                retrievalGatewayService.search(message, 10, null, null).collectList(),
                        productRepository.findAll().collectList(),
                        orderItemRepository.findAll().collectList())
            .flatMap(tuple -> Mono.fromCallable(() -> buildAdvice(userId, message, effectiveSessionId, tuple.getT1(), tuple.getT2(), tuple.getT3(), tuple.getT4(), null))
                        .subscribeOn(Schedulers.boundedElastic()));
    }

            public Mono<ChatAdvicePayload> adviseStream(UUID userId,
                                String message,
                                String sessionId,
                                Consumer<String> tokenConsumer) {
            String effectiveSessionId = (sessionId == null || sessionId.trim().isEmpty()) ? "default" : sessionId;

            return Mono.zip(
                    chatMessageRepository.findByUserIdAndSessionId(userId, effectiveSessionId).collectList(),
                    retrievalGatewayService.search(message, 16, null, null).collectList(),
                    productRepository.findAll().collectList(),
                    orderItemRepository.findAll().collectList())
                .flatMap(tuple -> Mono.fromCallable(() -> buildAdvice(userId, message, effectiveSessionId, tuple.getT1(), tuple.getT2(), tuple.getT3(), tuple.getT4(), tokenConsumer))
                    .subscribeOn(Schedulers.boundedElastic()));
            }

    public Mono<ChatAdvicePayload> adviseQuick(UUID userId, String message, String sessionId) {
        // 【快速模式】跳过知识库澄清，直接推荐
        // 1. 快速提取预算和核心需求
        // 2. 向量搜索和排名
        // 3. 返回简洁建议（2-3 句话）
        String effectiveSessionId = (sessionId == null || sessionId.trim().isEmpty()) ? "default" : sessionId;
        
        return Mono.zip(
                        retrievalGatewayService.search(message, 12, null, null)
                                .map(RetrievalGatewayService.RetrievalChunk::product)
                                .collectList(),
                        productRepository.findAll().collectList(),
                        orderItemRepository.findAll().collectList())
                .flatMap(tuple -> Mono.fromCallable(() -> {
                    List<Product> retrievedProducts = tuple.getT1();
                    List<Product> allProducts = tuple.getT2();
                    List<OrderItem> soldItems = tuple.getT3();
                    
                    // 快速提取预算
                    BigDecimal budget = resolveBudget(message, List.of());
                    String intent = resolveIntent(message, List.of());
                    
                    if (isBlank(intent)) {
                        intent = inferIntentFromProducts(retrievedProducts);
                    }
                    
                    Map<UUID, Integer> salesMap = aggregateSales(soldItems);
                        List<Product> ranked = rankAndFilterProducts(message, budget, intent,
                            "", "", "", "", "", "", effectiveSessionId,
                            retrievedProducts, allProducts, salesMap);
                    
                    if (ranked.isEmpty()) {
                        String reply = "按你的条件没找到完全匹配产品，建议放宽条件试试。";
                        return new ChatAdvicePayload(reply, List.of(), List.of(), List.of(), intent, budgetSummary(budget), true);
                    }
                    
                    // 快速推荐：仅前3件 + 简洁文案
                    List<ChatRecommendationResponse> recommendations = toRecommendations(ranked, salesMap, budget);
                    recordRecommendedProducts(effectiveSessionId, recommendations);
                    String quickReply = formatQuickRecommendationReply(intent, budget, ranked);
                    
                    recordSuccess();
                    return new ChatAdvicePayload(quickReply, recommendations, List.of(), List.of(), intent, budgetSummary(budget), false);
                }).subscribeOn(Schedulers.boundedElastic()));
    }

    private String formatQuickRecommendationReply(String intent, BigDecimal budget, List<Product> products) {
        // 生成2-3句的快速推荐
        if (products.isEmpty()) {
            return "暂无推荐";
        }
        
        Product top = products.get(0);
        String budgetStr = budgetSummary(budget);
        return "根据你的需求（" + blankToDefault(intent, "通用") + "，" + budgetStr + "），"
                + "我最推荐这款：" + safe(top.getName()) + " (" + formatPrice(top.getPrice()) + " 元)。"
                + "如需其他选项，下面还有备选款，点击查看详情。";
    }

    public String getRuntimeStatusSummary() {
        AiRuntimeStatus status = runtimeStatusRef.get();
        return "AI Provider: " + status.provider() + "\n"
                + "模型名称: " + status.modelName() + "\n"
                + "当前模式: " + (status.fallback() ? "规则兜底" : "实时模型") + "\n"
                + "最近原因: " + status.reason() + "\n"
                + "连续兜底次数: " + status.consecutiveFallbacks() + "\n"
                + "最近更新时间: " + status.updatedAt();
    }

    public AiRuntimeStatus getRuntimeStatus() {
        return runtimeStatusRef.get();
    }

    public synchronized void reloadProviderConfiguration(String reason) {
        invalidateAiCaches();
        runtimeStatusRef.set(new AiRuntimeStatus(currentProviderName(), activeModelName(), false,
                isBlank(reason) ? "CONFIG_RELOADED" : reason, Instant.now(), consecutiveFallbacks.get()));
    }

    private ChatAdvicePayload buildAdvice(UUID userId,
                                          String message,
                                          String sessionId,
                                          List<ChatMessage> history,
                          List<RetrievalGatewayService.RetrievalChunk> retrievalChunks,
                                          List<Product> allProducts,
                                          List<OrderItem> soldItems,
                                          Consumer<String> tokenConsumer) {
        List<Product> retrievedProducts = (retrievalChunks == null ? List.<RetrievalGatewayService.RetrievalChunk>of() : retrievalChunks)
            .stream()
            .map(RetrievalGatewayService.RetrievalChunk::product)
            .filter(item -> item != null)
            .toList();
        List<ChatMessage> effectiveHistory = extractEffectiveHistory(history, 12);
        
        // 检测是否是品类切换。如果是，过滤历史只保留最近的相关部分
        boolean isCategorySwitch = detectCategorySwitch(message, effectiveHistory);
        List<ChatMessage> contextualHistory = isCategorySwitch
            ? filterHistoryForNewCategory(message, effectiveHistory)
            : effectiveHistory;
        if (isCategorySwitch) {
            recentClarificationDimensionsBySession.remove(sessionId);
            requirementProfileBySession.remove(sessionId);
        }
        
        String currentIntent = detectIntent(message);
        List<String> recentUserMessages = extractRecentUserMessages(contextualHistory, 6);
        List<String> scopedUserMessages = extractIntentScopedUserMessages(contextualHistory, currentIntent, 6);
        List<String> baseHistory = scopedUserMessages.isEmpty() ? recentUserMessages : scopedUserMessages;
        List<String> inferenceHistory = shouldUseHistoryForInference(message, baseHistory)
            ? baseHistory
            : List.of();
        String mergedUserContext = mergeContext(message, inferenceHistory);

        BigDecimal budget = resolveBudget(message, inferenceHistory);
        String intent = resolveIntent(message, inferenceHistory);
        intent = normalizeDetectedIntent(message, intent);
        String scene = resolveScene(message, inferenceHistory, intent, isCategorySwitch);
        String functionPreference = resolveFunctionPreference(message, inferenceHistory, intent, isCategorySwitch);
        String appearancePreference = resolveAppearancePreference(message, inferenceHistory, intent, isCategorySwitch);
        String brandPreference = resolveBrandPreference(message, inferenceHistory, intent, isCategorySwitch);
        String typePreference = resolveTypePreference(message, inferenceHistory, intent, isCategorySwitch);
        String colorPreference = resolveColorPreference(message, inferenceHistory, intent, isCategorySwitch);

        SessionRequirementProfile profile = requirementProfileBySession.get(sessionId);
        if (profile != null && shouldApplySessionProfile(intent, profile)) {
            if (isBlank(intent)) {
                intent = profile.intent();
            }
            if (budget == null) {
                budget = profile.budget();
            }
            if (isBlank(scene)) {
                scene = profile.scene();
            }
            if (isBlank(functionPreference)) {
                functionPreference = profile.functionPreference();
            }
            if (isBlank(appearancePreference)) {
                appearancePreference = profile.appearancePreference();
            }
            if (isBlank(brandPreference)) {
                brandPreference = profile.brandPreference();
            }
            if (isBlank(typePreference)) {
                typePreference = profile.typePreference();
            }
            if (isBlank(colorPreference)) {
                colorPreference = profile.colorPreference();
            }
        }

        String lastAskedDimension = recentAskedDimensions(sessionId).stream().reduce((left, right) -> right).orElse("");
        if (!isBlank(lastAskedDimension) && !isFollowUpMessage(message)) {
            String normalizedAsked = normalizeDimensionKey(lastAskedDimension);
            String raw = safe(message).trim();
            switch (normalizedAsked) {
                case "budget" -> {
                    if (budget == null) {
                        budget = extractBudget(raw);
                    }
                }
                case "scene" -> {
                    if (isBlank(scene) && !raw.isBlank()) {
                        String inferred = detectScene(raw);
                        scene = isBlank(inferred) ? raw : inferred;
                    }
                }
                case "function" -> {
                    if (isBlank(functionPreference) && !raw.isBlank()) {
                        String inferred = detectFunctionPreference(raw);
                        functionPreference = isBlank(inferred) ? raw : inferred;
                    }
                }
                case "appearance" -> {
                    if (isBlank(appearancePreference) && !raw.isBlank()) {
                        String inferred = detectAppearancePreference(raw);
                        appearancePreference = isBlank(inferred) ? raw : inferred;
                    }
                }
                case "brand" -> {
                    if (isBlank(brandPreference) && !raw.isBlank()) {
                        String inferred = detectBrandPreference(raw);
                        if (isBlank(inferred) && containsAny(raw.toLowerCase(Locale.ROOT), List.of("都可以", "不限", "随便"))) {
                            inferred = "品牌不限";
                        }
                        brandPreference = inferred;
                    }
                }
                case "type" -> {
                    if (isBlank(typePreference) && !raw.isBlank()) {
                        String inferred = detectTypePreference(raw);
                        if (isBlank(inferred) && containsAny(raw.toLowerCase(Locale.ROOT), List.of("都可以", "不限", "随便"))) {
                            inferred = "类型不限";
                        }
                        typePreference = inferred;
                    }
                }
                case "color" -> {
                    if (isBlank(colorPreference) && !raw.isBlank()) {
                        String inferred = detectColorPreference(raw);
                        if (isBlank(inferred) && containsAny(raw.toLowerCase(Locale.ROOT), List.of("都可以", "不限", "随便"))) {
                            inferred = "颜色不限";
                        }
                        colorPreference = inferred;
                    }
                }
                default -> {
                    // no-op
                }
            }
        }

        boolean acceptsGenericPreference = acceptsGenericPreference(message, inferenceHistory);
        // 耳机导购保持高精度：不因“都可以”自动补默认，避免 2 问就推荐。
        if (acceptsGenericPreference && !HEADPHONE_INTENT.equals(intent)) {
            if (isBlank(scene)) {
                scene = defaultSceneByIntent(intent);
            }
            if (isBlank(functionPreference)) {
                functionPreference = defaultFunctionByIntent(intent);
            }
            if (isBlank(appearancePreference)) {
                appearancePreference = "简约通用";
            }
            if (isBlank(brandPreference)) {
                brandPreference = "主流品牌均可";
            }
        }

        List<AdvisorKnowledgeBaseService.KnowledgeSnippet> snippets = advisorKnowledgeBaseService
                .search(mergedUserContext, 4);
        AdvisorKnowledgeBaseService.ClarificationPlan plan = advisorKnowledgeBaseService
                .resolveClarificationPlan(intent, snippets);

        List<String> requiredDimensions = resolveRequiredDimensions(plan);

        List<String> missingDimensions = resolveMissingDimensions(plan, budget, scene, functionPreference, appearancePreference,
            brandPreference, typePreference, colorPreference, intent);
        boolean directRecommendation = wantsDirectRecommendation(message, inferenceHistory);

        if (directRecommendation) {
            if (isBlank(scene)) {
                scene = defaultSceneByIntent(intent);
            }
            if (isBlank(functionPreference)) {
                functionPreference = defaultFunctionByIntent(intent);
            }
            if (isBlank(appearancePreference)) {
                appearancePreference = "主流通用风格";
            }
            if (isBlank(brandPreference)) {
                brandPreference = "主流品牌均可";
            }
            if (HEADPHONE_INTENT.equals(intent)) {
                if (isBlank(typePreference)) {
                    typePreference = "类型不限";
                }
                if (isBlank(colorPreference)) {
                    colorPreference = "颜色不限";
                }
            }
            missingDimensions = resolveMissingDimensions(plan, budget, scene, functionPreference, appearancePreference,
                    brandPreference, typePreference, colorPreference, intent);
        }

            requirementProfileBySession.put(sessionId, new SessionRequirementProfile(
                blankToDefault(intent, "耳机"),
                budget,
                safe(scene),
                safe(functionPreference),
                safe(appearancePreference),
                safe(brandPreference),
                safe(typePreference),
                safe(colorPreference),
                Instant.now()));

        if (!missingDimensions.isEmpty()) {
            List<String> recentAskedDimensions = recentAskedDimensions(sessionId);
            String dimensionToAsk = selectBestDimensionToAsk(missingDimensions, plan, contextualHistory, intent, recentAskedDimensions);
            if (dimensionToAsk.isBlank()) {
                dimensionToAsk = "preference";
            }
            recordAskedDimension(sessionId, dimensionToAsk);
            List<String> collectedDimensions = resolveCollectedDimensions(requiredDimensions, missingDimensions);
            int completionPercent = calculateCompletionPercent(requiredDimensions, collectedDimensions);
            return buildClarificationPayload(intent, budget, dimensionToAsk, plan, snippets,
                    collectedDimensions, missingDimensions, completionPercent, retrievalChunks);
        }

        recentClarificationDimensionsBySession.remove(sessionId);

        Map<UUID, Integer> salesMap = aggregateSales(soldItems);
        List<Product> ranked = rankAndFilterProducts(mergedUserContext, budget, intent,
            scene, functionPreference, appearancePreference, brandPreference, typePreference, colorPreference, sessionId,
            retrievedProducts, allProducts, salesMap);

        if (ranked.isEmpty()) {
            String reply = "我已经按你给的条件在商品库里筛了一轮，暂时没有完全匹配的款。"
                    + "你可以告诉我是否放宽预算或功能要求，我马上给你第二版方案。";
            recordFallback("NO_MATCHED_PRODUCTS");
            return new ChatAdvicePayload(
                    reply,
                    List.of(),
                    List.of(),
                    buildInsights(intent, budget, snippets, true, "商品库无匹配结果", retrievalChunks),
                    detectedIntentLabel(intent, scene),
                    budgetSummary(budget),
                    true);
        }

        List<ChatRecommendationResponse> recommendations = toRecommendations(ranked, salesMap, budget);
        recordRecommendedProducts(sessionId, recommendations);
        List<Product> mainProducts = ranked.stream().limit(MAX_MAIN_RECOMMENDATIONS).toList();

        String aiReply = tokenConsumer == null
            ? generateRecommendationReply(userId, message, contextualHistory, budget, scene, intent,
                functionPreference, appearancePreference, brandPreference, typePreference, colorPreference, mainProducts, snippets, sessionId)
            : generateRecommendationReplyStreaming(userId, message, contextualHistory, budget, scene, intent,
                functionPreference, appearancePreference, brandPreference, typePreference, colorPreference, mainProducts, snippets, sessionId, tokenConsumer);

        String finalReply = ensureSummaryPreface(aiReply, intent, budget, scene,
            functionPreference, appearancePreference, brandPreference, typePreference, colorPreference);

        recordSuccess();
        return new ChatAdvicePayload(
            finalReply,
                recommendations,
                List.of(),
                appendCompletenessInsights(
                buildInsights(intent, budget, snippets, false, activeModelLabel(), retrievalChunks),
                        requiredDimensions,
                        List.of()),
                detectedIntentLabel(intent, scene),
                budgetSummary(budget),
                false);
    }

    private boolean wantsDirectRecommendation(String message, List<String> history) {
        String merged = safe(message) + "\n" + String.join("\n", history == null ? List.of() : history);
        String normalized = merged.toLowerCase(Locale.ROOT);
        return DIRECT_RECOMMEND_WORDS.stream().anyMatch(word -> normalized.contains(word.toLowerCase(Locale.ROOT)));
    }

    private String normalizeDetectedIntent(String message, String detectedIntent) {
        String normalizedMessage = safe(message).toLowerCase(Locale.ROOT);
        boolean containsAudioSignals = INTENT_KEYWORDS.getOrDefault(HEADPHONE_INTENT, List.of("耳机"))
                .stream()
                .map(item -> item.toLowerCase(Locale.ROOT))
                .anyMatch(normalizedMessage::contains);

        if (!isBlank(detectedIntent)) {
            return detectedIntent;
        }
        if (containsAudioSignals) {
            return HEADPHONE_INTENT;
        }
        return "";
    }

    private boolean shouldApplySessionProfile(String currentIntent, SessionRequirementProfile profile) {
        if (profile == null) {
            return false;
        }
        if (isBlank(currentIntent)) {
            return true;
        }
        return currentIntent.equals(safe(profile.intent()));
    }

    private ChatAdvicePayload buildClarificationPayload(String intent,
                                                        BigDecimal budget,
                                                        String missingDimension,
                                                        AdvisorKnowledgeBaseService.ClarificationPlan plan,
                                                        List<AdvisorKnowledgeBaseService.KnowledgeSnippet> snippets,
                                                        List<String> collectedDimensions,
                                                        List<String> missingDimensions,
                                                        int completionPercent,
                                                        List<RetrievalGatewayService.RetrievalChunk> retrievalChunks) {
        String question = buildProfessionalClarificationQuestion(intent, missingDimension, plan, snippets);
        if (question.isBlank()) {
            question = "我先补一个关键信息，这样推荐不会跑偏：你最在意预算、场景还是功能？";
        }

        List<ChatInsightResponse> insights = new ArrayList<>();
        insights.add(new ChatInsightResponse("导购阶段", "需求补全中"));
        insights.add(new ChatInsightResponse("待确认维度", missingDimension));
        insights.add(new ChatInsightResponse("知识库命中", String.valueOf(snippets.size())));
        insights.add(new ChatInsightResponse("需求完整度", completionPercent + "%"));
        insights.add(new ChatInsightResponse("已收集维度", joinDimensionLabels(collectedDimensions)));
        insights.add(new ChatInsightResponse("缺失维度", joinDimensionLabels(missingDimensions)));
        if (!snippets.isEmpty()) {
            String topic = snippets.stream()
                    .limit(2)
                    .map(item -> safe(item.category()) + ":" + safe(item.title()))
                    .collect(Collectors.joining(" | "));
            insights.add(new ChatInsightResponse("参考知识", topic));
        }
        insights.addAll(vectorizationInsights(retrievalChunks));

        return new ChatAdvicePayload(
                question,
                List.of(),
                List.of(),
                insights,
                detectedIntentLabel(intent, ""),
                budgetSummary(budget),
                false);
    }

    private List<ChatInsightResponse> appendCompletenessInsights(List<ChatInsightResponse> insights,
                                                                 List<String> requiredDimensions,
                                                                 List<String> missingDimensions) {
        List<ChatInsightResponse> enriched = new ArrayList<>(insights);
        List<String> collectedDimensions = resolveCollectedDimensions(requiredDimensions, missingDimensions);
        int completionPercent = calculateCompletionPercent(requiredDimensions, collectedDimensions);
        enriched.add(new ChatInsightResponse("需求完整度", completionPercent + "%"));
        enriched.add(new ChatInsightResponse("已收集维度", joinDimensionLabels(collectedDimensions)));
        enriched.add(new ChatInsightResponse("缺失维度", joinDimensionLabels(missingDimensions)));
        return enriched;
    }

    private List<String> resolveRequiredDimensions(AdvisorKnowledgeBaseService.ClarificationPlan plan) {
        List<String> required = normalizeDimensions(plan == null ? List.of() : plan.requiredDimensions());
        if (!required.isEmpty()) {
            return required;
        }
        return List.of("budget", "scene", "function", "appearance");
    }

    private List<String> normalizeDimensions(List<String> dimensions) {
        return (dimensions == null ? List.<String>of() : dimensions).stream()
                .map(this::normalizeDimensionKey)
                .filter(item -> !item.isBlank())
                .distinct()
                .toList();
    }

    private String normalizeDimensionKey(String key) {
        String normalized = safe(key).trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "usage" -> "scene";
            case "feature" -> "function";
            case "style" -> "appearance";
            default -> normalized;
        };
    }

    private List<String> resolveCollectedDimensions(List<String> requiredDimensions, List<String> missingDimensions) {
        Set<String> missing = normalizeDimensions(missingDimensions).stream().collect(Collectors.toSet());
        return normalizeDimensions(requiredDimensions).stream()
                .filter(item -> !missing.contains(item))
                .toList();
    }

    private int calculateCompletionPercent(List<String> requiredDimensions, List<String> collectedDimensions) {
        int total = Math.max(1, normalizeDimensions(requiredDimensions).size());
        int collected = normalizeDimensions(collectedDimensions).size();
        return (int) Math.round((collected * 100.0d) / total);
    }

    private String joinDimensionLabels(List<String> dimensions) {
        List<String> labels = normalizeDimensions(dimensions).stream()
                .map(this::dimensionLabel)
                .toList();
        if (labels.isEmpty()) {
            return "无";
        }
        return String.join("、", labels);
    }

    private String dimensionLabel(String key) {
        return switch (normalizeDimensionKey(key)) {
            case "budget" -> "预算";
            case "scene" -> "使用场景";
            case "function" -> "核心功能";
            case "appearance" -> "佩戴/外观";
            case "brand" -> "品牌偏好";
            case "type" -> "类型偏好";
            case "color" -> "颜色偏好";
            case "preference" -> "补充偏好";
            case "intent" -> "品类意图";
            default -> "其他";
        };
    }

    private String generateRecommendationReply(UUID userId,
                                               String message,
                                               List<ChatMessage> history,
                                               BigDecimal budget,
                                               String scene,
                                               String intent,
                                               String functionPreference,
                                               String appearancePreference,
                                                   String brandPreference,
                                                   String typePreference,
                                                   String colorPreference,
                                               List<Product> products,
                                               List<AdvisorKnowledgeBaseService.KnowledgeSnippet> snippets,
                                               String sessionId) {
                            String prompt = buildRecommendationPrompt(message, history, budget, scene, intent, functionPreference, appearancePreference,
                                brandPreference, typePreference, colorPreference, products, snippets);
        String conversationId = resolveRequestScopedConversationId(userId, sessionId);

        try {
            ShoppingAdvisorAiService toolService = getOrCreateShoppingAdvisorAiService();
            if (toolService != null) {
                String text = normalizeReply(toolService.chat(conversationId, prompt));
                if (text != null) {
                    return text;
                }
            }
        } catch (Exception error) {
            log.warn("Tool advisor call failed: {}", summarizeError(error));
            invalidateAiCaches();
        }

        try {
            DirectShoppingAdvisorAiService directService = getOrCreateDirectShoppingAdvisorAiService();
            if (directService != null) {
                String text = normalizeReply(directService.chat(conversationId, prompt));
                if (text != null) {
                    return text;
                }
            }
        } catch (Exception error) {
            log.warn("Direct advisor call failed: {}", summarizeError(error));
            invalidateAiCaches();
        }

        recordFallback("AI_UNAVAILABLE_RULE_BASED_REPLY");
        return buildRuleBasedReply(intent, budget, scene, products);
    }

    private String generateRecommendationReplyStreaming(UUID userId,
                                                        String message,
                                                        List<ChatMessage> history,
                                                        BigDecimal budget,
                                                        String scene,
                                                        String intent,
                                                        String functionPreference,
                                                        String appearancePreference,
                                                        String brandPreference,
                                                        String typePreference,
                                                        String colorPreference,
                                                        List<Product> products,
                                                        List<AdvisorKnowledgeBaseService.KnowledgeSnippet> snippets,
                                                        String sessionId,
                                                        Consumer<String> tokenConsumer) {
                                String prompt = buildRecommendationPrompt(message, history, budget, scene, intent, functionPreference, appearancePreference,
                                    brandPreference, typePreference, colorPreference, products, snippets);

        String streamed = streamReplyFromProvider(prompt, tokenConsumer);
        String normalized = normalizeReply(streamed);
        if (normalized != null) {
            recordSuccess();
            return normalized;
        }

        // 流式失败时回退到同步路径，并用字符级回调保持前端流式体验不中断。
        String fallback = generateRecommendationReply(userId, message, history, budget, scene, intent,
            functionPreference, appearancePreference, brandPreference, typePreference, colorPreference, products, snippets, sessionId);
        if (fallback != null && tokenConsumer != null) {
            fallback.codePoints().forEach(codePoint -> tokenConsumer.accept(new String(Character.toChars(codePoint))));
        }
        return fallback;
    }

    private String buildRecommendationPrompt(String message,
                                             List<ChatMessage> history,
                                             BigDecimal budget,
                                             String scene,
                                             String intent,
                                             String functionPreference,
                                             String appearancePreference,
                                             String brandPreference,
                                             String typePreference,
                                             String colorPreference,
                                             List<Product> products,
                                             List<AdvisorKnowledgeBaseService.KnowledgeSnippet> snippets) {
        String productContext = products.stream()
                .limit(MAX_MAIN_RECOMMENDATIONS)
                .map(product -> "- 商品：" + safe(product.getName())
                        + "；价格：" + formatPrice(product.getPrice()) + " 元"
                        + "；标签：" + safe(product.getTags())
                        + "；描述：" + safe(product.getDescription()))
                .collect(Collectors.joining("\n"));

        String historyContext = history.stream()
                .sorted(Comparator.comparing(ChatMessage::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder())))
                .skip(Math.max(0, history.size() - 6L))
                .map(item -> ("USER".equalsIgnoreCase(item.getRole()) ? "用户" : "助手") + "：" + safe(item.getContent()))
                .collect(Collectors.joining("\n"));

        String knowledgeContext = snippets.isEmpty()
                ? "暂无命中，使用通用导购策略。"
                : snippets.stream()
                .limit(3)
                .map(snippet -> "- [" + safe(snippet.category()) + "] " + safe(snippet.title()) + "：" + safe(snippet.summary()))
                .collect(Collectors.joining("\n"));

        String prompt = "你是中文电商资深导购顾问“小选”。必须严格按以下规则回复：\n"
            + "1. 只使用给定商品，不编造型号、参数、价格。\n"
            + "2. 输出结构固定为：\n"
            + "   【需求速览】1-2 行\n"
            + "   【主推】1 款（明确推荐理由）\n"
            + "   【备选】1-2 款（说明与主推的取舍差异）\n"
            + "   【购买建议】1 行（下单/加购建议）\n"
            + "3. 每个推荐理由要绑定“场景 + 预算 + 功能偏好”，避免空话。\n"
            + "4. 语气专业但自然，短句优先，不使用 Markdown 表格。\n"
            + "5. 已提供的信息不重复追问；若信息不足，只补问 1 个最高优先级维度。\n\n"
                + "用户本轮消息：" + safe(message) + "\n"
                + "识别品类：" + blankToDefault(intent, "未识别") + "\n"
                + "预算：" + budgetSummary(budget) + "\n"
                + "场景：" + blankToDefault(scene, "未明确") + "\n"
                + "功能偏好：" + blankToDefault(functionPreference, "未明确") + "\n"
                + "外观偏好：" + blankToDefault(appearancePreference, "未明确") + "\n"
                + "品牌偏好：" + blankToDefault(brandPreference, "未明确") + "\n"
                + "类型偏好：" + blankToDefault(typePreference, "未明确") + "\n"
                + "颜色偏好：" + blankToDefault(colorPreference, "未明确") + "\n\n"
                + "知识库片段：\n" + knowledgeContext + "\n\n"
                + "最近对话：\n" + (historyContext.isBlank() ? "暂无" : historyContext) + "\n\n"
                + "候选商品：\n" + productContext;
            return prompt;
    }

    private String streamReplyFromProvider(String prompt, Consumer<String> tokenConsumer) {
        AiProperties.Provider provider = currentProvider();
        if (provider == null || isBlank(provider.getApiKey()) || isBlank(provider.getModelName())) {
            recordFallback("MODEL_CONFIG_MISSING");
            return null;
        }

        String baseUrl = normalizeBaseUrl(isBlank(provider.getBaseUrl()) ? "https://api.deepseek.com/v1" : provider.getBaseUrl());
        Duration timeout = provider.getTimeout() == null ? Duration.ofSeconds(90) : provider.getTimeout();

        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("model", provider.getModelName());
        requestBody.put("stream", true);
        requestBody.put("messages", List.of(Map.of("role", "user", "content", prompt)));

        StringBuilder output = new StringBuilder();

        try {
            webClientBuilder
                    .baseUrl(baseUrl)
                    .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + provider.getApiKey())
                    .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .build()
                    .post()
                    .uri("/chat/completions")
                    .accept(MediaType.TEXT_EVENT_STREAM)
                    .bodyValue(requestBody)
                    .retrieve()
                    .bodyToFlux(String.class)
                    .timeout(timeout)
                    .doOnNext(chunk -> consumeSseChunk(chunk, output, tokenConsumer))
                    .blockLast(timeout.plusSeconds(2));

            return output.toString();
        } catch (Exception error) {
            recordFallback("MODEL_STREAM_FAILED: " + summarizeError(error));
            return null;
        }
    }

    private void consumeSseChunk(String chunk, StringBuilder output, Consumer<String> tokenConsumer) {
        if (isBlank(chunk)) {
            return;
        }
        String[] lines = chunk.split("\\r?\\n");
        for (String line : lines) {
            if (line == null || !line.startsWith("data:")) {
                continue;
            }
            String payload = line.substring(5).trim();
            if (payload.isBlank() || "[DONE]".equals(payload)) {
                continue;
            }
            String token = extractDeltaContent(payload);
            if (token == null || token.isEmpty()) {
                continue;
            }
            output.append(token);
            if (tokenConsumer != null) {
                tokenConsumer.accept(token);
            }
        }
    }

    private String extractDeltaContent(String payload) {
        try {
            JsonNode root = objectMapper.readTree(payload);
            JsonNode choices = root.path("choices");
            if (!choices.isArray() || choices.isEmpty()) {
                return "";
            }
            JsonNode delta = choices.get(0).path("delta");
            JsonNode content = delta.path("content");
            return content.isMissingNode() || content.isNull() ? "" : content.asText("");
        } catch (Exception error) {
            log.debug("Failed to parse stream payload: {}", summarizeError(error));
            return "";
        }
    }

    private String normalizeBaseUrl(String url) {
        if (url == null) {
            return "";
        }
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private String resolveRequestScopedConversationId(UUID userId, String sessionId) {
        String base = userId == null ? "anonymous" : userId.toString();
        String session = sessionId == null || sessionId.trim().isEmpty() ? "default" : sessionId;
        // 每个会话都有独立的 conversationId：userId:sessionId
        // 这样确保新对话会话不会继承之前会话的记忆
        return base + ":" + session;
    }

    private List<Product> rankAndFilterProducts(String mergedUserContext,
                                                BigDecimal budget,
                                                String intent,
                                                String scene,
                                                String functionPreference,
                                                String appearancePreference,
                                                String brandPreference,
                                                String typePreference,
                                                String colorPreference,
                                                String sessionId,
                                                List<Product> retrievedProducts,
                                                List<Product> allProducts,
                                                Map<UUID, Integer> salesMap) {
        List<Product> merged = deduplicateProducts(retrievedProducts, allProducts);

        if (!isBlank(intent)) {
            List<Product> strictIntentProducts = merged.stream()
                    .filter(product -> matchesIntent(product, intent))
                    .toList();
            if (strictIntentProducts.isEmpty()) {
                return List.of();
            }
            merged = strictIntentProducts;
        }

        if (budget != null) {
            List<Product> withinBudget = merged.stream()
                    .filter(product -> product.getPrice() != null && product.getPrice().compareTo(budget) <= 0)
                    .toList();
            if (!withinBudget.isEmpty()) {
                merged = withinBudget;
            }
        }

        Set<UUID> recentRecommendedIds = recentRecommendedProductIds(sessionId);
        List<Product> nonRepeated = merged.stream()
                .filter(product -> product.getId() == null || !recentRecommendedIds.contains(product.getId()))
                .toList();
        if (nonRepeated.size() >= MAX_MAIN_RECOMMENDATIONS) {
            merged = nonRepeated;
        }

        return merged.stream()
                .sorted((left, right) -> Double.compare(
                        productScore(mergedUserContext, right, salesMap.getOrDefault(right.getId(), 0), budget,
                                scene, functionPreference, appearancePreference, brandPreference, typePreference, colorPreference,
                                recentRecommendedIds),
                        productScore(mergedUserContext, left, salesMap.getOrDefault(left.getId(), 0), budget,
                                scene, functionPreference, appearancePreference, brandPreference, typePreference, colorPreference,
                                recentRecommendedIds)))
                .limit(12)
                .toList();
    }

    private double productScore(String query,
                                Product product,
                                int salesCount,
                                BigDecimal budget,
                                String scene,
                                String functionPreference,
                                String appearancePreference,
                                String brandPreference,
                                String typePreference,
                                String colorPreference,
                                Set<UUID> recentRecommendedIds) {
        double relevance = retrievalGatewayService.relevanceScore(query, product);
        double salesBoost = Math.min(1.0d, salesCount / 10.0d) * 0.25d;
        double budgetBoost = 0.0d;
        double preferenceBoost = preferenceScore(product, scene, functionPreference, appearancePreference, brandPreference, typePreference, colorPreference);
        double diversityPenalty = (product.getId() != null && recentRecommendedIds.contains(product.getId())) ? -0.60d : 0.0d;
        if (budget != null && product.getPrice() != null) {
            if (product.getPrice().compareTo(budget) <= 0) {
                budgetBoost = 0.2d;
            } else {
                budgetBoost = -0.15d;
            }
        }
        return relevance + salesBoost + budgetBoost + preferenceBoost + diversityPenalty;
    }

    private double preferenceScore(Product product,
                                   String scene,
                                   String functionPreference,
                                   String appearancePreference,
                                   String brandPreference,
                                   String typePreference,
                                   String colorPreference) {
        String searchable = (safe(product.getName()) + " " + safe(product.getDescription()) + " " + safe(product.getTags()) + " " + safe(product.getCategory()))
                .toLowerCase(Locale.ROOT);

        double score = 0.0d;
        score += keywordMatchScore(searchable, scene, 0.20d);
        score += keywordMatchScore(searchable, functionPreference, 0.24d);
        score += keywordMatchScore(searchable, appearancePreference, 0.18d);
        score += keywordMatchScore(searchable, brandPreference, 0.22d);
        score += keywordMatchScore(searchable, typePreference, 0.20d);
        score += keywordMatchScore(searchable, colorPreference, 0.16d);
        return Math.min(0.90d, score);
    }

    private double keywordMatchScore(String searchable, String preference, double weight) {
        if (isBlank(preference) || isBlank(searchable)) {
            return 0.0d;
        }
        List<String> tokens = Pattern.compile("[\\s,，、/|>]+")
                .splitAsStream(preference.toLowerCase(Locale.ROOT))
                .map(String::trim)
                .filter(item -> item.length() >= 2)
                .toList();
        if (tokens.isEmpty()) {
            return 0.0d;
        }
        long hits = tokens.stream().filter(searchable::contains).count();
        if (hits == 0) {
            return 0.0d;
        }
        return (hits * 1.0d / tokens.size()) * weight;
    }

    private List<ChatRecommendationResponse> toRecommendations(List<Product> products,
                                                                Map<UUID, Integer> salesMap,
                                                                BigDecimal budget) {
        return deduplicateRecommendationProducts(products).stream()
                .limit(MAX_MAIN_RECOMMENDATIONS)
                .map(product -> new ChatRecommendationResponse(
                        product.getId(),
                        safe(product.getName()),
                        safe(product.getDescription()),
                        safe(product.getImageUrl()),
                        product.getPrice(),
                        recommendationReason(product, salesMap.getOrDefault(product.getId(), 0), budget),
                        salesMap.getOrDefault(product.getId(), 0),
                        budget == null || (product.getPrice() != null && product.getPrice().compareTo(budget) <= 0),
                        parseTags(product.getTags())))
                .toList();
    }

    private String recommendationReason(Product product, int salesCount, BigDecimal budget) {
        List<String> reasons = new ArrayList<>();
        if (!isBlank(product.getTags())) {
            String topTags = parseTags(product.getTags()).stream().limit(2).collect(Collectors.joining(" / "));
            if (!isBlank(topTags)) {
                reasons.add("关键词匹配：" + topTags);
            }
        }
        if (budget != null && product.getPrice() != null) {
            reasons.add(product.getPrice().compareTo(budget) <= 0 ? "预算友好：在你的预算内" : "预算提醒：略高于预算，可作为升级备选");
        }
        if (salesCount > 0) {
            reasons.add("购买热度：历史成交 " + salesCount + " 件");
        }
        if (reasons.isEmpty()) {
            reasons.add("匹配度：与当前需求场景契合");
        }
        return String.join("\n", reasons);
    }

    private List<ChatInsightResponse> buildInsights(String intent,
                                                    BigDecimal budget,
                                                    List<AdvisorKnowledgeBaseService.KnowledgeSnippet> snippets,
                                                    boolean fallback,
                                                    String detail,
                                                    List<RetrievalGatewayService.RetrievalChunk> retrievalChunks) {
        List<ChatInsightResponse> insights = new ArrayList<>();
        insights.add(new ChatInsightResponse("AI模式", fallback ? "规则兜底" : "实时模型"));
        insights.add(new ChatInsightResponse(fallback ? "兜底原因" : "模型信息", detail));
        insights.add(new ChatInsightResponse("知识库来源", advisorKnowledgeBaseService.knowledgeSourceLabel()));
        insights.add(new ChatInsightResponse("需求类型", blankToDefault(intent, "未识别")));
        insights.add(new ChatInsightResponse("预算范围", budgetSummary(budget)));
        insights.add(new ChatInsightResponse("知识库命中", String.valueOf(snippets.size())));
        insights.add(new ChatInsightResponse("知识库向量检索", "已启用（哈希向量 + 关键词混合召回）"));
        if (!snippets.isEmpty()) {
            String topic = snippets.stream()
                    .limit(3)
                    .map(item -> safe(item.category()) + ":" + safe(item.title()))
                    .collect(Collectors.joining(" | "));
            insights.add(new ChatInsightResponse("知识库主题", topic));
        }
        insights.addAll(vectorizationInsights(retrievalChunks));
        return insights;
    }

    private List<ChatInsightResponse> vectorizationInsights(List<RetrievalGatewayService.RetrievalChunk> retrievalChunks) {
        List<RetrievalGatewayService.RetrievalChunk> chunks = retrievalChunks == null ? List.of() : retrievalChunks;
        List<ChatInsightResponse> insights = new ArrayList<>();
        insights.add(new ChatInsightResponse("商品向量检索", "已启用（向量重排 + 语义打分）"));
        insights.add(new ChatInsightResponse("向量候选数", String.valueOf(chunks.size())));
        if (!chunks.isEmpty()) {
            double best = chunks.stream()
                    .mapToDouble(RetrievalGatewayService.RetrievalChunk::score)
                    .max()
                    .orElse(0.0d);
            insights.add(new ChatInsightResponse("最高语义分", String.format(Locale.ROOT, "%.3f", best)));
        }
        return insights;
    }

    private String buildProfessionalClarificationQuestion(String intent,
                                                          String missingDimension,
                                                          AdvisorKnowledgeBaseService.ClarificationPlan plan,
                                                          List<AdvisorKnowledgeBaseService.KnowledgeSnippet> snippets) {
        String base = safe(plan == null ? "" : plan.questionByDimension(missingDimension));
        String normalizedIntent = safe(intent).trim();
        String dimension = normalizeDimensionKey(missingDimension);

        String hint = categoryHintByDimension(normalizedIntent, dimension);

        String knowledgeHit = snippets == null || snippets.isEmpty()
                ? ""
                : snippets.stream()
                    .filter(item -> normalizedIntent.equals(safe(item.category())))
                        .findFirst()
                        .map(item -> "\n参考：" + safe(item.title()))
                        .orElse("");

        if (base.isBlank()) {
            return hint + knowledgeHit;
        }
        return base + "\n" + hint + knowledgeHit;
    }

    private String categoryHintByDimension(String intent, String dimension) {
        if (HEADPHONE_INTENT.equals(intent)) {
            return switch (dimension) {
                case "budget" -> "我会按 200以下 / 200-500 / 500-1000 / 1000以上 四档给你筛。";
                case "scene" -> "不同场景权重不同：通勤看降噪续航，办公看通话清晰，运动看稳固防汗，游戏看低延迟。";
                case "brand" -> "如果你有品牌或手机生态偏好（如苹果/华为/索尼），我可以直接按兼容性优先排序。";
                case "type" -> "佩戴类型会直接影响舒适度：入耳便携隔音，半入耳舒适，头戴长戴更稳。";
                case "function" -> "你可直接说优先级，比如“降噪>通话>音质”或“音质>舒适度”。";
                case "color" -> "颜色我会尽量按你偏好筛，常见黑白/深色通常库存更稳定。";
                case "appearance" -> "佩戴建议：通勤多选入耳，久戴办公可选头戴，运动优先防脱落设计。";
                default -> "补齐这一项后我就可以直接给你 2-3 个可买方案。";
            };
        }

        if ("手机".equals(intent)) {
            return switch (dimension) {
                case "function" -> "可直接告诉我优先级，例如“影像>续航>性能”或“性能>散热>手感”。";
                case "scene" -> "不同场景权重差异很大：拍照、游戏、通勤和商务的选型完全不同。";
                default -> "补齐这一项后，我会把主推和备选拉开差异，避免同质化推荐。";
            };
        }

        if ("笔记本".equals(intent)) {
            return switch (dimension) {
                case "function" -> "建议先定取向：轻薄续航、性能释放、屏幕素质或接口扩展。";
                case "scene" -> "办公、创作、开发、游戏对应的 CPU/GPU/散热策略不同。";
                default -> "补齐这一项，我会给你“主力机 + 性价比备选”的组合。";
            };
        }

        if ("家居家具".equals(intent)) {
            return switch (dimension) {
                case "appearance" -> "家居品类建议同时给风格和尺寸，这样推荐可以直接落地。";
                case "function" -> "承重、易清洁和收纳效率通常比单纯颜值更影响长期体验。";
                default -> "补充这一项后，我会优先筛掉尺寸不合适或材质不匹配的方案。";
            };
        }

        if ("食品生鲜".equals(intent)) {
            return switch (dimension) {
                case "function" -> "食品建议先明确“低糖/高蛋白/提神/成分干净”等核心目标。";
                case "scene" -> "办公室囤货、健身补给、家庭分享的包装与成分策略差异明显。";
                default -> "补齐这一项后，我会优先避开不符合禁忌和口味方向的商品。";
            };
        }

        return "补齐这一项后我就可以直接给你 2-3 个可买方案。";
    }

    private BigDecimal resolveBudget(String message, List<String> history) {
        BigDecimal budget = extractBudget(message);
        if (budget != null) {
            return budget;
        }
        for (String item : history) {
            budget = extractBudget(item);
            if (budget != null) {
                return budget;
            }
        }
        return null;
    }

    private BigDecimal extractBudget(String text) {
        Matcher matcher = STRICT_BUDGET_PATTERN.matcher(safe(text).toLowerCase(Locale.ROOT));
        while (matcher.find()) {
            try {
                BigDecimal value = new BigDecimal(matcher.group(1));
                if (value.compareTo(BigDecimal.valueOf(30)) >= 0 && value.compareTo(BigDecimal.valueOf(200000)) <= 0) {
                    return value;
                }
            } catch (Exception ignored) {
                // ignore parse failures and continue scanning
            }
        }
        return null;
    }

    private String resolveIntent(String message, List<String> history) {
        String intent = detectIntent(message);
        if (!isBlank(intent)) {
            return intent;
        }
        for (String item : history) {
            intent = detectIntent(item);
            if (!isBlank(intent)) {
                return intent;
            }
        }
        return "";
    }

    private String detectIntent(String text) {
        String normalized = safe(text).toLowerCase(Locale.ROOT);
        for (Map.Entry<String, List<String>> entry : INTENT_KEYWORDS.entrySet()) {
            boolean matched = entry.getValue().stream().anyMatch(normalized::contains);
            if (matched) {
                return entry.getKey();
            }
        }
        return "";
    }

    private String inferIntentFromProducts(List<Product> products) {
        if (products == null || products.isEmpty()) {
            return "";
        }
        for (Product product : products.stream().limit(8).toList()) {
            String probe = safe(product.getName()) + " " + safe(product.getCategory()) + " " + safe(product.getTags());
            String detected = detectIntent(probe);
            if (!isBlank(detected)) {
                return detected;
            }
        }
        return "";
    }

    private boolean matchesIntent(Product product, String intent) {
        List<String> keywords = INTENT_KEYWORDS.getOrDefault(intent, List.of(intent));
        String content = (safe(product.getName()) + " " + safe(product.getDescription()) + " " + safe(product.getTags()) + " " + safe(product.getCategory()))
                .toLowerCase(Locale.ROOT);
        return keywords.stream().anyMatch(content::contains);
    }

    private String resolveScene(String message, List<String> history) {
        String scene = detectScene(message);
        if (!isBlank(scene)) {
            return scene;
        }
        for (String item : history) {
            scene = detectScene(item);
            if (!isBlank(scene)) {
                return scene;
            }
        }
        return "";
    }

    private String resolveScene(String message, List<String> history, String intent, boolean isCategorySwitch) {
        String scene = detectScene(message);
        if (!isBlank(scene)) {
            return scene;
        }
        // 品类切换时只检查当前消息后的历史，避免混淆
        if (isCategorySwitch && !isBlank(intent)) {
            return "";
        }
        for (String item : history) {
            scene = detectScene(item);
            if (!isBlank(scene)) {
                return scene;
            }
        }
        return "";
    }

    private String detectScene(String text) {
        String normalized = safe(text).toLowerCase(Locale.ROOT);
        if (containsAny(normalized, List.of("通勤", "地铁", "公交", "上班"))) return "通勤";
        if (containsAny(normalized, List.of("办公", "会议", "码字", "文档"))) return "办公";
        if (containsAny(normalized, List.of("学习", "学生", "上课", "作业"))) return "学习";
        if (containsAny(normalized, List.of("运动", "跑步", "健身", "训练"))) return "运动";
        if (containsAny(normalized, List.of("家用", "家里", "日常", "囤货"))) return "家用";
        if (containsAny(normalized, List.of("送礼", "礼物", "礼盒"))) return "送礼";
        if (containsAny(normalized, List.of("出差", "旅行", "外出"))) return "出行";
        return "";
    }

    private String resolveFunctionPreference(String message, List<String> history) {
        return resolveFunctionPreference(message, history, "", false);
    }

    private String resolveFunctionPreference(String message, List<String> history, String intent, boolean isCategorySwitch) {
        String current = detectFunctionPreference(message);
        if (!isBlank(current)) {
            return current;
        }
        // 品类切换时只检查当前消息后的历史，避免混淆
        if (isCategorySwitch && !isBlank(intent)) {
            return "";
        }
        for (String item : history) {
            current = detectFunctionPreference(item);
            if (!isBlank(current)) {
                return current;
            }
        }
        return "";
    }

    private String detectFunctionPreference(String text) {
        String normalized = safe(text).toLowerCase(Locale.ROOT);
        List<String> keywords = List.of("降噪", "续航", "音质", "静音", "轻薄", "性能", "防水", "透气", "分仓", "人体工学", "快充", "兼容");
        List<String> hits = keywords.stream().filter(normalized::contains).limit(3).toList();
        return hits.isEmpty() ? "" : String.join("/", hits);
    }

    private String resolveAppearancePreference(String message, List<String> history) {
        return resolveAppearancePreference(message, history, "", false);
    }

    private String resolveAppearancePreference(String message, List<String> history, String intent, boolean isCategorySwitch) {
        String current = detectAppearancePreference(message);
        if (!isBlank(current)) {
            return current;
        }
        // 品类切换时只检查当前消息后的历史，避免混淆
        if (isCategorySwitch && !isBlank(intent)) {
            return "";
        }
        for (String item : history) {
            current = detectAppearancePreference(item);
            if (!isBlank(current)) {
                return current;
            }
        }
        return "";
    }

    private String resolveBrandPreference(String message, List<String> history, String intent, boolean isCategorySwitch) {
        String current = detectBrandPreference(message);
        if (!isBlank(current)) {
            return current;
        }
        if (isCategorySwitch && !isBlank(intent)) {
            return "";
        }
        for (String item : history) {
            current = detectBrandPreference(item);
            if (!isBlank(current)) {
                return current;
            }
        }
        return "";
    }

    private String detectBrandPreference(String text) {
        String normalized = safe(text).toLowerCase(Locale.ROOT);
        if (containsAny(normalized, List.of("跳过品牌", "品牌不限", "品牌都可以", "无品牌偏好", "不看品牌"))) {
            return "品牌不限";
        }
        List<String> hits = BRAND_KEYWORDS.entrySet().stream()
                .filter(entry -> entry.getValue().stream().anyMatch(normalized::contains))
                .map(Map.Entry::getKey)
                .limit(2)
                .toList();
        return hits.isEmpty() ? "" : String.join("/", hits);
    }

    private String resolveTypePreference(String message, List<String> history, String intent, boolean isCategorySwitch) {
        String current = detectTypePreference(message);
        if (!isBlank(current)) {
            return current;
        }
        if (isCategorySwitch && !isBlank(intent)) {
            return "";
        }
        for (String item : history) {
            current = detectTypePreference(item);
            if (!isBlank(current)) {
                return current;
            }
        }
        return "";
    }

    private String detectTypePreference(String text) {
        String normalized = safe(text).toLowerCase(Locale.ROOT);
        if (containsAny(normalized, List.of("跳过类型", "跳过佩戴", "类型不限", "佩戴不限", "都可以"))) {
            return "类型不限";
        }
        List<String> hits = HEADPHONE_TYPE_KEYWORDS.entrySet().stream()
                .filter(entry -> entry.getValue().stream().anyMatch(normalized::contains))
                .map(Map.Entry::getKey)
                .limit(2)
                .toList();
        return hits.isEmpty() ? "" : String.join("/", hits);
    }

    private String resolveColorPreference(String message, List<String> history, String intent, boolean isCategorySwitch) {
        String current = detectColorPreference(message);
        if (!isBlank(current)) {
            return current;
        }
        if (isCategorySwitch && !isBlank(intent)) {
            return "";
        }
        for (String item : history) {
            current = detectColorPreference(item);
            if (!isBlank(current)) {
                return current;
            }
        }
        return "";
    }

    private String detectColorPreference(String text) {
        String normalized = safe(text).toLowerCase(Locale.ROOT);
        if (containsAny(normalized, List.of("跳过颜色", "颜色不限", "颜色都可以", "无颜色偏好"))) {
            return "颜色不限";
        }
        List<String> hits = COLOR_KEYWORDS.stream()
                .filter(normalized::contains)
                .limit(2)
                .toList();
        return hits.isEmpty() ? "" : String.join("/", hits);
    }

    private String detectAppearancePreference(String text) {
        String normalized = safe(text).toLowerCase(Locale.ROOT);
        List<String> keywords = List.of("黑色", "白色", "简约", "商务", "运动风", "复古", "轻量", "小巧", "头戴", "入耳", "圆盘", "方盘", "宽松", "修身");
        List<String> hits = keywords.stream().filter(normalized::contains).limit(3).toList();
        return hits.isEmpty() ? "" : String.join("/", hits);
    }

    private boolean acceptsGenericPreference(String message, List<String> history) {
        String normalized = safe(message).toLowerCase(Locale.ROOT);
        if (containsAny(normalized, GENERIC_ACCEPT_WORDS)) {
            return true;
        }
        for (String item : history) {
            String lowered = safe(item).toLowerCase(Locale.ROOT);
            if (containsAny(lowered, GENERIC_ACCEPT_WORDS)) {
                return true;
            }
        }
        return false;
    }

    private String defaultSceneByIntent(String intent) {
        return switch (safe(intent)) {
            case "耳机", "手表", "手环", "播放器" -> "通勤";
            case "笔记本", "键盘", "鼠标" -> "办公";
            case "手机", "平板", "电子数码" -> "通勤";
            case "背包", "生活用品", "家居家具", "个护母婴", "母婴玩具" -> "家用";
            case "服饰", "宠物", "鞋靴", "食品生鲜" -> "日常";
            default -> "日常";
        };
    }

    private String defaultFunctionByIntent(String intent) {
        return switch (safe(intent)) {
            case "耳机" -> "降噪/续航";
            case "笔记本" -> "轻薄/性能平衡";
            case "手机", "平板" -> "续航/流畅";
            case "键盘", "鼠标" -> "人体工学/稳定";
            case "背包" -> "容量/分仓";
            case "食品生鲜" -> "成分/口感";
            case "个护美妆" -> "温和/功效";
            case "家居家具" -> "耐用/收纳";
            case "电子数码" -> "兼容/稳定";
            case "手表", "手环" -> "续航/健康监测";
            case "个护母婴" -> "温和/安全";
            case "母婴玩具" -> "安全/启蒙";
            case "宠物" -> "适口性/安全";
            default -> "性价比/稳定";
        };
    }

    private boolean hasAskedDimensionRecently(List<ChatMessage> history, String dimension) {
        return hasAskedDimensionRecently(history, dimension, "");
    }

    private boolean hasAskedDimensionRecently(List<ChatMessage> history, String dimension, String intent) {
        if (history == null || history.isEmpty()) {
            return false;
        }

        List<String> dimensionHints = switch (safe(dimension).toLowerCase(Locale.ROOT)) {
            case "category" -> List.of("品类", "想买", "哪一类", "买什么", "需要什么");
            case "budget" -> List.of("预算", "价位", "多少钱", "怎么样花", "花多少");
            case "usage", "scene" -> List.of("场景", "通勤", "办公", "用途", "什么地方", "哪里用");
            case "function", "feature" -> List.of("功能", "看重", "需求", "偏好", "有什么要求", "什么功能");
            case "appearance", "style", "color" -> List.of("外观", "颜色", "风格", "外形", "什么色");
            case "brand" -> List.of("品牌", "牌子", "生态", "apple", "索尼", "华为");
            case "type" -> List.of("类型", "入耳", "半入耳", "头戴", "挂耳", "骨传导");
            default -> List.of(dimension);
        };

        // 检查最近10条AI生成的消息中是否提问过这个维度
        List<String> recentAssistantMessages = history.stream()
                .filter(item -> "ASSISTANT".equalsIgnoreCase(safe(item.getRole())))
                .sorted(Comparator.comparing(ChatMessage::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(10)
                .map(item -> safe(item.getContent()).toLowerCase(Locale.ROOT))
                .toList();

        boolean askedThisDimension = recentAssistantMessages.stream()
                .anyMatch(content -> dimensionHints.stream().anyMatch(content::contains));

        if (!askedThisDimension) {
            return false;
        }

        // 避免连续两轮追问同一维度造成“重复提问”体验。
        long immediateRepeatCount = recentAssistantMessages.stream()
            .limit(2)
            .filter(content -> dimensionHints.stream().anyMatch(content::contains))
            .count();
        if (immediateRepeatCount >= 1) {
            return true;
        }

        // 只有当用户明确给出了该维度答案，才视为“该维度已问完”。
        List<String> userResponses = history.stream()
                .filter(item -> "USER".equalsIgnoreCase(safe(item.getRole())))
                .sorted(Comparator.comparing(ChatMessage::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder())))
            .limit(4)
                .map(item -> safe(item.getContent()))
            .filter(content -> content.length() > 1)
                .toList();

        return userResponses.stream().anyMatch(content -> userAnsweredDimension(content, dimension));
    }

        private boolean userAnsweredDimension(String content, String dimension) {
        String normalized = safe(content).toLowerCase(Locale.ROOT);
        String normalizedDimension = safe(dimension).toLowerCase(Locale.ROOT);
        return switch (normalizedDimension) {
            case "budget" -> extractBudget(normalized) != null
                || containsAny(normalized, List.of("预算", "元", "块", "以内", "不超过", "左右"));
            case "usage", "scene" -> !isBlank(detectScene(normalized))
                || containsAny(normalized, List.of("通勤", "办公", "学习", "家用", "出差", "旅行"));
            case "function", "feature" -> !isBlank(detectFunctionPreference(normalized))
                || containsAny(normalized, List.of("看重", "需要", "要求", "防水", "续航", "降噪", "轻薄", "性能"));
            case "appearance", "style", "color" -> !isBlank(detectAppearancePreference(normalized))
                || containsAny(normalized, List.of("颜色", "外观", "风格", "黑色", "白色", "简约", "商务"));
            case "brand" -> !isBlank(detectBrandPreference(normalized))
                || containsAny(normalized, List.of("品牌", "牌子", "索尼", "华为", "苹果", "小米"));
            case "type" -> !isBlank(detectTypePreference(normalized))
                || containsAny(normalized, List.of("入耳", "半入耳", "头戴", "挂耳", "骨传导"));
            case "category" -> !isBlank(detectIntent(normalized));
            default -> normalized.length() >= 2;
        };
        }

    private int countFulfilledDimensions(BigDecimal budget,
                                         String scene,
                                         String functionPreference,
                                         String appearancePreference,
                                         String intent) {
        int count = 0;
        if (!isBlank(intent)) count++;
        if (budget != null) count++;
        if (!isBlank(scene)) count++;
        if (!isBlank(functionPreference)) count++;
        if (!isBlank(appearancePreference)) count++;
        return count;
    }

    private boolean detectCategorySwitch(String message, List<ChatMessage> history) {
        if (isBlank(message)) {
            return false;
        }

        String normalized = message.toLowerCase(Locale.ROOT);
        if (CATEGORY_SWITCH_SIGNALS.stream().anyMatch(normalized::contains)) {
            return true;
        }

        String currentIntent = detectIntent(message);
        if (isBlank(currentIntent) || history == null || history.isEmpty()) {
            return false;
        }

        String lastUserIntent = history.stream()
                .filter(item -> "USER".equalsIgnoreCase(safe(item.getRole())))
                .sorted(Comparator.comparing(ChatMessage::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .map(item -> detectIntent(item.getContent()))
                .filter(item -> !isBlank(item))
                .findFirst()
                .orElse("");

        return !isBlank(lastUserIntent) && !currentIntent.equals(lastUserIntent);
    }

    private List<ChatMessage> filterHistoryForNewCategory(String message, List<ChatMessage> history) {
        String newIntent = detectIntent(message);
        if (isBlank(newIntent) || history == null || history.isEmpty()) {
            return history == null ? List.of() : history;
        }

        int startIndex = -1;
        for (int i = history.size() - 1; i >= 0; i--) {
            ChatMessage current = history.get(i);
            if (!"USER".equalsIgnoreCase(safe(current.getRole()))) {
                continue;
            }
            if (newIntent.equals(detectIntent(current.getContent()))) {
                startIndex = Math.max(0, i - 2);
                break;
            }
        }

        if (startIndex < 0) {
            return List.of();
        }
        return history.subList(startIndex, history.size());
    }
    private List<String> extractIntentScopedUserMessages(List<ChatMessage> history, String intent, int limit) {
        if (history == null || history.isEmpty() || isBlank(intent)) {
            return List.of();
        }

        return history.stream()
                .sorted(Comparator.comparing(ChatMessage::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .filter(item -> "USER".equalsIgnoreCase(safe(item.getRole())))
                .filter(item -> intent.equals(detectIntent(item.getContent())))
                .map(item -> safe(item.getContent()).trim())
                .filter(item -> !item.isBlank())
                .limit(Math.max(1, limit))
                .toList();
    }


    private boolean shouldUseHistoryForInference(String message, List<String> recentUserMessages) {
        if (recentUserMessages == null || recentUserMessages.isEmpty()) {
            return false;
        }

        if (isFollowUpMessage(message)) {
            return true;
        }

        String currentIntent = detectIntent(message);
        if (isBlank(currentIntent)) {
            return true;
        }

        String latestHistoryIntent = detectIntent(recentUserMessages.get(0));
        if (isBlank(latestHistoryIntent)) {
            return true;
        }

        // 用户切换品类时，不继承上一品类预算/场景，防止直接推荐。
        return currentIntent.equals(latestHistoryIntent);
    }

    private boolean isFollowUpMessage(String message) {
        String normalized = safe(message).trim().toLowerCase(Locale.ROOT);
        if (normalized.isBlank()) {
            return false;
        }
        if (normalized.length() <= 4 && FOLLOW_UP_WORDS.stream().anyMatch(normalized::contains)) {
            return true;
        }
        return FOLLOW_UP_WORDS.stream().anyMatch(normalized::equals);
    }

    private List<String> resolveMissingDimensions(AdvisorKnowledgeBaseService.ClarificationPlan plan,
                                                  BigDecimal budget,
                                                  String scene,
                                                  String functionPreference,
                                                  String appearancePreference,
                                                  String brandPreference,
                                                  String typePreference,
                                                  String colorPreference,
                                                  String intent) {
        List<String> required = plan.requiredDimensions() == null || plan.requiredDimensions().isEmpty()
                ? List.of("budget", "usage", "function")
                : plan.requiredDimensions();

        List<String> missing = new ArrayList<>();
        for (String item : required) {
            String normalized = safe(item).toLowerCase(Locale.ROOT);
            switch (normalized) {
                case "budget" -> {
                    if (budget == null) missing.add("budget");
                }
                case "usage", "scene" -> {
                    if (isBlank(scene)) missing.add("usage");
                }
                case "function", "feature" -> {
                    if (isBlank(functionPreference)) missing.add("function");
                }
                case "appearance", "style" -> {
                    if (isBlank(appearancePreference)) missing.add("appearance");
                }
                case "brand" -> {
                    if (isBlank(brandPreference)) missing.add("brand");
                }
                case "type" -> {
                    if (isBlank(typePreference)) missing.add("type");
                }
                case "color" -> {
                    if (isBlank(colorPreference)) missing.add("color");
                }
                default -> {
                    if (isBlank(intent)) missing.add("category");
                }
            }
        }
        if (isBlank(intent)) {
            missing.add(0, "category");
        }
        return missing.stream().distinct().toList();
    }

    private List<Product> deduplicateProducts(List<Product> retrievedProducts, List<Product> allProducts) {
        Map<String, Product> unique = new LinkedHashMap<>();
        for (Product product : concat(retrievedProducts, allProducts)) {
            if (product == null) {
                continue;
            }
            String key = canonicalProductKey(product);
            if (key.isBlank()) {
                continue;
            }
            Product existing = unique.get(key);
            if (existing == null || shouldReplaceProduct(existing, product)) {
                unique.put(key, product);
            }
        }
        return List.copyOf(unique.values());
    }

    private List<Product> deduplicateRecommendationProducts(List<Product> products) {
        if (products == null || products.isEmpty()) {
            return List.of();
        }

        Map<String, Product> unique = new LinkedHashMap<>();
        for (Product product : products) {
            if (product == null) {
                continue;
            }
            String key = canonicalProductKey(product);
            if (key.isBlank() || unique.containsKey(key)) {
                continue;
            }
            unique.put(key, product);
        }
        return List.copyOf(unique.values());
    }

    private String canonicalProductKey(Product product) {
        String normalizedName = safe(product.getName())
                .toLowerCase(Locale.ROOT)
                .replaceAll("[\\s\\-_/，,。·()（）]+", "");
        String normalizedCategory = safe(product.getCategory())
                .toLowerCase(Locale.ROOT)
                .replaceAll("[\\s\\-_/，,。·()（）]+", "");
        String pricePart = product.getPrice() == null ? "na" : product.getPrice().stripTrailingZeros().toPlainString();

        if (normalizedName.isBlank() && product.getId() != null) {
            return product.getId().toString();
        }
        return normalizedName + "|" + normalizedCategory + "|" + pricePart;
    }

    private boolean shouldReplaceProduct(Product existing, Product candidate) {
        int existingScore = productQualityScore(existing);
        int candidateScore = productQualityScore(candidate);
        return candidateScore > existingScore;
    }

    private int productQualityScore(Product product) {
        int score = 0;
        if (!isBlank(product.getDescription())) score += 2;
        if (!isBlank(product.getTags())) score += 1;
        if (!isBlank(product.getImageUrl())) score += 1;
        if (product.getPrice() != null) score += 1;
        return score;
    }

    private List<Product> concat(List<Product> left, List<Product> right) {
        List<Product> merged = new ArrayList<>();
        if (left != null) merged.addAll(left);
        if (right != null) merged.addAll(right);
        return merged;
    }

    private Map<UUID, Integer> aggregateSales(List<OrderItem> soldItems) {
        return soldItems.stream()
                .filter(item -> item.getProductId() != null)
                .collect(Collectors.groupingBy(OrderItem::getProductId, Collectors.summingInt(OrderItem::getQuantity)));
    }

    private List<String> extractRecentUserMessages(List<ChatMessage> history, int limit) {
        if (history == null || history.isEmpty()) {
            return List.of();
        }
        return history.stream()
                .sorted(Comparator.comparing(ChatMessage::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .filter(item -> "USER".equalsIgnoreCase(safe(item.getRole())))
                .map(item -> safe(item.getContent()).trim())
                .filter(item -> !item.isBlank())
                .limit(Math.max(1, limit))
                .toList();
    }

    private List<ChatMessage> extractEffectiveHistory(List<ChatMessage> history, int limit) {
        if (history == null || history.isEmpty()) {
            return List.of();
        }
        return history.stream()
                .sorted(Comparator.comparing(ChatMessage::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(Math.max(1, limit))
                .toList();
    }

    private int countRecentClarificationTurns(List<ChatMessage> history) {
        if (history == null || history.isEmpty()) {
            return 0;
        }

        return (int) history.stream()
                .sorted(Comparator.comparing(ChatMessage::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .filter(item -> "ASSISTANT".equalsIgnoreCase(safe(item.getRole())))
                .limit(6)
                .map(item -> safe(item.getContent()).toLowerCase(Locale.ROOT))
                .filter(this::isClarificationQuestion)
                .count();
    }

    private boolean isClarificationQuestion(String content) {
        if (isBlank(content)) {
            return false;
        }

        String normalized = content.toLowerCase(Locale.ROOT);
        boolean isQuestion = normalized.contains("？") || normalized.contains("?");
        if (!isQuestion) {
            return false;
        }

        if (normalized.contains("需求速览") || normalized.contains("推荐清单") || normalized.contains("购买建议")) {
            return false;
        }

        return containsAny(normalized, List.of("预算", "场景", "功能", "偏好", "哪一类", "想买", "告诉我", "请补充"));
    }

    private int calculateAdaptiveMaxTurns(AdvisorKnowledgeBaseService.ClarificationPlan plan,
                                         List<String> missingDimensions, 
                                         List<AdvisorKnowledgeBaseService.KnowledgeSnippet> snippets) {
        // 根据知识库命中情况和缺失维度数量动态调整最大澄清轮数
        int baseMax = MAX_CLARIFICATION_TURNS;
        
        // 如果知识库有很好的匹配，可以减少澄清轮数，快速推荐
        if (snippets != null && snippets.size() >= 3) {
            baseMax = Math.max(MIN_CLARIFICATION_TURNS, baseMax - 1);
        }
        
        // 缺失维度较多时，增加澄清的机会（但不超过MAX_CLARIFICATION_TURNS）
        if (missingDimensions != null && missingDimensions.size() >= 3) {
            baseMax = Math.min(MAX_CLARIFICATION_TURNS, baseMax);
        }
        
        return baseMax;
    }

    private String selectBestDimensionToAsk(List<String> missingDimensions,
                                           AdvisorKnowledgeBaseService.ClarificationPlan plan,
                                           List<ChatMessage> history,
                                           String intent,
                                           List<String> recentAskedDimensions) {
        // 【智能优先级策略】优先级从高到低：
        // 1. 使用知识库计划的必需维度顺序（品类特定优化）
        // 2. 跳过已最近被问过的维度
        // 3. 降级到备用全局优先级
        
        List<String> requiredDimensions = plan != null ? plan.requiredDimensions() : List.of();
        List<String> filteredMissing = missingDimensions.stream()
                .filter(item -> !hasAskedDimensionRecently(history, item, intent))
                .toList();

        List<String> candidateMissing = filteredMissing.isEmpty() ? missingDimensions : filteredMissing;
        if (candidateMissing.isEmpty()) {
            return "";
        }

        Set<String> cooldownDimensions = recentAskedDimensions.stream()
            .map(this::normalizeDimensionKey)
            .filter(item -> !item.isBlank())
            .collect(Collectors.toSet());
        
        // 【第1优先级】按知识库计划的必需维度顺序选择
        if (!requiredDimensions.isEmpty()) {
            for (String required : requiredDimensions) {
                String normalizedReq = required.toLowerCase(Locale.ROOT).trim();
                String matched = filteredMissing.stream()
                        .filter(missing -> {
                            String normalizedMissing = missing.toLowerCase(Locale.ROOT);
                            return normalizedMissing.contains(normalizedReq) || normalizedReq.contains(normalizedMissing);
                        })
                        .findFirst()
                        .orElse(null);
                if (matched != null) {
                    if (!cooldownDimensions.contains(normalizeDimensionKey(matched))) {
                        return matched;
                    }
                }
            }
        }
        
        // 【第2优先级】降级到全局优先级
        String[] fallbackPriority = {"budget", "scene", "usage", "brand", "type", "function", "feature", "color", "appearance", "style", "preference"};
        for (String priority : fallbackPriority) {
            String dimension = candidateMissing.stream()
                    .filter(d -> {
                        String normalized = d.toLowerCase(Locale.ROOT);
                        return normalized.contains(priority) || priority.contains(normalized);
                    })
                    .findFirst()
                    .orElse(null);
            
            if (dimension != null && !dimension.isBlank() && !cooldownDimensions.contains(normalizeDimensionKey(dimension))) {
                return dimension;
            }
        }
        
        // 【第3优先级】冷却后仍无可选时，退化为任意缺失维度，防止会话卡住。
        return candidateMissing.stream()
                .filter(item -> !cooldownDimensions.contains(normalizeDimensionKey(item)))
                .findFirst()
                .orElse(candidateMissing.stream().findFirst().orElse(""));
    }

    private List<String> recentAskedDimensions(String sessionId) {
        if (isBlank(sessionId)) {
            return List.of();
        }
        Deque<String> deque = recentClarificationDimensionsBySession.get(sessionId);
        if (deque == null) {
            return List.of();
        }
        synchronized (deque) {
            return List.copyOf(deque);
        }
    }

    private void recordAskedDimension(String sessionId, String dimension) {
        if (isBlank(sessionId) || isBlank(dimension)) {
            return;
        }
        String normalized = normalizeDimensionKey(dimension);
        if (normalized.isBlank()) {
            return;
        }
        Deque<String> deque = recentClarificationDimensionsBySession
                .computeIfAbsent(sessionId, ignored -> new ArrayDeque<>());
        synchronized (deque) {
            deque.remove(normalized);
            deque.addLast(normalized);
            while (deque.size() > CLARIFICATION_DIMENSION_COOLDOWN) {
                deque.removeFirst();
            }
        }
    }

    private Set<UUID> recentRecommendedProductIds(String sessionId) {
        if (isBlank(sessionId)) {
            return Set.of();
        }
        Deque<UUID> deque = recentRecommendedProductsBySession.get(sessionId);
        if (deque == null) {
            return Set.of();
        }
        synchronized (deque) {
            return Set.copyOf(deque);
        }
    }

    private void recordRecommendedProducts(String sessionId, List<ChatRecommendationResponse> recommendations) {
        if (isBlank(sessionId) || recommendations == null || recommendations.isEmpty()) {
            return;
        }
        Deque<UUID> deque = recentRecommendedProductsBySession
                .computeIfAbsent(sessionId, ignored -> new ArrayDeque<>());
        synchronized (deque) {
            for (ChatRecommendationResponse item : recommendations) {
                if (item == null || item.productId() == null) {
                    continue;
                }
                deque.remove(item.productId());
                deque.addLast(item.productId());
            }
            while (deque.size() > RECOMMENDATION_PRODUCT_COOLDOWN) {
                deque.removeFirst();
            }
        }
    }

    private String mergeContext(String message, List<String> recentUserMessages) {
        if (recentUserMessages.isEmpty()) {
            return safe(message);
        }
        return safe(message) + "\n" + String.join("\n", recentUserMessages);
    }

    private String ensureSummaryPreface(String reply,
                                        String intent,
                                        BigDecimal budget,
                                        String scene,
                                        String functionPreference,
                                        String appearancePreference,
                                        String brandPreference,
                                        String typePreference,
                                        String colorPreference) {
        String text = safe(reply).trim();
        if (text.contains("需求速览") || text.contains("主推") || text.contains("购买建议")) {
            return text;
        }
        String summary = "【需求速览】"
            + "品类=" + blankToDefault(intent, "通用导购")
                + "，预算=" + budgetSummary(budget)
                + "，场景=" + blankToDefault(scene, "未明确")
                + "，品牌=" + blankToDefault(brandPreference, "未明确")
                + "，类型=" + blankToDefault(typePreference, "未明确")
                + "，功能=" + blankToDefault(functionPreference, "未明确")
                + "，颜色=" + blankToDefault(colorPreference, "未明确");
        if (text.isBlank()) {
            return summary;
        }
        return summary + "\n" + text;
    }

    private String buildRuleBasedReply(String intent, BigDecimal budget, String scene, List<Product> products) {
        List<Product> top = products.stream().limit(MAX_MAIN_RECOMMENDATIONS).toList();
        String recommendation = top.stream()
                .map(product -> "- " + safe(product.getName()) + "（" + formatPrice(product.getPrice()) + " 元）：" + safe(product.getDescription()))
                .collect(Collectors.joining("\n"));

        if (recommendation.isBlank()) {
            return "我先不盲推。你告诉我预算、使用场景和最看重的 1 个点（例如续航/轻薄/容量），我会马上给你精简到 1-3 款。";
        }

        return "【需求速览】品类=" + blankToDefault(intent, "未明确品类")
            + "，预算=" + budgetSummary(budget)
            + "，场景=" + blankToDefault(scene, "未明确") + "\n"
            + "【主推/备选】我先给你 1-3 款可直接比较的商品：\n"
            + recommendation + "\n"
            + "【购买建议】如果你再补 1 个偏好（品牌/尺寸/颜色/材质），我可以收敛成最终 1-2 款并给下单顺序。";
    }

    private String detectedIntentLabel(String intent, String scene) {
        String normalizedIntent = blankToDefault(intent, "通用导购");
        if (isBlank(scene)) {
            return normalizedIntent;
        }
        return normalizedIntent + " / " + scene;
    }

    private String budgetSummary(BigDecimal budget) {
        return budget == null ? "未明确" : budget.stripTrailingZeros().toPlainString() + " 元";
    }

    private String formatPrice(BigDecimal price) {
        if (price == null) {
            return "待定";
        }
        return price.stripTrailingZeros().toPlainString();
    }

    private List<String> parseTags(String tags) {
        if (isBlank(tags)) {
            return List.of();
        }
        return Pattern.compile("[,，]")
                .splitAsStream(tags)
                .map(String::trim)
                .filter(item -> !item.isBlank())
                .limit(4)
                .toList();
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
            ChatLanguageModel model = ensureModel();
            if (model == null) {
                return null;
            }
            shoppingAdvisorAiService = AiServices.builder(ShoppingAdvisorAiService.class)
                    .chatLanguageModel(model)
                    .chatMemoryProvider(shoppingAdvisorMemoryProvider)
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
            ChatLanguageModel model = ensureModel();
            if (model == null) {
                return null;
            }
            directShoppingAdvisorAiService = AiServices.builder(DirectShoppingAdvisorAiService.class)
                    .chatLanguageModel(model)
                    .chatMemoryProvider(directAdvisorMemoryProvider)
                    .build();
            return directShoppingAdvisorAiService;
        }
    }

    private ChatLanguageModel ensureModel() {
        if (chatLanguageModel != null) {
            return chatLanguageModel;
        }

        synchronized (this) {
            if (chatLanguageModel != null) {
                return chatLanguageModel;
            }

            AiProperties.Provider provider = currentProvider();
            if (provider == null || isBlank(provider.getApiKey()) || isBlank(provider.getModelName())) {
                recordFallback("MODEL_CONFIG_MISSING");
                return null;
            }

            try {
                OpenAiChatModel.OpenAiChatModelBuilder builder = OpenAiChatModel.builder()
                        .apiKey(provider.getApiKey())
                        .modelName(provider.getModelName())
                        .timeout(provider.getTimeout() == null ? Duration.ofSeconds(90) : provider.getTimeout())
                        .logRequests(provider.isLogRequests())
                        .logResponses(provider.isLogResponses());

                if (!isBlank(provider.getBaseUrl())) {
                    builder.baseUrl(provider.getBaseUrl());
                }
                if (provider.getMaxRetries() != null) {
                    builder.maxRetries(provider.getMaxRetries());
                }

                chatLanguageModel = builder.build();
                runtimeStatusRef.set(new AiRuntimeStatus(currentProviderName(), activeModelName(), false,
                        "MODEL_READY", Instant.now(), consecutiveFallbacks.get()));
                return chatLanguageModel;
            } catch (Exception error) {
                recordFallback("MODEL_INIT_FAILED: " + summarizeError(error));
                return null;
            }
        }
    }

    private void invalidateAiCaches() {
        chatLanguageModel = null;
        shoppingAdvisorAiService = null;
        directShoppingAdvisorAiService = null;
    }

    private AiProperties.Provider currentProvider() {
        String providerName = resolveProviderNameWithFallback();
        return switch (providerName) {
            case "siliconflow" -> aiProperties.getSiliconflow();
            case "chatgpt" -> aiProperties.getChatgpt();
            default -> aiProperties.getDeepseek();
        };
    }

    private String currentProviderName() {
        String provider = safe(aiProperties.getProvider()).trim().toLowerCase(Locale.ROOT);
        return provider.isBlank() ? "deepseek" : provider;
    }

    private String resolveProviderNameWithFallback() {
        String preferred = currentProviderName();
        if (isProviderConfigured(preferred)) {
            return preferred;
        }
        if (isProviderConfigured("deepseek")) {
            return "deepseek";
        }
        if (isProviderConfigured("chatgpt")) {
            return "chatgpt";
        }
        if (isProviderConfigured("siliconflow")) {
            return "siliconflow";
        }
        return preferred;
    }

    private boolean hasAvailableModelProvider() {
        return isProviderConfigured("deepseek")
                || isProviderConfigured("chatgpt")
                || isProviderConfigured("siliconflow");
    }

    private boolean isProviderConfigured(String providerName) {
        AiProperties.Provider provider = switch (safe(providerName).toLowerCase(Locale.ROOT)) {
            case "chatgpt" -> aiProperties.getChatgpt();
            case "siliconflow" -> aiProperties.getSiliconflow();
            default -> aiProperties.getDeepseek();
        };
        return provider != null && !isBlank(provider.getApiKey()) && !isBlank(provider.getModelName());
    }

    private String activeModelName() {
        AiProperties.Provider provider = currentProvider();
        return provider == null ? "" : safe(provider.getModelName());
    }

    private String activeModelLabel() {
        return currentProviderName() + ":" + blankToDefault(activeModelName(), "unknown-model");
    }

    private String normalizeReply(String reply) {
        if (isBlank(reply)) {
            return null;
        }
        String cleaned = reply.replaceAll("(?s)<think>.*?</think>\\s*", "")
                .replace("\r\n", "\n")
                .trim();
        return cleaned.isBlank() ? null : cleaned;
    }

    private void recordSuccess() {
        consecutiveFallbacks.set(0);
        runtimeStatusRef.set(new AiRuntimeStatus(currentProviderName(), activeModelName(), false,
                "OK", Instant.now(), 0));
    }

    private void recordFallback(String reason) {
        int count = consecutiveFallbacks.incrementAndGet();
        runtimeStatusRef.set(new AiRuntimeStatus(currentProviderName(), activeModelName(), true,
                blankToDefault(reason, "UNKNOWN"), Instant.now(), count));
    }

    private String summarizeError(Throwable error) {
        if (error == null) {
            return "unknown";
        }
        String message = error.getMessage();
        if (isBlank(message)) {
            return error.getClass().getSimpleName();
        }
        return message.length() > 140 ? message.substring(0, 140) : message;
    }

    private boolean containsAny(String content, List<String> keywords) {
        return keywords.stream().anyMatch(content::contains);
    }

    private String blankToDefault(String value, String fallback) {
        String normalized = safe(value).trim();
        return normalized.isBlank() ? fallback : normalized;
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
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

    private record SessionRequirementProfile(String intent,
                                             BigDecimal budget,
                                             String scene,
                                             String functionPreference,
                                             String appearancePreference,
                                             String brandPreference,
                                             String typePreference,
                                             String colorPreference,
                                             Instant updatedAt) {
    }
}
