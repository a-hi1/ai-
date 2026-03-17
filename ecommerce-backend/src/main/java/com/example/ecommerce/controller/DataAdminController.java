package com.example.ecommerce.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.ecommerce.dto.DataAdminActionRequest;
import com.example.ecommerce.dto.DataAdminActionResponse;
import com.example.ecommerce.dto.DataAdminOverviewResponse;
import com.example.ecommerce.service.DataAdminService;

import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/admin/data")
public class DataAdminController {
    private final DataAdminService dataAdminService;

    public DataAdminController(DataAdminService dataAdminService) {
        this.dataAdminService = dataAdminService;
    }

    @GetMapping("/overview")
    public Mono<DataAdminOverviewResponse> overview() {
        return dataAdminService.getOverview();
    }

    @PostMapping("/clear")
    public Mono<DataAdminActionResponse> clear(@RequestBody(required = false) DataAdminActionRequest request) {
        String scope = request == null ? "SAMPLE" : request.scope();
        return dataAdminService.clear(scope);
    }

    @PostMapping("/rebuild-sample")
    public Mono<DataAdminActionResponse> rebuildSample() {
        return dataAdminService.rebuildSampleData();
    }
}