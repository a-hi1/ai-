package com.example.ecommerce.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.ecommerce.dto.AiProviderConfigActionResponse;
import com.example.ecommerce.dto.AiProviderOverviewResponse;
import com.example.ecommerce.dto.UpdateAiProviderConfigRequest;
import com.example.ecommerce.service.AiProviderConfigService;

import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/admin/ai-config")
public class AiProviderConfigController {
    private final AiProviderConfigService aiProviderConfigService;

    public AiProviderConfigController(AiProviderConfigService aiProviderConfigService) {
        this.aiProviderConfigService = aiProviderConfigService;
    }

    @GetMapping("/overview")
    public Mono<AiProviderOverviewResponse> overview() {
        return Mono.fromSupplier(aiProviderConfigService::getOverview);
    }

    @PutMapping
    public Mono<AiProviderConfigActionResponse> update(@RequestBody UpdateAiProviderConfigRequest request) {
        return Mono.fromCallable(() -> aiProviderConfigService.update(request));
    }
}