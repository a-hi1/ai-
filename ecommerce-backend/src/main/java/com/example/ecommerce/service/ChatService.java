package com.example.ecommerce.service;

import java.time.Instant;
import java.util.UUID;

import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.stereotype.Service;

import com.example.ecommerce.dto.ChatAdvicePayload;
import com.example.ecommerce.model.ChatMessage;
import com.example.ecommerce.repository.ChatMessageRepository;

import reactor.core.publisher.Mono;

@Service
public class ChatService {
    private final ChatMessageRepository chatMessageRepository;
    private final SkillRegistry skillRegistry;
    private final AiShoppingAdvisorService aiShoppingAdvisorService;
    private final R2dbcEntityTemplate entityTemplate;

    public ChatService(ChatMessageRepository chatMessageRepository,
                       SkillRegistry skillRegistry,
                       AiShoppingAdvisorService aiShoppingAdvisorService,
                       R2dbcEntityTemplate entityTemplate) {
        this.chatMessageRepository = chatMessageRepository;
        this.skillRegistry = skillRegistry;
        this.aiShoppingAdvisorService = aiShoppingAdvisorService;
        this.entityTemplate = entityTemplate;
    }

    public Mono<ChatMessage> saveUserMessage(UUID userId, String message) {
        ChatMessage chatMessage = new ChatMessage(UUID.randomUUID(), userId, "USER", message, Instant.now());
        return entityTemplate.insert(ChatMessage.class).using(chatMessage);
    }

    public Mono<ChatMessage> saveAssistantMessage(UUID userId, String message) {
        ChatMessage chatMessage = new ChatMessage(UUID.randomUUID(), userId, "ASSISTANT", message, Instant.now());
        return entityTemplate.insert(ChatMessage.class).using(chatMessage);
    }

    public Mono<ChatAdvicePayload> generateReply(UUID userId, String message) {
        return skillRegistry.handleSkill(message)
                .map(reply -> new ChatAdvicePayload(reply, java.util.List.of(), java.util.List.of(), java.util.List.of(), "技能指令", "未设置", false))
                .switchIfEmpty(aiShoppingAdvisorService.advise(userId, message));
    }
}
