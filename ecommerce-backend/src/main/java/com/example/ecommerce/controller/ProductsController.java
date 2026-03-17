package com.example.ecommerce.controller;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.springframework.validation.annotation.Validated;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.ecommerce.dto.CreateProductRequest;
import com.example.ecommerce.dto.ChatRecommendationResponse;
import com.example.ecommerce.model.Product;
import com.example.ecommerce.repository.ProductRepository;
import com.example.ecommerce.service.ProductRecommendationService;
import com.example.ecommerce.service.ProductSearchService;
import com.example.ecommerce.service.ProductVectorStoreService;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/products")
public class ProductsController {
    private final ProductRepository productRepository;
    private final R2dbcEntityTemplate entityTemplate;
    private final ProductSearchService productSearchService;
    private final ProductRecommendationService productRecommendationService;
    private final ProductVectorStoreService productVectorStoreService;

    public ProductsController(ProductRepository productRepository,
                              R2dbcEntityTemplate entityTemplate,
                              ProductSearchService productSearchService,
                              ProductRecommendationService productRecommendationService,
                              ProductVectorStoreService productVectorStoreService) {
        this.productRepository = productRepository;
        this.entityTemplate = entityTemplate;
        this.productSearchService = productSearchService;
        this.productRecommendationService = productRecommendationService;
        this.productVectorStoreService = productVectorStoreService;
    }

    @PostMapping
    public Mono<Product> create(@Validated @RequestBody CreateProductRequest request) {
        String dataSource = request.dataSource() == null || request.dataSource().isBlank() ? "MANUAL" : request.dataSource().trim().toUpperCase();
        Product product = new Product(UUID.randomUUID(), request.name(), request.description(), request.price(), request.imageUrl(), request.tags(), dataSource, Instant.now());
        return entityTemplate.insert(Product.class).using(product)
                .flatMap(productVectorStoreService::syncProduct);
    }

    @GetMapping
    public Flux<Product> list(@RequestParam(name = "q", required = false) String q) {
        if (q == null || q.isBlank()) {
            return productRepository.findAll();
        }
        return productSearchService.search(q);
    }

    @GetMapping("/health")
    public Mono<Map<String, Object>> health() {
        return Mono.just(Map.of(
                "service", "product-service",
                "status", "UP",
                "resource", "products",
                "timestamp", Instant.now().toString()));
    }

    @GetMapping("/{id}")
    public Mono<Product> get(@PathVariable("id") UUID id) {
        return productRepository.findById(id);
    }

    @GetMapping("/{id}/related")
    public Mono<java.util.List<ChatRecommendationResponse>> related(@PathVariable("id") UUID id,
                                                                    @RequestParam(name = "limit", defaultValue = "4") int limit) {
        return productRecommendationService.recommendForProduct(id, Math.max(1, Math.min(limit, 8)));
    }

    @PostMapping("/vector/refresh")
    public Mono<ProductVectorStoreService.CatalogStats> refreshVectorCatalog() {
        return productVectorStoreService.refreshCatalog();
    }

    @GetMapping("/vector/stats")
    public Mono<ProductVectorStoreService.CatalogStats> vectorCatalogStats() {
        return productVectorStoreService.getCatalogStats();
    }
}
