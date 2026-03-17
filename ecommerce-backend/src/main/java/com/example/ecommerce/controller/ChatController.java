package com.example.ecommerce.controller;

import java.util.UUID;

import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.ecommerce.dto.ChatAdvicePayload;
import com.example.ecommerce.dto.ChatRequest;
import com.example.ecommerce.dto.ChatResponse;
import com.example.ecommerce.repository.ChatMessageRepository;
import com.example.ecommerce.service.ChatService;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/chat")
public class ChatController {
    private final ChatService chatService;
    private final ChatMessageRepository chatMessageRepository;

    public ChatController(ChatService chatService, ChatMessageRepository chatMessageRepository) {
        this.chatService = chatService;
        this.chatMessageRepository = chatMessageRepository;
    }

    @PostMapping("/send")
    public Mono<ChatResponse> send(@Validated @RequestBody ChatRequest request) {
        UUID userId = request.resolvedUserId();
        return chatService.saveUserMessage(userId, request.message())
                .then(chatService.generateReply(userId, request.message()))
                .flatMap(advice -> toResponse(userId, advice));
    }

    @GetMapping("/history/{userId}")
    public Flux<com.example.ecommerce.model.ChatMessage> history(@PathVariable("userId") UUID userId) {
        return chatMessageRepository.findByUserId(userId);
    }

    private Mono<ChatResponse> toResponse(UUID userId, ChatAdvicePayload advice) {
        return chatService.saveAssistantMessage(userId, advice.reply())
                .map(saved -> new ChatResponse(
                        saved.getContent(),
                        saved.getCreatedAt(),
                        advice.recommendations(),
                    advice.relatedRecommendations(),
                        advice.insights(),
                        advice.detectedIntent(),
                        advice.budgetSummary(),
                        advice.fallback()));
    }
}
