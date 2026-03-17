package com.example.ecommerce.config;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.stereotype.Component;

import com.example.ecommerce.model.Order;
import com.example.ecommerce.model.OrderItem;
import com.example.ecommerce.model.Product;
import com.example.ecommerce.model.ProductView;
import com.example.ecommerce.model.User;
import com.example.ecommerce.repository.OrderItemRepository;
import com.example.ecommerce.repository.OrderRepository;
import com.example.ecommerce.repository.ProductRepository;
import com.example.ecommerce.repository.ProductViewRepository;
import com.example.ecommerce.repository.UserRepository;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Component
@ConditionalOnProperty(name = "app.demo.initializer.enabled", havingValue = "true")
public class DemoDataInitializer implements ApplicationRunner {
    private final ProductRepository productRepository;
    private final UserRepository userRepository;
    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final ProductViewRepository productViewRepository;
    private final R2dbcEntityTemplate entityTemplate;

    public DemoDataInitializer(ProductRepository productRepository,
                               UserRepository userRepository,
                               OrderRepository orderRepository,
                               OrderItemRepository orderItemRepository,
                               ProductViewRepository productViewRepository,
                               R2dbcEntityTemplate entityTemplate) {
        this.productRepository = productRepository;
        this.userRepository = userRepository;
        this.orderRepository = orderRepository;
        this.orderItemRepository = orderItemRepository;
        this.productViewRepository = productViewRepository;
        this.entityTemplate = entityTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        seedUsers()
                .flatMap(user -> normalizeExistingProducts()
                        .thenMany(seedProducts())
                        .collectList()
                        .flatMap(products -> seedOrders(user, products)
                                .then(seedProductViews(user, products))))
                .block();
    }

    private Mono<User> seedUsers() {
        return userRepository.findByEmail("demo@aishop.local")
                .switchIfEmpty(entityTemplate.insert(User.class).using(new User(
                        UUID.randomUUID(),
                        "demo@aishop.local",
                        "HASHED:123456",
                        "USER",
                        "AI 导购体验账号",
                        "13800138000",
                        "Shanghai",
                        "偏好数码、效率工具、通勤装备和运动穿戴商品",
                        Instant.now())));
    }

    private Flux<Product> seedProducts() {
        List<Product> demoProducts = List.of(
                new Product(UUID.randomUUID(), "联想小新 Pro 14", "14 英寸轻薄办公笔记本，32GB 内存，1TB 固态，适合通勤与高效办公", new BigDecimal("6299.00"), "https://picsum.photos/seed/laptop/480/320", "笔记本,laptop,办公,office,轻薄,通勤", "SAMPLE", Instant.now()),
                new Product(UUID.randomUUID(), "华硕灵耀 14 Air", "高分辨率 OLED 轻薄本，适合移动办公、内容创作和差旅携带", new BigDecimal("6999.00"), "https://picsum.photos/seed/zenbook/480/320", "笔记本,laptop,轻薄,oled,办公,创作", "SAMPLE", Instant.now()),
                new Product(UUID.randomUUID(), "索尼 WH-1000XM5 降噪耳机", "头戴式无线降噪耳机，适合通勤、地铁和长时间音乐聆听", new BigDecimal("2299.00"), "https://picsum.photos/seed/headphone/480/320", "耳机,headphone,音频,audio,降噪,通勤", "SAMPLE", Instant.now()),
                new Product(UUID.randomUUID(), "华为 FreeBuds Pro 4", "入耳式主动降噪耳机，适合电话会议、地铁通勤和长续航需求", new BigDecimal("1499.00"), "https://picsum.photos/seed/freebuds/480/320", "耳机,headphone,降噪,通勤,会议,蓝牙", "SAMPLE", Instant.now()),
                new Product(UUID.randomUUID(), "Apple Watch Series 9 智能手表", "支持心率、运动和健康监测的智能手表，适合健身与日常提醒", new BigDecimal("2999.00"), "https://picsum.photos/seed/watch/480/320", "手表,watch,wearable,健康,运动", "SAMPLE", Instant.now()),
                new Product(UUID.randomUUID(), "Garmin Forerunner 265", "偏运动训练的智能手表，适合跑步、心率监测和高频训练计划", new BigDecimal("2680.00"), "https://picsum.photos/seed/garmin/480/320", "手表,watch,运动,训练,跑步,健康", "SAMPLE", Instant.now()),
                new Product(UUID.randomUUID(), "任天堂 Switch OLED 游戏机", "7 英寸 OLED 屏的便携游戏主机，适合家庭娱乐和掌机体验", new BigDecimal("2399.00"), "https://picsum.photos/seed/switch/480/320", "游戏机,console,game,娱乐,掌机", "SAMPLE", Instant.now()),
                new Product(UUID.randomUUID(), "小米智能手环 9", "价格友好的运动手环，续航长，适合日常健康和睡眠监测", new BigDecimal("299.00"), "https://picsum.photos/seed/band/480/320", "手环,smart band,wearable,fitness,运动,健康", "SAMPLE", Instant.now()),
                new Product(UUID.randomUUID(), "华为手环 9 NFC 版", "支持睡眠、心率和刷卡能力的轻量运动手环，适合日常佩戴", new BigDecimal("379.00"), "https://picsum.photos/seed/hband/480/320", "手环,band,wearable,健康,运动,NFC", "SAMPLE", Instant.now()),
                new Product(UUID.randomUUID(), "K87 机械键盘", "紧凑布局机械键盘，支持热插拔，适合办公与游戏", new BigDecimal("499.00"), "https://picsum.photos/seed/keyboard/480/320", "键盘,keyboard,机械键盘,office,gaming,办公", "SAMPLE", Instant.now()),
                new Product(UUID.randomUUID(), "Keychron K8 Pro", "支持蓝牙与有线双模的机械键盘，适合 Mac/Windows 混合办公", new BigDecimal("699.00"), "https://picsum.photos/seed/keychron/480/320", "键盘,keyboard,机械键盘,蓝牙,办公,效率", "SAMPLE", Instant.now()),
                new Product(UUID.randomUUID(), "罗技 MX Keys S", "静音输入体验更好的高效办公键盘，适合长时间文档和表格处理", new BigDecimal("799.00"), "https://picsum.photos/seed/mxkeys/480/320", "键盘,keyboard,办公,静音,效率,蓝牙", "SAMPLE", Instant.now())
        );

        return productRepository.findAll()
                .collectMap(Product::getName, Function.identity())
                .flatMapMany(existing -> Flux.fromIterable(demoProducts)
                        .concatMap(product -> existing.containsKey(product.getName())
                                ? Mono.just(existing.get(product.getName()))
                                : entityTemplate.insert(Product.class).using(product)));
    }

    private Mono<Void> seedOrders(User demoUser, List<Product> products) {
        return orderRepository.findByUserId(demoUser.getId())
                .collectList()
                .flatMap(existingOrders -> {
                    if (existingOrders.size() >= 3) {
                        return Mono.empty();
                    }

                    Map<String, Product> productMap = products.stream()
                            .collect(Collectors.toMap(Product::getName, Function.identity(), (left, right) -> left));

                    Order orderOne = new Order(UUID.randomUUID(), demoUser.getId(), "PAID", new BigDecimal("4598.00"), "ALIPAY_SANDBOX", "SANDBOX-DEMO-1001", Instant.now().minus(5, ChronoUnit.DAYS), Instant.now().minus(5, ChronoUnit.DAYS));
                    Order orderTwo = new Order(UUID.randomUUID(), demoUser.getId(), "PAID", new BigDecimal("3378.00"), "ALIPAY_SANDBOX", "SANDBOX-DEMO-1002", Instant.now().minus(3, ChronoUnit.DAYS), Instant.now().minus(3, ChronoUnit.DAYS));
                    Order orderThree = new Order(UUID.randomUUID(), demoUser.getId(), "PAID", new BigDecimal("1198.00"), "ALIPAY_SANDBOX", "SANDBOX-DEMO-1003", Instant.now().minus(1, ChronoUnit.DAYS), Instant.now().minus(1, ChronoUnit.DAYS));

                    List<OrderItem> items = List.of(
                            createOrderItem(orderOne.getId(), requireProduct(productMap, "索尼 WH-1000XM5 降噪耳机"), 1, Instant.now().minus(5, ChronoUnit.DAYS)),
                            createOrderItem(orderOne.getId(), requireProduct(productMap, "小米智能手环 9"), 1, Instant.now().minus(5, ChronoUnit.DAYS)),
                            createOrderItem(orderOne.getId(), requireProduct(productMap, "K87 机械键盘"), 2, Instant.now().minus(5, ChronoUnit.DAYS)),
                            createOrderItem(orderTwo.getId(), requireProduct(productMap, "华为 FreeBuds Pro 4"), 1, Instant.now().minus(3, ChronoUnit.DAYS)),
                            createOrderItem(orderTwo.getId(), requireProduct(productMap, "Apple Watch Series 9 智能手表"), 1, Instant.now().minus(3, ChronoUnit.DAYS)),
                            createOrderItem(orderTwo.getId(), requireProduct(productMap, "华为手环 9 NFC 版"), 1, Instant.now().minus(3, ChronoUnit.DAYS)),
                            createOrderItem(orderThree.getId(), requireProduct(productMap, "Keychron K8 Pro"), 1, Instant.now().minus(1, ChronoUnit.DAYS)),
                            createOrderItem(orderThree.getId(), requireProduct(productMap, "罗技 MX Keys S"), 1, Instant.now().minus(1, ChronoUnit.DAYS)));

                    return Flux.just(orderOne, orderTwo, orderThree)
                            .concatMap(order -> entityTemplate.insert(Order.class).using(order))
                            .thenMany(Flux.fromIterable(items).concatMap(item -> entityTemplate.insert(OrderItem.class).using(item)))
                            .then();
                });
    }

    private Mono<Void> seedProductViews(User demoUser, List<Product> products) {
        return productViewRepository.findRecentByUserId(demoUser.getId())
                .collectList()
                .flatMap(existingViews -> {
                    if (existingViews.size() >= 8) {
                        return Mono.empty();
                    }

                    Map<String, Product> productMap = products.stream()
                            .collect(Collectors.toMap(Product::getName, Function.identity(), (left, right) -> left));

                    List<ProductView> views = List.of(
                            createView(demoUser.getId(), requireProduct(productMap, "索尼 WH-1000XM5 降噪耳机"), "chat-recommendation", "通勤降噪场景高匹配", 18),
                            createView(demoUser.getId(), requireProduct(productMap, "华为 FreeBuds Pro 4"), "chat-recommendation", "适合会议与地铁通勤", 16),
                            createView(demoUser.getId(), requireProduct(productMap, "联想小新 Pro 14"), "featured", "高效办公轻薄本", 12),
                            createView(demoUser.getId(), requireProduct(productMap, "华硕灵耀 14 Air"), "featured", "偏创作与差旅办公", 10),
                            createView(demoUser.getId(), requireProduct(productMap, "Apple Watch Series 9 智能手表"), "chat-recommendation", "健康提醒与运动结合", 8),
                            createView(demoUser.getId(), requireProduct(productMap, "Garmin Forerunner 265"), "chat-recommendation", "适合高频训练人群", 6),
                            createView(demoUser.getId(), requireProduct(productMap, "Keychron K8 Pro"), "featured", "办公与多设备切换", 4),
                            createView(demoUser.getId(), requireProduct(productMap, "小米智能手环 9"), "account-recommendation", "入门价位更友好", 2));

                    return Flux.fromIterable(views)
                            .concatMap(view -> entityTemplate.insert(ProductView.class).using(view))
                            .then();
                });
    }

    private OrderItem createOrderItem(UUID orderId, Product product, int quantity, Instant createdAt) {
        return new OrderItem(
                UUID.randomUUID(),
                orderId,
                product.getId(),
                product.getName(),
                product.getDescription(),
                product.getImageUrl(),
                product.getPrice(),
                quantity,
                createdAt);
    }

    private ProductView createView(UUID userId, Product product, String source, String reason, int hoursAgo) {
        return new ProductView(
                UUID.randomUUID(),
                userId,
                product.getId(),
                source,
                reason,
                Instant.now().minus(hoursAgo, ChronoUnit.HOURS));
    }

    private Product requireProduct(Map<String, Product> products, String name) {
        Product product = products.get(name);
        if (product == null) {
            throw new IllegalStateException("Missing demo product: " + name);
        }
        return product;
    }

    private Mono<Void> normalizeExistingProducts() {
        return productRepository.findAll()
                .flatMap(product -> {
                    boolean updated = false;

                    if ("Lenovo Xiaoxin Pro 14".equals(product.getName())) {
                        product.setName("联想小新 Pro 14");
                        product.setDescription("14 英寸轻薄办公笔记本，32GB 内存，1TB 固态，适合通勤与高效办公");
                        product.setTags("笔记本,laptop,办公,office,轻薄,通勤");
                        updated = true;
                    } else if ("Sony WH-1000XM5".equals(product.getName())) {
                        product.setName("索尼 WH-1000XM5 降噪耳机");
                        product.setDescription("头戴式无线降噪耳机，适合通勤、地铁和长时间音乐聆听");
                        product.setTags("耳机,headphone,音频,audio,降噪,通勤");
                        updated = true;
                    } else if ("Apple Watch Series 9".equals(product.getName())) {
                        product.setName("Apple Watch Series 9 智能手表");
                        product.setDescription("支持心率、运动和健康监测的智能手表，适合健身与日常提醒");
                        product.setTags("手表,watch,wearable,健康,运动");
                        updated = true;
                    } else if ("Nintendo Switch OLED".equals(product.getName())) {
                        product.setName("任天堂 Switch OLED 游戏机");
                        product.setDescription("7 英寸 OLED 屏的便携游戏主机，适合家庭娱乐和掌机体验");
                        product.setTags("游戏机,console,game,娱乐,掌机");
                        updated = true;
                    } else if ("Xiaomi Smart Band 9".equals(product.getName())) {
                        product.setName("小米智能手环 9");
                        product.setDescription("价格友好的运动手环，续航长，适合日常健康和睡眠监测");
                        product.setTags("手环,smart band,wearable,fitness,运动,健康");
                        updated = true;
                    } else if ("Mechanical Keyboard K87".equals(product.getName())) {
                        product.setName("K87 机械键盘");
                        product.setDescription("紧凑布局机械键盘，支持热插拔，适合办公与游戏");
                        product.setTags("键盘,keyboard,机械键盘,office,gaming,办公");
                        updated = true;
                    }

                    return updated ? productRepository.save(product) : Mono.just(product);
                })
                .then();
    }
}
