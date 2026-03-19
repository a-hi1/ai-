package com.example.ecommerce.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
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
    private static final List<String> GENERIC_ACCEPT_WORDS = List.of("都可以", "随便", "无所谓", "你定", "你决定", "没要求", "一般就行");
    private static final int MIN_REQUIRED_DIMENSIONS_TO_RECOMMEND = 3;
    private static final int MIN_REQUIRED_DIMENSIONS_WITHOUT_AI = 4;
    private static final int MAX_CLARIFICATION_TURNS = 3;
    private static final int MIN_CLARIFICATION_TURNS = 1;
    private static final List<String> FOLLOW_UP_WORDS = List.of("这个", "这个吧", "就这个", "可以", "行", "好", "按你说的", "继续", "嗯", "对", "是的");
    private static final List<String> CATEGORY_SWITCH_SIGNALS = List.of("换个", "改要", "还想要", "另外要", "再要", "还要一个", "我再问", "对了", "顺便问", "帮我也");

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

    private volatile ChatLanguageModel chatLanguageModel;
    private volatile ShoppingAdvisorAiService shoppingAdvisorAiService;
    private volatile DirectShoppingAdvisorAiService directShoppingAdvisorAiService;

    private final ConcurrentMap<String, ChatMemory> shoppingAdvisorMemories = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, ChatMemory> directAdvisorMemories = new ConcurrentHashMap<>();

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
                                    AdvisorToolContext advisorToolContext) {
        this.aiProperties = aiProperties;
        this.retrievalGatewayService = retrievalGatewayService;
        this.productRecommendationService = productRecommendationService;
        this.advisorKnowledgeBaseService = advisorKnowledgeBaseService;
        this.chatMessageRepository = chatMessageRepository;
        this.orderItemRepository = orderItemRepository;
        this.productRepository = productRepository;
        this.shoppingAdvisorTools = shoppingAdvisorTools;
        this.advisorToolContext = advisorToolContext;
        runtimeStatusRef.set(AiRuntimeStatus.initial(currentProviderName(), activeModelName()));
    }

    public Mono<ChatAdvicePayload> advise(UUID userId, String message, String sessionId) {
        // 🔒 会话隔离：按 sessionId 查询消息，防止新会话使用旧会话的历史记录
        String effectiveSessionId = (sessionId == null || sessionId.trim().isEmpty()) ? "default" : sessionId;
        
        return Mono.zip(
                        // ✅ 改为按 sessionId 过滤，确保新会话不会继承旧会话的数据
                        chatMessageRepository.findByUserIdAndSessionId(userId, effectiveSessionId).collectList(),
                        retrievalGatewayService.search(message, 16, null, null)
                                .map(RetrievalGatewayService.RetrievalChunk::product)
                                .collectList(),
                        productRepository.findAll().collectList(),
                        orderItemRepository.findAll().collectList())
                .flatMap(tuple -> Mono.fromCallable(() -> buildAdvice(userId, message, effectiveSessionId, tuple.getT1(), tuple.getT2(), tuple.getT3(), tuple.getT4()))
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
                    List<Product> ranked = rankAndFilterProducts(message, budget, intent, retrievedProducts, allProducts, salesMap);
                    
                    if (ranked.isEmpty()) {
                        String reply = "按你的条件没找到完全匹配产品，建议放宽条件试试。";
                        return new ChatAdvicePayload(reply, List.of(), List.of(), List.of(), intent, budgetSummary(budget), true);
                    }
                    
                    // 快速推荐：仅前3件 + 简洁文案
                    List<ChatRecommendationResponse> recommendations = toRecommendations(ranked, salesMap, budget);
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
        
        Product top = products.getFirst();
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
                                          List<Product> retrievedProducts,
                                          List<Product> allProducts,
                                          List<OrderItem> soldItems) {
        List<ChatMessage> effectiveHistory = extractEffectiveHistory(history, 12);
        
        // 检测是否是品类切换。如果是，过滤历史只保留最近的相关部分
        boolean isCategorySwitch = detectCategorySwitch(message, effectiveHistory);
        List<ChatMessage> contextualHistory = isCategorySwitch
            ? filterHistoryForNewCategory(message, effectiveHistory)
            : effectiveHistory;
        
        String currentIntent = detectIntent(message);
        List<String> recentUserMessages = extractRecentUserMessages(contextualHistory, 6);
        List<String> scopedUserMessages = extractIntentScopedUserMessages(contextualHistory, currentIntent, 6);
        List<String> baseHistory = scopedUserMessages.isEmpty() ? recentUserMessages : scopedUserMessages;
        List<String> inferenceHistory = shouldUseHistoryForInference(message, baseHistory)
            ? baseHistory
            : List.of();
        int clarificationTurns = countRecentClarificationTurns(contextualHistory);
        String mergedUserContext = mergeContext(message, inferenceHistory);

        BigDecimal budget = resolveBudget(message, inferenceHistory);
        String intent = resolveIntent(message, inferenceHistory);
        if (isBlank(intent)) {
            intent = inferIntentFromProducts(retrievedProducts);
        }
        String scene = resolveScene(message, inferenceHistory, intent, isCategorySwitch);
        String functionPreference = resolveFunctionPreference(message, inferenceHistory, intent, isCategorySwitch);
        String appearancePreference = resolveAppearancePreference(message, inferenceHistory, intent, isCategorySwitch);

        boolean acceptsGenericPreference = acceptsGenericPreference(message, inferenceHistory);
        if (acceptsGenericPreference) {
            if (isBlank(scene)) {
                scene = defaultSceneByIntent(intent);
            }
            if (isBlank(functionPreference)) {
                functionPreference = defaultFunctionByIntent(intent);
            }
            if (isBlank(appearancePreference)) {
                appearancePreference = "简约通用";
            }
        }

        List<AdvisorKnowledgeBaseService.KnowledgeSnippet> snippets = advisorKnowledgeBaseService
                .search(mergedUserContext, 4);
        AdvisorKnowledgeBaseService.ClarificationPlan plan = advisorKnowledgeBaseService
                .resolveClarificationPlan(intent, snippets);

        List<String> missingDimensions = resolveMissingDimensions(plan, budget, scene, functionPreference, appearancePreference, intent);
        int fulfilledDimensions = countFulfilledDimensions(budget, scene, functionPreference, appearancePreference, intent);
        boolean aiReady = hasAvailableModelProvider();
        
        // 根据知识库计划动态调整澄清轮数
        int adaptiveMaxClarificationTurns = calculateAdaptiveMaxTurns(plan, missingDimensions, snippets);
        
        if (!missingDimensions.isEmpty()) {
            String dimensionToAsk = selectBestDimensionToAsk(missingDimensions, plan, contextualHistory, intent);

            if (!dimensionToAsk.isBlank() && clarificationTurns < adaptiveMaxClarificationTurns) {
                return buildClarificationPayload(intent, budget, dimensionToAsk, plan, snippets);
            }

            // 已连续澄清多轮时，自动补齐默认值并进入推荐，避免循环追问。
            if (fulfilledDimensions < MIN_REQUIRED_DIMENSIONS_TO_RECOMMEND
                    && clarificationTurns < adaptiveMaxClarificationTurns) {
                return buildClarificationPayload(intent, budget, "preference", plan, snippets);
            }

            if (isBlank(intent)) {
                intent = inferIntentFromProducts(retrievedProducts);
            }
            if (isBlank(scene)) {
                scene = defaultSceneByIntent(intent);
            }
            if (isBlank(functionPreference)) {
                functionPreference = defaultFunctionByIntent(intent);
            }
            if (isBlank(appearancePreference)) {
                appearancePreference = acceptsGenericPreference ? "简约通用" : "主流通用";
            }
        }

        if (countFulfilledDimensions(budget, scene, functionPreference, appearancePreference, intent) < MIN_REQUIRED_DIMENSIONS_TO_RECOMMEND
                && clarificationTurns < adaptiveMaxClarificationTurns) {
            return buildClarificationPayload(intent, budget, "preference", plan, snippets);
        }

        // 模型不可用时，提升澄清门槛，避免过早进入模板推荐导致“兜底感”过强。
        if (!aiReady
                && countFulfilledDimensions(budget, scene, functionPreference, appearancePreference, intent) < MIN_REQUIRED_DIMENSIONS_WITHOUT_AI
                && clarificationTurns < adaptiveMaxClarificationTurns + 1) {
            String dimensionToAsk = selectBestDimensionToAsk(missingDimensions, plan, contextualHistory, intent);
            if (dimensionToAsk.isBlank()) {
                dimensionToAsk = "preference";
            }
            return buildClarificationPayload(intent, budget, dimensionToAsk, plan, snippets);
        }

        Map<UUID, Integer> salesMap = aggregateSales(soldItems);
        List<Product> ranked = rankAndFilterProducts(mergedUserContext, budget, intent, retrievedProducts, allProducts, salesMap);

        if (ranked.isEmpty()) {
            String reply = "我已经按你给的条件在商品库里筛了一轮，暂时没有完全匹配的款。"
                    + "你可以告诉我是否放宽预算或功能要求，我马上给你第二版方案。";
            recordFallback("NO_MATCHED_PRODUCTS");
            return new ChatAdvicePayload(
                    reply,
                    List.of(),
                    List.of(),
                    buildInsights(intent, budget, snippets, true, "商品库无匹配结果"),
                    detectedIntentLabel(intent, scene),
                    budgetSummary(budget),
                    true);
        }

        List<ChatRecommendationResponse> recommendations = toRecommendations(ranked, salesMap, budget);
        List<Product> mainProducts = ranked.stream().limit(MAX_MAIN_RECOMMENDATIONS).toList();

        String aiReply = generateRecommendationReply(userId, message, contextualHistory, budget, scene, intent,
                functionPreference, appearancePreference, mainProducts, snippets, sessionId);

        recordSuccess();
        return new ChatAdvicePayload(
                aiReply,
                recommendations,
            List.of(),
                buildInsights(intent, budget, snippets, false, activeModelLabel()),
                detectedIntentLabel(intent, scene),
                budgetSummary(budget),
                false);
    }

    private ChatAdvicePayload buildClarificationPayload(String intent,
                                                        BigDecimal budget,
                                                        String missingDimension,
                                                        AdvisorKnowledgeBaseService.ClarificationPlan plan,
                                                        List<AdvisorKnowledgeBaseService.KnowledgeSnippet> snippets) {
        String question = safe(plan.questionByDimension(missingDimension));
        if (question.isBlank()) {
            question = "我先补一个关键信息，这样推荐不会跑偏：你最在意预算、场景还是功能？";
        }

        List<ChatInsightResponse> insights = new ArrayList<>();
        insights.add(new ChatInsightResponse("导购阶段", "需求补全中"));
        insights.add(new ChatInsightResponse("待确认维度", missingDimension));
        insights.add(new ChatInsightResponse("知识库命中", String.valueOf(snippets.size())));
        if (!snippets.isEmpty()) {
            String topic = snippets.stream()
                    .limit(2)
                    .map(item -> safe(item.category()) + ":" + safe(item.title()))
                    .collect(Collectors.joining(" | "));
            insights.add(new ChatInsightResponse("参考知识", topic));
        }

        return new ChatAdvicePayload(
                question,
                List.of(),
                List.of(),
                insights,
                detectedIntentLabel(intent, ""),
                budgetSummary(budget),
                false);
    }

    private String generateRecommendationReply(UUID userId,
                                               String message,
                                               List<ChatMessage> history,
                                               BigDecimal budget,
                                               String scene,
                                               String intent,
                                               String functionPreference,
                                               String appearancePreference,
                                               List<Product> products,
                                               List<AdvisorKnowledgeBaseService.KnowledgeSnippet> snippets,
                                               String sessionId) {
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

        String prompt = "你是中文电商导购员“小选”。必须严格按以下规则回复：\n"
                + "1. 只使用给定商品，不编造型号和价格。\n"
            + "2. 输出结构：需求速览、主推1款、备选1-2款、购买建议。\n"
            + "3. 每个推荐都要解释为何匹配用户场景，避免空泛话术。\n"
            + "4. 语气像真人导购，简洁自然，不要 Markdown 表格。\n"
            + "5. 不要重复追问已经给出的信息。\n\n"
                + "用户本轮消息：" + safe(message) + "\n"
                + "识别品类：" + blankToDefault(intent, "未识别") + "\n"
                + "预算：" + budgetSummary(budget) + "\n"
                + "场景：" + blankToDefault(scene, "未明确") + "\n"
                + "功能偏好：" + blankToDefault(functionPreference, "未明确") + "\n"
                + "外观偏好：" + blankToDefault(appearancePreference, "未明确") + "\n\n"
                + "知识库片段：\n" + knowledgeContext + "\n\n"
                + "最近对话：\n" + (historyContext.isBlank() ? "暂无" : historyContext) + "\n\n"
                + "候选商品：\n" + productContext;

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

        return merged.stream()
                .sorted((left, right) -> Double.compare(
                        productScore(mergedUserContext, right, salesMap.getOrDefault(right.getId(), 0), budget),
                        productScore(mergedUserContext, left, salesMap.getOrDefault(left.getId(), 0), budget)))
                .limit(12)
                .toList();
    }

    private double productScore(String query, Product product, int salesCount, BigDecimal budget) {
        double relevance = retrievalGatewayService.relevanceScore(query, product);
        double salesBoost = Math.min(1.0d, salesCount / 10.0d) * 0.25d;
        double budgetBoost = 0.0d;
        if (budget != null && product.getPrice() != null) {
            if (product.getPrice().compareTo(budget) <= 0) {
                budgetBoost = 0.2d;
            } else {
                budgetBoost = -0.15d;
            }
        }
        return relevance + salesBoost + budgetBoost;
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
                                                    String detail) {
        List<ChatInsightResponse> insights = new ArrayList<>();
        insights.add(new ChatInsightResponse("AI模式", fallback ? "规则兜底" : "实时模型"));
        insights.add(new ChatInsightResponse(fallback ? "兜底原因" : "模型信息", detail));
        insights.add(new ChatInsightResponse("需求类型", blankToDefault(intent, "未识别")));
        insights.add(new ChatInsightResponse("预算范围", budgetSummary(budget)));
        insights.add(new ChatInsightResponse("知识库命中", String.valueOf(snippets.size())));
        if (!snippets.isEmpty()) {
            String topic = snippets.stream()
                    .limit(3)
                    .map(item -> safe(item.category()) + ":" + safe(item.title()))
                    .collect(Collectors.joining(" | "));
            insights.add(new ChatInsightResponse("知识库主题", topic));
        }
        return insights;
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
                case "appearance", "style", "color" -> {
                    if (isBlank(appearancePreference)) missing.add("appearance");
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
                                           String intent) {
        // 【智能优先级策略】优先级从高到低：
        // 1. 使用知识库计划的必需维度顺序（品类特定优化）
        // 2. 跳过已最近被问过的维度
        // 3. 降级到备用全局优先级
        
        List<String> requiredDimensions = plan != null ? plan.requiredDimensions() : List.of();
        List<String> filteredMissing = missingDimensions.stream()
                .filter(item -> !hasAskedDimensionRecently(history, item, intent))
                .toList();
        
        if (filteredMissing.isEmpty()) {
            return "";
        }
        
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
                    return matched;
                }
            }
        }
        
        // 【第2优先级】降级到全局优先级
        String[] fallbackPriority = {"budget", "scene", "usage", "function", "feature", "appearance", "style", "preference"};
        for (String priority : fallbackPriority) {
            String dimension = filteredMissing.stream()
                    .filter(d -> {
                        String normalized = d.toLowerCase(Locale.ROOT);
                        return normalized.contains(priority) || priority.contains(normalized);
                    })
                    .findFirst()
                    .orElse(null);
            
            if (dimension != null && !dimension.isBlank()) {
                return dimension;
            }
        }
        
        // 【第3优先级】返回第一个缺失维度
        return filteredMissing.stream().findFirst().orElse("");
    }

    private String mergeContext(String message, List<String> recentUserMessages) {
        if (recentUserMessages.isEmpty()) {
            return safe(message);
        }
        return safe(message) + "\n" + String.join("\n", recentUserMessages);
    }

    private String buildRuleBasedReply(String intent, BigDecimal budget, String scene, List<Product> products) {
        List<Product> top = products.stream().limit(MAX_MAIN_RECOMMENDATIONS).toList();
        String recommendation = top.stream()
                .map(product -> "- " + safe(product.getName()) + "（" + formatPrice(product.getPrice()) + " 元）：" + safe(product.getDescription()))
                .collect(Collectors.joining("\n"));

        if (recommendation.isBlank()) {
            return "我先不盲推。你告诉我预算、使用场景和最看重的 1 个点（例如续航/轻薄/容量），我会马上给你精简到 1-3 款。";
        }

        return "我先按你这轮需求，给你一版更贴近实际的导购建议：\n"
            + "- 需求类型：" + blankToDefault(intent, "未明确品类") + "\n"
            + "- 预算范围：" + budgetSummary(budget) + "\n"
            + "- 使用场景：" + blankToDefault(scene, "未明确") + "\n\n"
            + "我优先推荐这几款（已尽量避开重复定位）：\n"
            + recommendation + "\n\n"
            + "你如果补充一个偏好（品牌/尺寸/颜色/是否要轻量），我可以继续收敛成最终 1-2 款。";
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
}
