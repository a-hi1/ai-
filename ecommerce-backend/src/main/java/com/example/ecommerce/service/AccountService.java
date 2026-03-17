package com.example.ecommerce.service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.stereotype.Service;

import com.example.ecommerce.dto.AccountOverviewResponse;
import com.example.ecommerce.dto.AuthUserResponse;
import com.example.ecommerce.dto.ProductInsightResponse;
import com.example.ecommerce.model.Product;
import com.example.ecommerce.model.ProductView;
import com.example.ecommerce.model.User;
import com.example.ecommerce.repository.ChatMessageRepository;
import com.example.ecommerce.repository.OrderRepository;
import com.example.ecommerce.repository.ProductRepository;
import com.example.ecommerce.repository.ProductViewRepository;
import com.example.ecommerce.repository.UserRepository;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Service
public class AccountService {
    private final UserRepository userRepository;
    private final ProductRepository productRepository;
    private final ProductViewRepository productViewRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final OrderRepository orderRepository;
    private final R2dbcEntityTemplate entityTemplate;

    public AccountService(
            UserRepository userRepository,
            ProductRepository productRepository,
            ProductViewRepository productViewRepository,
            ChatMessageRepository chatMessageRepository,
            OrderRepository orderRepository,
            R2dbcEntityTemplate entityTemplate) {
        this.userRepository = userRepository;
        this.productRepository = productRepository;
        this.productViewRepository = productViewRepository;
        this.chatMessageRepository = chatMessageRepository;
        this.orderRepository = orderRepository;
        this.entityTemplate = entityTemplate;
    }

    public Mono<ProductView> recordView(UUID userId, UUID productId, String source, String reason) {
        ProductView view = new ProductView(UUID.randomUUID(), userId, productId, source, reason, Instant.now());
        return entityTemplate.insert(ProductView.class).using(view);
    }

    public Mono<AccountOverviewResponse> getOverview(UUID userId) {
        Mono<AuthUserResponse> profileMono = userRepository.findById(userId).map(this::toAuthResponse);
        Mono<List<ProductInsightResponse>> recentViewsMono = productViewRepository.findRecentByUserId(userId)
                .flatMap(this::toInsight)
                .collectList();

        Mono<List<ProductInsightResponse>> recommendationsMono = Mono.zip(
                        chatMessageRepository.findByUserId(userId).collectList(),
                        orderRepository.findByUserId(userId).collectList(),
                        productRepository.findAll().collectList())
                .map(tuple -> {
                    String chatKeywords = tuple.getT1().stream()
                            .map(message -> message.getContent().toLowerCase())
                            .reduce("", (left, right) -> left + " " + right);
                    boolean hasRecentOrder = !tuple.getT2().isEmpty();

                    return tuple.getT3().stream()
                            .limit(4)
                            .map(product -> new ProductInsightResponse(
                                    product.getId(),
                                    product.getName(),
                                    product.getDescription(),
                                    product.getImageUrl(),
                                    product.getPrice(),
                                    buildReason(product, chatKeywords, hasRecentOrder),
                                    hasRecentOrder ? "order-history" : "chat-intent",
                                    Instant.now()))
                            .toList();
                });

        return Mono.zip(profileMono, recentViewsMono, recommendationsMono)
                .map(tuple -> new AccountOverviewResponse(tuple.getT1(), tuple.getT2(), tuple.getT3()));
    }

    private Mono<ProductInsightResponse> toInsight(ProductView view) {
        return productRepository.findById(view.getProductId())
                .map(product -> new ProductInsightResponse(
                        product.getId(),
                        product.getName(),
                        product.getDescription(),
                        product.getImageUrl(),
                        product.getPrice(),
                        view.getReason(),
                        view.getSource(),
                        view.getCreatedAt()));
    }

    private String buildReason(Product product, String chatKeywords, boolean hasRecentOrder) {
        String base = product.getTags() == null ? "" : product.getTags().toLowerCase();
        if (!chatKeywords.isBlank() && chatKeywords.contains("降噪") && base.contains("headphone")) {
            return "你最近多次提到通勤和降噪，这款商品与当前聊天意图高度匹配。";
        }
        if (!chatKeywords.isBlank() && chatKeywords.contains("办公") && base.contains("office")) {
            return "你近期偏好办公场景商品，这件商品在轻办公和桌面效率上更契合。";
        }
        if (hasRecentOrder) {
            return "基于你最近的下单品类，系统补充推荐相邻价格带和同场景商品。";
        }
        return "基于你的浏览和聊天关键词，系统推荐这件高相关商品。";
    }

    private AuthUserResponse toAuthResponse(User user) {
        return new AuthUserResponse(user.getId(), user.getEmail(), user.getRole(), user.getDisplayName(), user.getPhone(), user.getCity(), user.getBio());
    }
}