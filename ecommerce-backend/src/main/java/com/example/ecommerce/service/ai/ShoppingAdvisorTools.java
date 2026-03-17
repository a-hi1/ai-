package com.example.ecommerce.service.ai;

import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.Duration;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.example.ecommerce.model.ChatMessage;
import com.example.ecommerce.model.OrderItem;
import com.example.ecommerce.model.Product;
import com.example.ecommerce.repository.ChatMessageRepository;
import com.example.ecommerce.repository.OrderItemRepository;
import com.example.ecommerce.repository.ProductRepository;
import com.example.ecommerce.service.ProductVectorStoreService;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

@Component
public class ShoppingAdvisorTools {
    private static final HttpClient WEATHER_HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private final ProductVectorStoreService productVectorStoreService;
    private final ProductRepository productRepository;
    private final OrderItemRepository orderItemRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final AdvisorToolContext advisorToolContext;

    public ShoppingAdvisorTools(ProductVectorStoreService productVectorStoreService,
                                ProductRepository productRepository,
                                OrderItemRepository orderItemRepository,
                                ChatMessageRepository chatMessageRepository,
                                AdvisorToolContext advisorToolContext) {
        this.productVectorStoreService = productVectorStoreService;
        this.productRepository = productRepository;
        this.orderItemRepository = orderItemRepository;
        this.chatMessageRepository = chatMessageRepository;
        this.advisorToolContext = advisorToolContext;
    }

    @Tool("Search the product catalog for products relevant to the user's shopping request. Always use this before giving recommendations.")
    public String searchRelevantProducts(@P("The user's shopping need or product keywords") String query) {
        List<Product> products = productVectorStoreService.search(query, 6).collectList().block();
        products = products == null ? List.of() : products;
        advisorToolContext.recordProducts("searchRelevantProducts", products);
        return formatProducts(products, "relevant products for: " + safe(query));
    }

    @Tool("Search products that fit within a user's budget.")
    public String searchProductsWithinBudget(@P("Product demand or keywords") String query,
                                             @P("Maximum acceptable price in RMB") double maxPrice) {
        BigDecimal budget = BigDecimal.valueOf(maxPrice);
        List<Product> products = productVectorStoreService.search(query, 12)
                .filter(product -> product.getPrice() != null && product.getPrice().compareTo(budget) <= 0)
                .take(6)
                .collectList()
                .block();
        products = products == null ? List.of() : products;
        advisorToolContext.recordProducts("searchProductsWithinBudget", products);
        return formatProducts(products, "products within budget " + budget.stripTrailingZeros().toPlainString());
    }

    @Tool("Get the user's recent shopping conversation context and preference hints.")
    public String getUserShoppingContext() {
        UUID userId = advisorToolContext.currentUserId();
        List<ChatMessage> messages = chatMessageRepository.findByUserId(userId).collectList().block();
        messages = messages == null ? List.of() : messages;
        if (messages.isEmpty()) {
            advisorToolContext.recordTrace("getUserShoppingContext: no history");
            return "No previous shopping conversation history.";
        }

        String history = messages.stream()
                .sorted(Comparator.comparing(ChatMessage::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder())))
                .skip(Math.max(0, messages.size() - 6L))
                .map(message -> ("USER".equalsIgnoreCase(message.getRole()) ? "User" : "Assistant") + ": " + safe(message.getContent()))
                .collect(Collectors.joining("\n"));
        advisorToolContext.recordTrace("getUserShoppingContext: " + Math.min(messages.size(), 6) + " messages");
        return history;
    }

    @Tool("Get current catalog sync health and the newest products in the catalog.")
    public String getCatalogStatus() {
        ProductVectorStoreService.CatalogStats stats = productVectorStoreService.getCatalogStats().block();
        if (stats == null) {
            advisorToolContext.recordTrace("getCatalogStatus: unavailable");
            return "Catalog status unavailable.";
        }

        String latest = stats.latestProducts().stream()
                .map(Product::getName)
                .collect(Collectors.joining(", "));
        advisorToolContext.recordTrace("getCatalogStatus: " + stats.productCount() + " products indexed");
        return "Indexed product count: " + stats.productCount() + ". Latest products: " + (latest.isBlank() ? "none" : latest);
    }

    @Tool("Get the most popular products by recent sales volume for a category hint.")
    public String getPopularProducts(@P("Optional category or scene hint, such as headphones, laptop, commute") String categoryHint) {
        List<Product> products = productRepository.findAll().collectList().block();
        List<OrderItem> orderItems = orderItemRepository.findAll().collectList().block();
        products = products == null ? List.of() : products;
        orderItems = orderItems == null ? List.of() : orderItems;

        Map<UUID, Integer> sales = orderItems.stream()
                .filter(item -> item.getProductId() != null)
                .collect(Collectors.groupingBy(OrderItem::getProductId, Collectors.summingInt(OrderItem::getQuantity)));

        String normalizedHint = safe(categoryHint).toLowerCase(Locale.ROOT);
        List<Product> popular = products.stream()
                .filter(product -> normalizedHint.isBlank() || contains(product, normalizedHint))
                .sorted(Comparator.comparingInt((Product product) -> sales.getOrDefault(product.getId(), 0)).reversed()
                        .thenComparing(Product::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(5)
                .toList();

        advisorToolContext.recordProducts("getPopularProducts", popular);
        advisorToolContext.recordTrace("getPopularProducts: " + popular.size() + " products for hint " + normalizedHint);
        return formatProducts(popular, "popular products for: " + safe(categoryHint));
    }

    @Tool("Get current weather summary for a city. Use this when user asks about weather or plans based on weather.")
    public String getWeatherSummary(@P("City name in Chinese or English, e.g. Beijing, Shanghai, Shenzhen") String city) {
        String normalizedCity = safe(city).trim();
        if (normalizedCity.isBlank()) {
            advisorToolContext.recordTrace("getWeatherSummary: empty city");
            return "请提供要查询天气的城市名称，例如：北京、上海、深圳。";
        }

        try {
            WeatherSnapshot weather = fetchWeather(normalizedCity);
            if (!weather.available()) {
                advisorToolContext.recordTrace("getWeatherSummary: unavailable for " + normalizedCity);
                return "暂时无法获取 " + normalizedCity + " 的天气，请稍后再试。";
            }

            advisorToolContext.recordTrace("getWeatherSummary: success for " + normalizedCity);
            return "城市：" + normalizedCity
                    + "；天气：" + blankToDefault(weather.description(), "未知")
                    + "；当前气温：" + blankToDefault(weather.tempC(), "未知") + "°C"
                    + "；体感温度：" + blankToDefault(weather.feelsLikeC(), "未知") + "°C"
                    + "；湿度：" + blankToDefault(weather.humidity(), "未知") + "%";
        } catch (Exception error) {
            advisorToolContext.recordTrace("getWeatherSummary: failed - " + error.getClass().getSimpleName());
            return "暂时无法获取 " + normalizedCity + " 的天气，请稍后重试。";
        }
    }

    @Tool("Get current date, weekday, holiday hint and travel suggestion. Use this for date questions, holiday planning and travel advice.")
    public String getDateTravelContext(@P("Date in yyyy-MM-dd format, or empty for today") String dateText,
                                       @P("Optional city for travel/weather context, e.g. Beijing or Shanghai") String city) {
        LocalDate date = parseDateOrToday(dateText);
        String normalizedCity = safe(city).trim();
        String weekday = formatWeekday(date.getDayOfWeek());
        String season = seasonOf(date);
        boolean weekend = date.getDayOfWeek() == DayOfWeek.SATURDAY || date.getDayOfWeek() == DayOfWeek.SUNDAY;
        String holidayHint = resolveHolidayHint(date);
        String travelHint = buildTravelHint(date, normalizedCity, holidayHint, weekend);

        if (!normalizedCity.isBlank()) {
            advisorToolContext.recordTrace("getDateTravelContext: " + date + " / " + normalizedCity);
        } else {
            advisorToolContext.recordTrace("getDateTravelContext: " + date);
        }

        return "日期：" + date
                + "；星期：" + weekday
                + "；季节：" + season
                + "；节假日提示：" + holidayHint
                + "；出行建议：" + travelHint;
    }

    @Tool("Get outfit advice based on city weather and scene. Use this for clothing, shoes, commute wear and packing advice.")
    public String getOutfitAdvice(@P("City name for weather context, e.g. Beijing or Shanghai") String city,
                                  @P("Scene such as commute, travel, office, outdoor, campus") String scene,
                                  @P("Optional style preference such as 简约, 通勤, 运动, 防水, 保暖") String stylePreference) {
        String normalizedCity = safe(city).trim();
        String normalizedScene = safe(scene).trim();
        String normalizedStyle = safe(stylePreference).trim();
        if (normalizedCity.isBlank()) {
            advisorToolContext.recordTrace("getOutfitAdvice: empty city");
            return "请先提供城市名称，我才能结合天气给出穿搭建议。";
        }

        try {
            WeatherSnapshot weather = fetchWeather(normalizedCity);
            if (!weather.available()) {
                advisorToolContext.recordTrace("getOutfitAdvice: weather unavailable for " + normalizedCity);
                return "暂时无法获取 " + normalizedCity + " 的天气，无法给出可靠穿搭建议。";
            }

            int temp = parseIntOrDefault(weather.tempC(), 20);
            String sceneLabel = normalizedScene.isBlank() ? "日常" : normalizedScene;
            List<String> layers = recommendLayers(temp, weather.description());
            List<String> shoes = recommendShoes(temp, weather.description(), sceneLabel);
            List<String> keywords = recommendProductKeywords(temp, weather.description(), sceneLabel, normalizedStyle);

            advisorToolContext.recordTrace("getOutfitAdvice: success for " + normalizedCity + " / " + sceneLabel);
            return "城市：" + normalizedCity
                    + "；天气：" + blankToDefault(weather.description(), "未知")
                    + "；气温：" + weather.tempC() + "°C"
                    + "；场景：" + sceneLabel
                    + "；建议穿搭：" + String.join("、", layers)
                    + "；鞋履建议：" + String.join("、", shoes)
                    + (normalizedStyle.isBlank() ? "" : "；风格偏好：" + normalizedStyle)
                    + "；建议检索关键词：" + String.join("、", keywords);
        } catch (Exception error) {
            advisorToolContext.recordTrace("getOutfitAdvice: failed - " + error.getClass().getSimpleName());
            return "暂时无法生成 " + normalizedCity + " 的穿搭建议，请稍后再试。";
        }
    }

    private boolean contains(Product product, String hint) {
        String content = (safe(product.getName()) + " " + safe(product.getDescription()) + " " + safe(product.getTags())).toLowerCase(Locale.ROOT);
        return content.contains(hint);
    }

    private WeatherSnapshot fetchWeather(String city) throws Exception {
        String encodedCity = URLEncoder.encode(city, StandardCharsets.UTF_8);
        String url = "https://wttr.in/" + encodedCity + "?format=j1";
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(8))
                .header("User-Agent", "ecommerce-advisor/1.0")
                .GET()
                .build();

        HttpResponse<String> response = WEATHER_HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            return WeatherSnapshot.unavailable();
        }

        String body = safe(response.body());
        return new WeatherSnapshot(
                extractJsonValue(body, "temp_C"),
                extractJsonValue(body, "FeelsLikeC"),
                extractJsonValue(body, "humidity"),
                extractWeatherDesc(body));
    }

    private LocalDate parseDateOrToday(String dateText) {
        String normalized = safe(dateText).trim();
        if (normalized.isBlank()) {
            return LocalDate.now(ZoneId.systemDefault());
        }
        try {
            return LocalDate.parse(normalized);
        } catch (Exception ignored) {
            return LocalDate.now(ZoneId.systemDefault());
        }
    }

    private String formatWeekday(DayOfWeek dayOfWeek) {
        return switch (dayOfWeek) {
            case MONDAY -> "周一";
            case TUESDAY -> "周二";
            case WEDNESDAY -> "周三";
            case THURSDAY -> "周四";
            case FRIDAY -> "周五";
            case SATURDAY -> "周六";
            case SUNDAY -> "周日";
        };
    }

    private String seasonOf(LocalDate date) {
        int month = date.getMonthValue();
        if (month >= 3 && month <= 5) {
            return "春季";
        }
        if (month >= 6 && month <= 8) {
            return "夏季";
        }
        if (month >= 9 && month <= 11) {
            return "秋季";
        }
        return "冬季";
    }

    private String resolveHolidayHint(LocalDate date) {
        String monthDay = String.format("%02d-%02d", date.getMonthValue(), date.getDayOfMonth());
        return switch (monthDay) {
            case "01-01" -> "元旦假期，出行与商圈人流可能较高";
            case "05-01" -> "劳动节假期，建议提前安排出行与购物";
            case "10-01" -> "国庆假期，热门景点与商圈通常较拥挤";
            case "02-14" -> "情人节消费场景较强，礼品和穿搭需求通常上升";
            case "12-24", "12-25" -> "节日氛围较强，礼品和聚会穿搭需求较高";
            default -> {
                boolean weekend = date.getDayOfWeek() == DayOfWeek.SATURDAY || date.getDayOfWeek() == DayOfWeek.SUNDAY;
                yield weekend ? "周末，适合短途出行和线下逛街" : "普通工作日，通勤和效率优先";
            }
        };
    }

    private String buildTravelHint(LocalDate date, String city, String holidayHint, boolean weekend) {
        String base = weekend ? "建议错峰安排出行，优先轻便与舒适装备" : "建议以通勤效率和便携收纳为先";
        if (!city.isBlank()) {
            return city + "：" + base + "；" + holidayHint;
        }
        return base + "；" + holidayHint;
    }

    private int parseIntOrDefault(String value, int fallback) {
        try {
            return Integer.parseInt(safe(value).trim());
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private List<String> recommendLayers(int temp, String weatherDesc) {
        String normalizedWeather = safe(weatherDesc).toLowerCase(Locale.ROOT);
        if (temp <= 5) {
            return List.of("保暖内搭", "厚外套或羽绒服", "围巾/帽子");
        }
        if (temp <= 12) {
            return List.of("长袖内搭", "轻薄保暖外套", "防风层");
        }
        if (temp <= 20) {
            if (normalizedWeather.contains("rain") || normalizedWeather.contains("雨")) {
                return List.of("长袖上衣", "轻防水外套", "速干下装");
            }
            return List.of("长袖或薄卫衣", "轻便外套", "通勤裤装");
        }
        if (temp <= 28) {
            return List.of("透气短袖", "轻薄外搭", "舒适通勤下装");
        }
        return List.of("速干短袖", "轻薄防晒层", "透气下装");
    }

    private List<String> recommendShoes(int temp, String weatherDesc, String scene) {
        String normalizedWeather = safe(weatherDesc).toLowerCase(Locale.ROOT);
        String normalizedScene = safe(scene).toLowerCase(Locale.ROOT);
        if (normalizedWeather.contains("rain") || normalizedWeather.contains("雨")) {
            return List.of("防滑防泼水运动鞋", "包裹性较好的通勤鞋");
        }
        if (normalizedScene.contains("office") || normalizedScene.contains("办公") || normalizedScene.contains("通勤")) {
            return List.of("轻便通勤鞋", "缓震运动休闲鞋");
        }
        if (normalizedScene.contains("outdoor") || normalizedScene.contains("travel") || normalizedScene.contains("出行")) {
            return List.of("耐走运动鞋", "防滑休闲鞋");
        }
        if (temp <= 8) {
            return List.of("保暖运动鞋", "厚底休闲鞋");
        }
        return List.of("舒适百搭休闲鞋", "轻量运动鞋");
    }

    private List<String> recommendProductKeywords(int temp, String weatherDesc, String scene, String stylePreference) {
        String normalizedWeather = safe(weatherDesc).toLowerCase(Locale.ROOT);
        String normalizedScene = safe(scene).toLowerCase(Locale.ROOT);
        String normalizedStyle = safe(stylePreference).toLowerCase(Locale.ROOT);

        if (normalizedWeather.contains("rain") || normalizedWeather.contains("雨")) {
            if (normalizedScene.contains("通勤") || normalizedScene.contains("commute")) {
                return List.of("防水通勤鞋", "防滑运动鞋", "轻薄防雨外套");
            }
            return List.of("防泼水外套", "防滑鞋", "雨天出行穿搭");
        }
        if (temp <= 12) {
            return List.of("保暖外套", "长袖打底", "防风通勤鞋");
        }
        if (temp >= 28) {
            return List.of("透气短袖", "轻薄鞋", "速干通勤穿搭");
        }
        if (normalizedStyle.contains("运动")) {
            return List.of("运动风穿搭", "缓震跑鞋", "轻量外套");
        }
        return List.of("通勤穿搭", "舒适休闲鞋", "轻便外套");
    }

    private String extractJsonValue(String json, String field) {
        String marker = "\"" + field + "\":\"";
        int start = json.indexOf(marker);
        if (start < 0) {
            return "";
        }
        start += marker.length();
        int end = json.indexOf('"', start);
        if (end < 0) {
            return "";
        }
        return json.substring(start, end);
    }

    private String extractWeatherDesc(String json) {
        String marker = "\"weatherDesc\":[{\"value\":\"";
        int start = json.indexOf(marker);
        if (start < 0) {
            return "";
        }
        start += marker.length();
        int end = json.indexOf('"', start);
        if (end < 0) {
            return "";
        }
        return json.substring(start, end);
    }

    private String blankToDefault(String value, String fallback) {
        String normalized = safe(value).trim();
        return normalized.isBlank() ? fallback : normalized;
    }

    private String formatProducts(List<Product> products, String title) {
        if (products == null || products.isEmpty()) {
            return "No products found for " + title + ".";
        }

        return products.stream()
                .map(product -> "- " + safe(product.getName())
                        + " | price=" + formatPrice(product.getPrice())
                        + " | tags=" + safe(product.getTags())
                        + " | createdAt=" + formatInstant(product.getCreatedAt())
                        + " | description=" + safe(product.getDescription()))
                .collect(Collectors.joining("\n"));
    }

    private String formatPrice(BigDecimal price) {
        return price == null ? "unknown" : price.stripTrailingZeros().toPlainString();
    }

    private String formatInstant(Instant instant) {
        return instant == null ? "unknown" : instant.toString();
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private record WeatherSnapshot(String tempC, String feelsLikeC, String humidity, String description) {
        private static WeatherSnapshot unavailable() {
            return new WeatherSnapshot("", "", "", "");
        }

        private boolean available() {
            return !(tempC == null || tempC.isBlank()) || !(description == null || description.isBlank());
        }
    }
}