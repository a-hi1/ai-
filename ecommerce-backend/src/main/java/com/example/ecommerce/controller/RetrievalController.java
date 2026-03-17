package com.example.ecommerce.controller;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.ecommerce.service.RetrievalGatewayService;
import com.example.ecommerce.service.RetrievalGatewayService.RetrievalChunk;

import reactor.core.publisher.Flux;

@RestController
@RequestMapping("/api/retrieval")
public class RetrievalController {
    private final RetrievalGatewayService retrievalGatewayService;

    public RetrievalController(RetrievalGatewayService retrievalGatewayService) {
        this.retrievalGatewayService = retrievalGatewayService;
    }

    @GetMapping("/search")
    public Flux<RetrievalSearchResponse> search(@RequestParam("q") String query,
                                                @RequestParam(name = "limit", defaultValue = "6") int limit,
                                                @RequestParam(name = "category", required = false) String category,
                                                @RequestParam(name = "maxPrice", required = false) BigDecimal maxPrice) {
        int safeLimit = Math.max(1, Math.min(limit, 30));
        return retrievalGatewayService.search(query, safeLimit, category, maxPrice)
                .map(this::toResponse);
    }

    private RetrievalSearchResponse toResponse(RetrievalChunk chunk) {
        return new RetrievalSearchResponse(
                chunk.productId(),
                chunk.title(),
                chunk.category(),
                chunk.source(),
                chunk.price(),
                chunk.updateTime(),
                chunk.chunk(),
                chunk.score());
    }

    public record RetrievalSearchResponse(
            UUID productId,
            String title,
            String category,
            String source,
            BigDecimal price,
            Instant updateTime,
            String chunk,
            double score) {
    }
}
