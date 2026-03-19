package com.example.ecommerce.service;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.net.URLEncoder;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.example.ecommerce.dto.PaymentSessionResponse;
import com.example.ecommerce.model.Order;

import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@Service
public class PaymentService {
    private final R2dbcEntityTemplate entityTemplate;
    private final String frontendBaseUrl;
    private final String alipayMode;
    private final String alipayGateway;
    private final String alipayAppId;
    private final String alipayPrivateKey;
    private final String alipayPublicKey;
    private final boolean signEnabled;
    private final String alipayNotifyUrl;
    private final String alipayReturnUrl;
        private final ObjectMapper objectMapper;
        private final HttpClient httpClient;

    public PaymentService(
            R2dbcEntityTemplate entityTemplate,
            ObjectMapper objectMapper,
            @Value("${payment.frontend-base-url}") String frontendBaseUrl,
            @Value("${payment.alipay.mode}") String alipayMode,
            @Value("${payment.alipay.gateway}") String alipayGateway,
            @Value("${payment.alipay.app-id:}") String alipayAppId,
            @Value("${payment.alipay.private-key:}") String alipayPrivateKey,
            @Value("${payment.alipay.public-key:}") String alipayPublicKey,
            @Value("${payment.alipay.sign-enabled:false}") boolean signEnabled,
            @Value("${payment.alipay.notify-url:}") String alipayNotifyUrl,
            @Value("${payment.alipay.return-url:http://127.0.0.1:5173/payment/callback}") String alipayReturnUrl) {
        this.entityTemplate = entityTemplate;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newHttpClient();
        this.frontendBaseUrl = frontendBaseUrl;
        this.alipayMode = alipayMode;
        this.alipayGateway = alipayGateway;
        this.alipayAppId = alipayAppId;
        this.alipayPrivateKey = alipayPrivateKey;
        this.alipayPublicKey = alipayPublicKey;
        this.signEnabled = signEnabled;
        this.alipayNotifyUrl = alipayNotifyUrl;
        this.alipayReturnUrl = alipayReturnUrl;
    }

    public Mono<Order> updateOrderStatus(Order order, String status, String gatewayTradeNo) {
        order.setStatus(status);
        if (gatewayTradeNo != null && !gatewayTradeNo.isBlank()) {
            order.setGatewayTradeNo(gatewayTradeNo);
        }
        if ("PAID".equalsIgnoreCase(status)) {
            order.setPaidAt(Instant.now());
        }
        order.setCreatedAt(order.getCreatedAt() == null ? Instant.now() : order.getCreatedAt());
        return entityTemplate.getDatabaseClient()
            .sql("UPDATE orders SET status = :status, payment_method = :paymentMethod, gateway_trade_no = :gatewayTradeNo, paid_at = :paidAt WHERE id = :id")
            .bind("status", order.getStatus())
            .bind("paymentMethod", order.getPaymentMethod() == null ? "ALIPAY" : order.getPaymentMethod())
            .bind("gatewayTradeNo", order.getGatewayTradeNo())
            .bind("paidAt", order.getPaidAt())
            .bind("id", order.getId())
            .fetch()
            .rowsUpdated()
            .thenReturn(order);
    }

    public Mono<PaymentSessionResponse> createPaymentSession(Order order, String preferredMode) {
        if (!"CREATED".equalsIgnoreCase(order.getStatus())) {
            return Mono.error(new IllegalStateException("仅待支付订单允许发起支付"));
        }

        String normalizedPreferredMode = normalizePreferredMode(preferredMode);
        boolean forceSandbox = "alipay".equals(normalizedPreferredMode);
        boolean forceDemo = "demo".equals(normalizedPreferredMode);
        boolean alreadyInitialized = order.getGatewayTradeNo() != null && !order.getGatewayTradeNo().isBlank();

        String gatewayTradeNo = alreadyInitialized ? order.getGatewayTradeNo() : "ALIPAY-" + order.getId();
        boolean useSandbox = forceSandbox || (!forceDemo && shouldUseConfiguredSandbox());

        if (forceSandbox && !hasSandboxConfig()) {
            return Mono.error(new IllegalStateException("支付宝沙箱未配置完成，请先补全 appId、私钥和公钥配置"));
        }

        order.setPaymentMethod(useSandbox ? "ALIPAY_SANDBOX" : "ALIPAY_DEMO");
        order.setGatewayTradeNo(gatewayTradeNo);
        order.setCreatedAt(order.getCreatedAt() == null ? Instant.now() : order.getCreatedAt());

        Mono<Order> persisted = alreadyInitialized ? Mono.just(order) : entityTemplate.getDatabaseClient()
            .sql("UPDATE orders SET payment_method = :paymentMethod, gateway_trade_no = :gatewayTradeNo, created_at = :createdAt WHERE id = :id")
            .bind("paymentMethod", order.getPaymentMethod())
            .bind("gatewayTradeNo", order.getGatewayTradeNo())
            .bind("createdAt", order.getCreatedAt())
            .bind("id", order.getId())
            .fetch()
            .rowsUpdated()
            .thenReturn(order);

        if (useSandbox) {
            return persisted.map(savedOrder -> new PaymentSessionResponse(
                    savedOrder.getId(),
                    buildAlipayGatewayUrl(savedOrder, gatewayTradeNo),
                    "ALIPAY",
                    "alipay",
                    gatewayTradeNo));
        }

        return persisted.map(savedOrder -> buildDemoSession(savedOrder, gatewayTradeNo));
    }

    private String buildAlipayGatewayUrl(Order order, String gatewayTradeNo) {
        String timestamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                .withZone(ZoneId.of("Asia/Shanghai"))
                .format(Instant.now());

        String bizContent = "{"
                + "\"out_trade_no\":\"" + order.getId() + "\"," 
                + "\"product_code\":\"FAST_INSTANT_TRADE_PAY\"," 
                + "\"total_amount\":\"" + order.getTotalAmount() + "\"," 
                + "\"subject\":\"AI Shop Order " + order.getId() + "\"," 
                + "\"body\":\"AI Shop checkout\"}";

        List<String[]> params = new ArrayList<>();
        params.add(new String[] { "app_id", alipayAppId });
        params.add(new String[] { "biz_content", bizContent });
        params.add(new String[] { "charset", "UTF-8" });
        params.add(new String[] { "format", "JSON" });
        params.add(new String[] { "method", "alipay.trade.page.pay" });
        addParamIfPresent(params, "notify_url", alipayNotifyUrl);
        params.add(new String[] { "passback_params", gatewayTradeNo });
        addParamIfPresent(params, "return_url", alipayReturnUrl);
        params.add(new String[] { "sign_type", "RSA2" });
        params.add(new String[] { "timestamp", timestamp });
        params.add(new String[] { "version", "1.0" });

        String unsigned = params.stream()
                .sorted(Comparator.comparing(entry -> entry[0]))
                .map(entry -> entry[0] + "=" + entry[1])
                .reduce((left, right) -> left + "&" + right)
                .orElse("");

        StringBuilder url = new StringBuilder(alipayGateway).append("?");
        for (int i = 0; i < params.size(); i++) {
            String[] entry = params.get(i);
            if (i > 0) {
                url.append('&');
            }
            url.append(entry[0]).append('=').append(encode(entry[1]));
        }

        String signature = sign(unsigned);
        if (!signature.isBlank()) {
            url.append("&sign=").append(encode(signature));
        }
        return url.toString();
    }

    public boolean verifyAlipaySignature(Map<String, String> params) {
        if (alipayPublicKey == null || alipayPublicKey.isBlank()) {
            return false;
        }

        String sign = params.get("sign");
        if (sign == null || sign.isBlank()) {
            return false;
        }

        try {
            String content = params.entrySet().stream()
                    .filter(entry -> entry.getKey() != null && !entry.getKey().isBlank())
                    .filter(entry -> entry.getValue() != null && !entry.getValue().isBlank())
                    .filter(entry -> !Set.of("sign", "sign_type").contains(entry.getKey()))
                    .sorted(Map.Entry.comparingByKey())
                    .map(entry -> entry.getKey() + "=" + entry.getValue())
                    .reduce((left, right) -> left + "&" + right)
                    .orElse("");

            PublicKey publicKey = KeyFactory.getInstance("RSA")
                    .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(normalizePublicKey(alipayPublicKey))));
            Signature verifier = Signature.getInstance("SHA256withRSA");
            verifier.initVerify(publicKey);
            verifier.update(content.getBytes(StandardCharsets.UTF_8));
            return verifier.verify(Base64.getDecoder().decode(sign));
        } catch (Exception ex) {
            return false;
        }
    }

    public String mapTradeStatus(String tradeStatus) {
        if ("TRADE_SUCCESS".equalsIgnoreCase(tradeStatus) || "TRADE_FINISHED".equalsIgnoreCase(tradeStatus)) {
            return "PAID";
        }
        if ("WAIT_BUYER_PAY".equalsIgnoreCase(tradeStatus)) {
            return "CREATED";
        }
        return "CANCELLED";
    }

    public Mono<String> queryAndSyncOrderStatus(Order order) {
        if (!"CREATED".equalsIgnoreCase(order.getStatus()) || order.getGatewayTradeNo() == null || order.getGatewayTradeNo().isBlank()) {
            return Mono.just(order.getStatus());
        }
        if (!shouldQuerySandboxStatus(order)) {
            return Mono.just(order.getStatus());
        }

        return Mono.fromCallable(() -> queryAlipayTrade(order))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(result -> {
                    if (result == null || result.tradeStatus() == null || result.tradeStatus().isBlank()) {
                        return Mono.just(order.getStatus());
                    }

                    String mappedStatus = mapTradeStatus(result.tradeStatus());
                    if (mappedStatus.equalsIgnoreCase(order.getStatus())
                            && ((result.tradeNo() == null && order.getGatewayTradeNo() == null)
                            || (result.tradeNo() != null && result.tradeNo().equals(order.getGatewayTradeNo())))) {
                        return Mono.just(order.getStatus());
                    }

                    return updateOrderStatus(order, mappedStatus, result.tradeNo()).map(Order::getStatus);
                })
                .onErrorResume(error -> Mono.just(order.getStatus()));
    }

    private PaymentSessionResponse buildDemoSession(Order order, String gatewayTradeNo) {
        String demoUrl = frontendBaseUrl + "/payment/callback?orderId=" + encode(order.getId().toString())
                + "&status=PAID&tradeNo=" + encode(gatewayTradeNo) + "&provider=ALIPAY_DEMO";
        return new PaymentSessionResponse(order.getId(), demoUrl, "ALIPAY", "demo", gatewayTradeNo);
    }

    private boolean hasSandboxConfig() {
        return alipayAppId != null && !alipayAppId.isBlank()
                && alipayPrivateKey != null && !alipayPrivateKey.isBlank()
                && alipayPublicKey != null && !alipayPublicKey.isBlank();
    }

    private boolean shouldUseConfiguredSandbox() {
        return ("alipay".equalsIgnoreCase(alipayMode) || signEnabled) && hasSandboxConfig();
    }

    private boolean shouldQuerySandboxStatus(Order order) {
        return "ALIPAY_SANDBOX".equalsIgnoreCase(order.getPaymentMethod()) && hasSandboxConfig();
    }

    private String normalizePreferredMode(String preferredMode) {
        if (preferredMode == null) {
            return "";
        }
        return preferredMode.trim().toLowerCase();
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private AlipayTradeQueryResult queryAlipayTrade(Order order) throws IOException, InterruptedException {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("app_id", alipayAppId);
        params.put("biz_content", "{\"out_trade_no\":\"" + order.getId() + "\"}");
        params.put("charset", "UTF-8");
        params.put("format", "JSON");
        params.put("method", "alipay.trade.query");
        params.put("sign_type", "RSA2");
        params.put("timestamp", DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                .withZone(ZoneId.of("Asia/Shanghai"))
                .format(Instant.now()));
        params.put("version", "1.0");

        String unsigned = params.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .reduce((left, right) -> left + "&" + right)
                .orElse("");

        StringBuilder url = new StringBuilder(alipayGateway).append("?");
        int index = 0;
        for (Map.Entry<String, String> entry : params.entrySet()) {
            if (index++ > 0) {
                url.append('&');
            }
            url.append(entry.getKey()).append('=').append(encode(entry.getValue()));
        }

        String signature = sign(unsigned);
        if (!signature.isBlank()) {
            url.append("&sign=").append(encode(signature));
        }

        HttpRequest request = HttpRequest.newBuilder(URI.create(url.toString())).GET().build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        JsonNode root = objectMapper.readTree(response.body());
        JsonNode tradeResponse = root.path("alipay_trade_query_response");
        if (!"10000".equals(tradeResponse.path("code").asText())) {
            return null;
        }

        String tradeStatus = tradeResponse.path("trade_status").asText("");
        String tradeNo = tradeResponse.path("trade_no").asText("");
        BigDecimal totalAmount = tradeResponse.hasNonNull("total_amount")
                ? new BigDecimal(tradeResponse.path("total_amount").asText("0"))
                : null;
        return new AlipayTradeQueryResult(tradeStatus, tradeNo, totalAmount);
    }

    private void addParamIfPresent(List<String[]> params, String key, String value) {
        if (value != null && !value.isBlank()) {
            params.add(new String[] { key, value });
        }
    }

    private String sign(String content) {
        try {
            PrivateKey privateKey = KeyFactory.getInstance("RSA")
                    .generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(normalizeKey(alipayPrivateKey))));
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initSign(privateKey);
            signature.update(content.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(signature.sign());
        } catch (Exception ex) {
            return "";
        }
    }

    private String normalizeKey(String key) {
        return key.replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replace("\r", "")
                .replace("\n", "")
                .trim();
    }

    private String normalizePublicKey(String key) {
        return key.replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replace("\r", "")
                .replace("\n", "")
                .trim();
    }

    private record AlipayTradeQueryResult(String tradeStatus, String tradeNo, BigDecimal totalAmount) {
    }
}
