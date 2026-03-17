package com.example.ecommerce.controller;

import java.util.Map;
import java.util.UUID;

import org.springframework.validation.annotation.Validated;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.example.ecommerce.dto.CreatePaymentRequest;
import com.example.ecommerce.dto.PaymentSessionResponse;
import com.example.ecommerce.dto.PaymentNotifyRequest;
import com.example.ecommerce.repository.OrderRepository;
import com.example.ecommerce.service.PaymentService;

import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/payments")
public class PaymentController {
    private final OrderRepository orderRepository;
    private final PaymentService paymentService;

    public PaymentController(OrderRepository orderRepository, PaymentService paymentService) {
        this.orderRepository = orderRepository;
        this.paymentService = paymentService;
    }

    @PostMapping("/alipay/create")
    public Mono<PaymentSessionResponse> create(@Validated @RequestBody CreatePaymentRequest request) {
        return orderRepository.findById(request.orderId())
            .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND")))
            .flatMap(order -> paymentService.createPaymentSession(order, request.preferredMode()))
            .onErrorMap(IllegalStateException.class,
                ex -> new ResponseStatusException(HttpStatus.CONFLICT, ex.getMessage(), ex));
    }

    @PostMapping("/alipay/notify")
    public Mono<String> notify(@Validated @RequestBody PaymentNotifyRequest request) {
        return orderRepository.findById(request.orderId())
                .flatMap(order -> paymentService.updateOrderStatus(order, request.status(), request.gatewayTradeNo()))
                .map(order -> "OK")
                .defaultIfEmpty("ORDER_NOT_FOUND");
    }

    @PostMapping("/alipay/return/verify")
    public Mono<String> verifyReturn(@RequestBody Map<String, String> payload) {
        if (!paymentService.verifyAlipaySignature(payload)) {
            return Mono.just("INVALID_SIGNATURE");
        }

        String orderId = payload.get("out_trade_no");
        if (orderId == null || orderId.isBlank()) {
            return Mono.just("MISSING_ORDER_ID");
        }

        UUID parsedOrderId;
        try {
            parsedOrderId = UUID.fromString(orderId);
        } catch (IllegalArgumentException ex) {
            return Mono.just("INVALID_ORDER_ID");
        }

        String tradeStatus = payload.getOrDefault("trade_status", "TRADE_SUCCESS");
        String gatewayTradeNo = payload.get("trade_no");

        return orderRepository.findById(parsedOrderId)
                .flatMap(order -> paymentService.updateOrderStatus(order, paymentService.mapTradeStatus(tradeStatus), gatewayTradeNo))
                .map(order -> "OK")
                .defaultIfEmpty("ORDER_NOT_FOUND");
    }

    @GetMapping("/alipay/query/{orderId}")
    public Mono<String> query(@PathVariable("orderId") UUID orderId) {
        return orderRepository.findById(orderId)
                .flatMap(paymentService::queryAndSyncOrderStatus)
                .defaultIfEmpty("ORDER_NOT_FOUND");
    }
}
