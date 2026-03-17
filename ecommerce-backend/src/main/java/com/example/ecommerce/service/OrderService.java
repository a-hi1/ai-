package com.example.ecommerce.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import com.example.ecommerce.dto.CreateOrderRequest;
import com.example.ecommerce.dto.OrderDetailResponse;
import com.example.ecommerce.dto.OrderItemResponse;
import com.example.ecommerce.dto.OrderPreviewItemResponse;
import com.example.ecommerce.dto.OrderSummaryResponse;
import com.example.ecommerce.model.Order;
import com.example.ecommerce.model.OrderItem;
import com.example.ecommerce.repository.OrderItemRepository;
import com.example.ecommerce.repository.OrderRepository;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Service
public class OrderService {
    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final CartService cartService;
        private final PaymentService paymentService;
    private final R2dbcEntityTemplate entityTemplate;

        public OrderService(OrderRepository orderRepository, OrderItemRepository orderItemRepository, CartService cartService,
                        PaymentService paymentService,
            R2dbcEntityTemplate entityTemplate) {
        this.orderRepository = orderRepository;
        this.orderItemRepository = orderItemRepository;
        this.cartService = cartService;
                this.paymentService = paymentService;
        this.entityTemplate = entityTemplate;
    }

    public Mono<OrderDetailResponse> create(CreateOrderRequest request) {
        return cartService.listSnapshot(request.userId())
                .flatMap(items -> {
                    if (items.isEmpty()) {
                        return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cart is empty"));
                    }

                    BigDecimal totalAmount = items.stream()
                            .map(item -> item.lineTotal() == null ? BigDecimal.ZERO : item.lineTotal())
                            .reduce(BigDecimal.ZERO, BigDecimal::add);

                    Order order = new Order(
                            UUID.randomUUID(),
                            request.userId(),
                            "CREATED",
                            totalAmount,
                            "ALIPAY",
                            null,
                            null,
                            Instant.now());

                    return entityTemplate.insert(Order.class).using(order)
                            .flatMap(savedOrder -> saveOrderItems(savedOrder.getId(), items)
                                    .then(cartService.clearUserCart(request.userId()))
                                    .thenReturn(toDetailFromCart(savedOrder, items)));
                });
    }

    public Flux<OrderSummaryResponse> listByUser(UUID userId) {
        return orderRepository.findByUserId(userId)
                .flatMap(this::refreshOrderIfPending)
                .flatMap(order -> orderItemRepository.findByOrderId(order.getId())
                        .collectList()
                        .map(items -> new OrderSummaryResponse(
                                order.getId(),
                                order.getUserId(),
                                order.getStatus(),
                                order.getTotalAmount(),
                                order.getPaymentMethod(),
                                order.getGatewayTradeNo(),
                                order.getPaidAt(),
                                order.getCreatedAt(),
                                items.stream().mapToInt(OrderItem::getQuantity).sum(),
                                items.stream()
                                        .limit(3)
                                        .map(this::toOrderPreviewItemResponse)
                                        .toList())));
    }

    public Mono<OrderDetailResponse> get(UUID orderId) {
        return orderRepository.findById(orderId)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found")))
                                .flatMap(this::refreshOrderIfPending)
                .flatMap(order -> orderItemRepository.findByOrderId(order.getId())
                        .map(this::toOrderItemResponse)
                        .collectList()
                        .map(items -> toDetail(order, items)));
    }

        private Mono<Order> refreshOrderIfPending(Order order) {
                if (!"CREATED".equalsIgnoreCase(order.getStatus()) || order.getGatewayTradeNo() == null || order.getGatewayTradeNo().isBlank()) {
                        return Mono.just(order);
                }

                return paymentService.queryAndSyncOrderStatus(order)
                                .flatMap(status -> orderRepository.findById(order.getId()).defaultIfEmpty(order))
                                .onErrorResume(error -> Mono.just(order));
        }

    private Mono<Void> saveOrderItems(UUID orderId, List<com.example.ecommerce.dto.CartItemResponse> items) {
        return Flux.fromIterable(items)
                .flatMap(item -> entityTemplate.insert(OrderItem.class).using(new OrderItem(
                        UUID.randomUUID(),
                        orderId,
                        item.productId(),
                        item.productName(),
                        item.productDescription(),
                        item.imageUrl(),
                        item.unitPrice(),
                        item.quantity(),
                        Instant.now())))
                .then();
    }

    private OrderItemResponse toOrderItemResponse(OrderItem orderItem) {
        return new OrderItemResponse(
                orderItem.getId(),
                orderItem.getProductId(),
                orderItem.getProductName(),
                orderItem.getProductDescription(),
                orderItem.getImageUrl(),
                orderItem.getUnitPrice(),
                orderItem.getQuantity(),
                orderItem.getUnitPrice().multiply(BigDecimal.valueOf(orderItem.getQuantity())));
    }

        private OrderPreviewItemResponse toOrderPreviewItemResponse(OrderItem orderItem) {
                return new OrderPreviewItemResponse(
                                orderItem.getProductId(),
                                orderItem.getProductName(),
                                orderItem.getImageUrl(),
                                orderItem.getQuantity());
        }

        private OrderDetailResponse toDetailFromCart(Order order, List<com.example.ecommerce.dto.CartItemResponse> items) {
        List<OrderItemResponse> detailItems = items.stream()
                .map(item -> new OrderItemResponse(
                        item.cartItemId(),
                        item.productId(),
                        item.productName(),
                        item.productDescription(),
                        item.imageUrl(),
                        item.unitPrice(),
                        item.quantity(),
                        item.lineTotal()))
                .toList();
        return toDetail(order, detailItems);
    }

    private OrderDetailResponse toDetail(Order order, List<OrderItemResponse> items) {
        return new OrderDetailResponse(
                order.getId(),
                order.getUserId(),
                order.getStatus(),
                order.getTotalAmount(),
                order.getPaymentMethod(),
                order.getGatewayTradeNo(),
                order.getPaidAt(),
                order.getCreatedAt(),
                items);
    }
}