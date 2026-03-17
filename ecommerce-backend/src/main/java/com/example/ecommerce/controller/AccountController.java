package com.example.ecommerce.controller;

import java.util.UUID;

import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.ecommerce.dto.AccountOverviewResponse;
import com.example.ecommerce.dto.RecordProductViewRequest;
import com.example.ecommerce.service.AccountService;

import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/account")
public class AccountController {
    private final AccountService accountService;

    public AccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    @GetMapping("/{userId}/overview")
    public Mono<AccountOverviewResponse> overview(@PathVariable("userId") UUID userId) {
        return accountService.getOverview(userId);
    }

    @PostMapping("/views")
    public Mono<Void> recordView(@Validated @RequestBody RecordProductViewRequest request) {
        return accountService.recordView(request.userId(), request.productId(), request.source(), request.reason()).then();
    }
}