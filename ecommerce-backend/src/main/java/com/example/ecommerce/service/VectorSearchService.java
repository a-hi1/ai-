package com.example.ecommerce.service;

import org.springframework.stereotype.Service;

import com.example.ecommerce.model.Product;

import reactor.core.publisher.Flux;

@Service
public class VectorSearchService {
    private final ProductSearchService productSearchService;
    private final ProductVectorStoreService productVectorStoreService;

    public VectorSearchService(ProductSearchService productSearchService, ProductVectorStoreService productVectorStoreService) {
        this.productSearchService = productSearchService;
        this.productVectorStoreService = productVectorStoreService;
    }

    public Flux<Product> semanticSearch(String query) {
        return productVectorStoreService.search(query, 8)
                .switchIfEmpty(productSearchService.search(query));
    }
}
