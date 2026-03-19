package com.example.ecommerce.service;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.annotation.PostConstruct;

@Service
public class AdvisorKnowledgeBaseService {
    private static final Logger log = LoggerFactory.getLogger(AdvisorKnowledgeBaseService.class);
    private static final Pattern TOKEN_PATTERN = Pattern.compile("[\\s,，。！？!?:：/\\-]+");
    private static final String KNOWLEDGE_FILE = "ai/advisor-knowledge-base.json";
    private static final String GENERIC_PLAN_KEY = "_generic_";
    private static final int VECTOR_SIZE = 256;

    private final ObjectMapper objectMapper;
    private final AtomicReference<List<KnowledgeEntry>> entriesRef = new AtomicReference<>(List.of());
    private final AtomicReference<List<IndexedKnowledgeEntry>> indexedEntriesRef = new AtomicReference<>(List.of());
    private final AtomicReference<Map<String, ClarificationPlan>> clarificationPlansRef = new AtomicReference<>(Map.of());

    public AdvisorKnowledgeBaseService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void initialize() {
        try (InputStream inputStream = new ClassPathResource(KNOWLEDGE_FILE).getInputStream()) {
            KnowledgeDocument document = objectMapper.readValue(inputStream, KnowledgeDocument.class);
            List<KnowledgeEntry> entries = document.entries() == null ? List.of() : document.entries().stream()
                    .filter(entry -> entry != null && !isBlank(entry.title()) && !isBlank(entry.summary()))
                    .toList();
            entriesRef.set(entries);
            indexedEntriesRef.set(buildIndexedEntries(entries));
            clarificationPlansRef.set(loadClarificationPlans(document.questionFlows()));
            log.info("Advisor knowledge base loaded: {} entries", entries.size());
        } catch (IOException error) {
            List<KnowledgeEntry> fallback = defaultEntries();
            entriesRef.set(fallback);
            indexedEntriesRef.set(buildIndexedEntries(fallback));
            clarificationPlansRef.set(defaultClarificationPlans());
            log.warn("Load advisor knowledge base failed, fallback to defaults: {}", error.toString());
        }
    }

    public ClarificationPlan resolveClarificationPlan(String intent, List<KnowledgeSnippet> snippets) {
        Map<String, ClarificationPlan> planMap = clarificationPlansRef.get();
        if (planMap == null || planMap.isEmpty()) {
            planMap = defaultClarificationPlans();
        }

        String normalizedIntent = normalizeIntentKey(intent);
        if (!normalizedIntent.isBlank() && planMap.containsKey(normalizedIntent)) {
            return planMap.get(normalizedIntent);
        }

        if (snippets != null) {
            for (KnowledgeSnippet snippet : snippets) {
                if (snippet == null) {
                    continue;
                }
                String normalizedCategory = normalizeIntentKey(snippet.category());
                if (!normalizedCategory.isBlank() && planMap.containsKey(normalizedCategory)) {
                    return planMap.get(normalizedCategory);
                }
            }
        }

        return planMap.getOrDefault(GENERIC_PLAN_KEY, buildGenericPlan());
    }

    public List<KnowledgeSnippet> search(String query, int limit) {
        List<IndexedKnowledgeEntry> indexedEntries = indexedEntriesRef.get();
        if (indexedEntries.isEmpty()) {
            return List.of();
        }

        int safeLimit = Math.max(1, limit);
        String normalizedQuery = normalize(query);
        Set<String> queryTokens = tokenize(query);
        float[] queryVector = vectorize(query);

        List<ScoredEntry> scoredEntries = new ArrayList<>();
        for (IndexedKnowledgeEntry indexed : indexedEntries) {
            double score = score(indexed, normalizedQuery, queryTokens, queryVector);
            if (score <= 0.0d) {
                continue;
            }
            scoredEntries.add(new ScoredEntry(indexed.entry(), score));
        }

        if (scoredEntries.isEmpty()) {
            return indexedEntries.stream()
                    .limit(Math.min(safeLimit, 2))
                    .map(indexed -> indexed.entry())
                    .map(entry -> new KnowledgeSnippet(entry.title(), entry.category(), entry.summary(), "基础导购知识"))
                    .toList();
        }

        return scoredEntries.stream()
                .sorted(Comparator.comparingDouble(ScoredEntry::score).reversed())
                .limit(safeLimit)
                .map(scored -> new KnowledgeSnippet(
                        scored.entry().title(),
                        scored.entry().category(),
                        scored.entry().summary(),
                        buildMatchReason(scored.entry(), queryTokens)))
                .toList();
    }

    private double score(IndexedKnowledgeEntry indexed,
                         String normalizedQuery,
                         Set<String> queryTokens,
                         float[] queryVector) {
        KnowledgeEntry entry = indexed.entry();
        String searchable = indexed.searchable();
        Set<String> entryTokens = indexed.tokens();

        double score = 0.0d;
        if (!normalizedQuery.isBlank() && searchable.contains(normalizedQuery)) {
            score += 2.2d;
        }

        long tokenHits = queryTokens.stream().filter(searchable::contains).count();
        if (!queryTokens.isEmpty()) {
            score += (double) tokenHits / queryTokens.size() * 2.0d;
        }

        score += cosine(queryVector, indexed.vector()) * 1.6d;
        score += keywordCoverage(queryTokens, entryTokens) * 1.1d;
        if (containsNormalizedPhrase(searchable, normalizedQuery)) {
            score += 0.9d;
        }

        if (containsAny(searchable, List.of("预算")) && containsAny(normalizedQuery, List.of("预算", "价位", "多少钱", "以内"))) {
            score += 0.9d;
        }
        if (containsAny(searchable, List.of("场景")) && containsAny(normalizedQuery, List.of("通勤", "办公", "运动", "学生", "出差", "旅行"))) {
            score += 0.8d;
        }
        if (containsAny(searchable, List.of("母婴", "婴儿", "宝宝")) && containsAny(normalizedQuery, List.of("母婴", "宝宝", "婴儿", "纸尿裤", "奶粉"))) {
            score += 1.0d;
        }
        if (containsAny(searchable, List.of("耳机", "降噪")) && containsAny(normalizedQuery, List.of("耳机", "降噪", "通话", "续航"))) {
            score += 1.0d;
        }
        if (containsAny(searchable, List.of("笔记本", "电脑")) && containsAny(normalizedQuery, List.of("笔记本", "电脑", "学习", "办公", "游戏"))) {
            score += 1.0d;
        }
        return score;
    }

    private List<IndexedKnowledgeEntry> buildIndexedEntries(List<KnowledgeEntry> entries) {
        if (entries == null || entries.isEmpty()) {
            return List.of();
        }
        return entries.stream()
                .map(this::indexEntry)
                .toList();
    }

    private IndexedKnowledgeEntry indexEntry(KnowledgeEntry entry) {
        String searchable = normalize(String.join(" ",
                safe(entry.title()),
                safe(entry.category()),
                safe(entry.summary()),
                String.join(" ", entry.tags() == null ? List.of() : entry.tags())));
        Set<String> tokens = tokenize(searchable);
        float[] vector = vectorize(searchable);
        return new IndexedKnowledgeEntry(entry, searchable, tokens, vector);
    }

    private String buildMatchReason(KnowledgeEntry entry, Set<String> queryTokens) {
        String searchable = normalize(String.join(" ", safe(entry.title()), safe(entry.summary()), safe(entry.category())));
        List<String> matched = queryTokens.stream()
                .filter(token -> token.length() >= 2 && searchable.contains(token))
                .limit(3)
                .toList();
        if (matched.isEmpty()) {
            return "基础导购知识";
        }
        return "匹配关键词：" + String.join("/", matched);
    }

    private Set<String> tokenize(String value) {
        return TOKEN_PATTERN.splitAsStream(normalize(value))
                .map(String::trim)
                .filter(token -> token.length() >= 2)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }

    private float[] vectorize(String text) {
        float[] vector = new float[VECTOR_SIZE];
        String normalized = normalize(text);
        List<String> features = new ArrayList<>(tokenize(normalized));

        String compact = normalized.replace(" ", "");
        for (int index = 0; index < compact.length() - 1; index++) {
            String bigram = compact.substring(index, index + 2).trim();
            if (!bigram.isBlank()) {
                features.add(bigram);
            }
        }

        for (String feature : features) {
            int bucket = Math.abs(feature.hashCode()) % VECTOR_SIZE;
            vector[bucket] += 1.0f;
        }

        float norm = 0.0f;
        for (float item : vector) {
            norm += item * item;
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
        if (left == null || right == null || left.length == 0 || right.length == 0) {
            return 0.0d;
        }
        int length = Math.min(left.length, right.length);
        double dot = 0.0d;
        for (int index = 0; index < length; index++) {
            dot += left[index] * right[index];
        }
        return dot;
    }

    private double keywordCoverage(Set<String> queryTokens, Set<String> entryTokens) {
        if (queryTokens == null || queryTokens.isEmpty() || entryTokens == null || entryTokens.isEmpty()) {
            return 0.0d;
        }
        long covered = queryTokens.stream()
                .filter(token -> entryTokens.stream().anyMatch(item -> item.contains(token) || token.contains(item)))
                .count();
        return (double) covered / queryTokens.size();
    }

    private boolean containsNormalizedPhrase(String content, String normalizedQuery) {
        if (isBlank(content) || isBlank(normalizedQuery)) {
            return false;
        }
        String compactContent = normalize(content).replace(" ", "");
        String compactQuery = normalize(normalizedQuery).replace(" ", "");
        return !compactQuery.isBlank() && compactContent.contains(compactQuery);
    }

    private List<KnowledgeEntry> defaultEntries() {
        return List.of(
                new KnowledgeEntry("预算追问策略", "通用导购", "预算不明确时先确认可接受价位区间，再给分层推荐（入门/均衡/进阶）。", List.of("预算", "追问", "分层")),
                new KnowledgeEntry("场景优先策略", "通用导购", "先锁定使用场景（通勤/办公/运动/母婴），再匹配关键参数，避免泛化推荐。", List.of("场景", "参数", "推荐")),
                new KnowledgeEntry("耳机选购关键", "数码办公", "通勤优先降噪和佩戴舒适，办公优先麦克风与多设备切换，运动优先稳固和防汗。", List.of("耳机", "降噪", "通勤", "办公", "运动")));
    }

    private Map<String, ClarificationPlan> loadClarificationPlans(List<QuestionFlowEntry> flows) {
        if (flows == null || flows.isEmpty()) {
            return defaultClarificationPlans();
        }

        Map<String, ClarificationPlan> planMap = new java.util.LinkedHashMap<>();
        planMap.put(GENERIC_PLAN_KEY, buildGenericPlan());

        for (QuestionFlowEntry flow : flows) {
            if (flow == null) {
                continue;
            }
            ClarificationPlan plan = toClarificationPlan(flow);
            if (plan == null) {
                continue;
            }

            List<String> keys = new ArrayList<>();
            if (!isBlank(flow.intent())) {
                keys.add(flow.intent());
            }
            if (flow.aliases() != null) {
                keys.addAll(flow.aliases());
            }

            for (String key : keys) {
                String normalized = normalizeIntentKey(key);
                if (!normalized.isBlank()) {
                    planMap.put(normalized, plan);
                }
            }
        }

        return Map.copyOf(planMap);
    }

    private ClarificationPlan toClarificationPlan(QuestionFlowEntry flow) {
        String categoryQuestion = firstNonBlank(flow.categoryQuestion(), "你是想买哪一类商品呀？比如耳机、背包、键盘、鼠标或家居日用品。");
        String budgetQuestion = firstNonBlank(flow.budgetQuestion(), "预算大概控制在多少比较合适呢？");
        String usageQuestion = firstNonBlank(flow.sceneQuestion(), "主要使用场景是什么呢？比如通勤、办公、运动、家用或送礼。");
        String functionQuestion = firstNonBlank(flow.functionQuestion(), "你更看重哪些功能点？比如降噪、续航、防水、收纳分区、静音手感等。");
        String appearanceQuestion = firstNonBlank(flow.appearanceQuestion(), "外观上有偏好吗？比如颜色、材质、风格、大小或佩戴观感。");
        String preferenceQuestion = firstNonBlank(flow.preferenceQuestion(), "还有没有特别在意的点？比如重量、降噪、材质、尺寸、颜色或品牌。");
        List<String> requiredDimensions = normalizeRequiredDimensions(flow.requiredDimensions());
        return new ClarificationPlan(categoryQuestion, budgetQuestion, usageQuestion, functionQuestion, appearanceQuestion, preferenceQuestion, requiredDimensions);
    }

    private Map<String, ClarificationPlan> defaultClarificationPlans() {
        Map<String, ClarificationPlan> defaults = new java.util.LinkedHashMap<>();
        ClarificationPlan generic = buildGenericPlan();
        defaults.put(GENERIC_PLAN_KEY, generic);
        defaults.put(normalizeIntentKey("耳机"), new ClarificationPlan(
                "你想看耳机是对的，我先帮你收窄。",
                "耳机预算你更倾向 200 内、300 到 500，还是 500 以上呢？",
                "主要是通勤降噪、开会通话，还是运动佩戴呢？",
            "功能上你更在意降噪强度、音质表现，还是通话清晰度？",
            "外观上偏向入耳式还是头戴式？颜色有没有偏好？",
                "你更在意降噪、音质、麦克风清晰度，还是佩戴舒适度？",
                List.of("budget", "usage", "function", "appearance")));
        defaults.put(normalizeIntentKey("背包"), new ClarificationPlan(
                "背包这个方向很好，我先按你的使用方式来筛。",
                "预算想控制在多少以内？比如 100 到 200、200 到 300、300 以上。",
                "主要是通勤、出差、上课，还是短途旅行场景？",
            "功能上有刚需吗？比如 14 寸电脑位、防泼水、分仓、肩带减压。",
            "外观你更偏简约商务、运动户外，还是学院风？",
                "有容量或细节要求吗？比如能放 14 寸本、防泼水、轻量、肩带舒适。",
                List.of("budget", "usage", "function", "appearance")));
        defaults.put(normalizeIntentKey("笔记本"), new ClarificationPlan(
                "笔记本我可以按你的工作强度来配。",
                "预算你希望控制在多少？我可以按价位给你分档。",
                "主要做办公、学习、设计剪辑，还是游戏？",
            "功能上更偏轻薄续航、性能释放，还是屏幕素质与接口扩展？",
            "外观上偏好轻薄便携还是金属质感？有颜色偏好吗？",
                "还有偏好吗？比如轻薄续航、屏幕素质、接口、品牌。",
                List.of("budget", "usage", "function", "appearance")));
        defaults.put(normalizeIntentKey("个护母婴"), new ClarificationPlan(
                "母婴用品我会更谨慎一点来筛。",
                "预算大概在什么范围呢？",
                "是日常补货、外出使用，还是宝宝敏感期专项需求？",
            "功能上更关注透气、回渗、温和配方，还是便携与补货效率？",
            "外观包装上有偏好吗？例如独立小包、简约分装、低香型。",
                "还有关键信息吗？比如月龄、肤质、是否需要避开香精。",
                List.of("budget", "usage", "function", "appearance")));
        return Map.copyOf(defaults);
    }

    private ClarificationPlan buildGenericPlan() {
        return new ClarificationPlan(
                "我先帮你确认品类，这样推荐不会跑偏。",
                "预算大概想控制在多少以内呢？",
                "主要使用场景是什么？比如通勤、办公、运动、家用或送礼。",
            "功能上你最在意什么？比如性能、续航、防水、容量、静音等。",
            "外观你偏什么风格？比如简约、运动、商务、可爱，以及颜色偏好。",
                "再补一个偏好就能更准：你更看重品牌、性能、颜值还是性价比？",
                List.of("budget", "usage", "function", "appearance"));
    }

        private List<String> normalizeRequiredDimensions(List<String> dimensions) {
        List<String> normalized = (dimensions == null ? List.<String>of() : dimensions).stream()
            .map(this::normalizeIntentKey)
            .filter(value -> !value.isBlank())
            .toList();
        if (!normalized.isEmpty()) {
            return normalized;
        }
        return List.of("budget", "usage", "function");
        }

    private String normalizeIntentKey(String value) {
        return normalize(value)
                .replace(" ", "")
                .replace("/", "")
                .replace("-", "");
    }

    private String firstNonBlank(String value, String fallback) {
        return isBlank(value) ? fallback : value.trim();
    }

    private boolean containsAny(String content, List<String> keywords) {
        return keywords.stream().anyMatch(content::contains);
    }

    private String normalize(String value) {
        return safe(value).toLowerCase(Locale.ROOT).trim();
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record KnowledgeDocument(@JsonProperty("entries") List<KnowledgeEntry> entries,
                                     @JsonProperty("questionFlows") List<QuestionFlowEntry> questionFlows) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record KnowledgeEntry(
            @JsonProperty("title") String title,
            @JsonProperty("category") String category,
            @JsonProperty("summary") String summary,
            @JsonProperty("tags") List<String> tags) {
    }

        @JsonIgnoreProperties(ignoreUnknown = true)
        private record QuestionFlowEntry(
            @JsonProperty("intent") String intent,
            @JsonProperty("aliases") List<String> aliases,
            @JsonProperty("categoryQuestion") String categoryQuestion,
            @JsonProperty("budgetQuestion") String budgetQuestion,
            @JsonProperty("sceneQuestion") String sceneQuestion,
            @JsonProperty("functionQuestion") String functionQuestion,
            @JsonProperty("appearanceQuestion") String appearanceQuestion,
            @JsonProperty("requiredDimensions") List<String> requiredDimensions,
            @JsonProperty("preferenceQuestion") String preferenceQuestion) {
        }

    private record ScoredEntry(KnowledgeEntry entry, double score) {
    }

    private record IndexedKnowledgeEntry(KnowledgeEntry entry, String searchable, Set<String> tokens, float[] vector) {
    }

    public record KnowledgeSnippet(String title, String category, String summary, String matchReason) {
    }

    public record ClarificationPlan(String categoryQuestion,
                                    String budgetQuestion,
                                    String sceneQuestion,
                                    String functionQuestion,
                                    String appearanceQuestion,
                                    String preferenceQuestion,
                                    List<String> requiredDimensions) {
        public String questionByDimension(String dimension) {
            String normalized = dimension == null ? "" : dimension.trim().toLowerCase(Locale.ROOT);
            return switch (normalized) {
                case "budget" -> budgetQuestion;
                case "usage", "scene" -> sceneQuestion;
                case "function", "feature" -> functionQuestion;
                case "appearance", "style", "color" -> appearanceQuestion;
                default -> preferenceQuestion;
            };
        }
    }
}
