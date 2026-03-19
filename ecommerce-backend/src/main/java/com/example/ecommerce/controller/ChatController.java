package com.example.ecommerce.controller;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.example.ecommerce.dto.ChatInsightResponse;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;

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
    private final ObjectMapper objectMapper;

    public ChatController(ChatService chatService,
                          ChatMessageRepository chatMessageRepository,
                          ObjectMapper objectMapper) {
        this.chatService = chatService;
        this.chatMessageRepository = chatMessageRepository;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/send")
    public Mono<ChatResponse> send(@Validated @RequestBody ChatRequest request) {
        UUID userId = request.resolvedUserId();
        String sessionId = request.resolvedSessionId();
        return chatService.saveUserMessage(userId, sessionId, request.message())
                .then(chatService.generateReply(userId, request.message(), sessionId))
            .flatMap(advice -> toResponse(userId, sessionId, advice));
    }

    @PostMapping("/quick")
    public Mono<ChatResponse> quick(@Validated @RequestBody ChatRequest request) {
        UUID userId = request.resolvedUserId();
        String sessionId = request.resolvedSessionId();
        return chatService.saveUserMessage(userId, sessionId, request.message())
                .then(chatService.generateQuickReply(userId, request.message(), sessionId))
            .flatMap(advice -> toResponse(userId, sessionId, advice));
    }

    @GetMapping("/history/{userId}")
    public Flux<com.example.ecommerce.model.ChatMessage> history(@PathVariable("userId") UUID userId) {
        return chatMessageRepository.findByUserId(userId);
    }

    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> stream(@Validated @RequestBody ChatRequest request) {
        UUID userId = request.resolvedUserId();
        String sessionId = request.resolvedSessionId();
        Flux<ServerSentEvent<String>> earlyEvents = Flux.just(
            buildEvent("start", "accepted"),
            buildEvent("progress", toJson(new StreamProgress(1, 3, "Agent 正在理解需求")))
        );

        Flux<ServerSentEvent<String>> replyEvents = chatService.saveUserMessage(userId, sessionId, request.message())
            .then(chatService.generateReply(userId, request.message(), sessionId))
            .flatMapMany(advice -> toStream(userId, sessionId, advice));

        return Flux.concat(earlyEvents, replyEvents)
                .onErrorResume(error -> Flux.just(buildEvent("error", summarizeError(error))));
    }

    private Mono<ChatResponse> toResponse(UUID userId, String sessionId, ChatAdvicePayload advice) {
        return chatService.saveAssistantMessage(userId, sessionId, advice.reply())
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

    private Flux<ServerSentEvent<String>> toStream(UUID userId, String sessionId, ChatAdvicePayload advice) {
        return chatService.saveAssistantMessage(userId, sessionId, advice.reply())
                .flatMapMany(saved -> {
                    ChatResponse response = new ChatResponse(
                            saved.getContent(),
                            saved.getCreatedAt(),
                            advice.recommendations(),
                            advice.relatedRecommendations(),
                            advice.insights(),
                            advice.detectedIntent(),
                            advice.budgetSummary(),
                            advice.fallback());

                    String fullText = saved.getContent() == null ? "" : saved.getContent();
                    List<String> chunks = splitChunks(fullText);
                    List<String> progressSteps = buildAgentProgressSteps(advice.insights());
                    Flux<ServerSentEvent<String>> progress = Flux.fromStream(
                                    IntStream.range(0, progressSteps.size())
                                            .mapToObj(index -> buildEvent("progress", toJson(new StreamProgress(index + 1, progressSteps.size(), progressSteps.get(index)))))
                            )
                            .delayElements(Duration.ofMillis(18));
                    Flux<ServerSentEvent<String>> stream = Flux.fromIterable(chunks)
                            .delayElements(Duration.ofMillis(resolveDelay(fullText.length())))
                            .map(chunk -> buildEvent("delta", chunk));

                    return Flux.concat(
                            progress,
                            stream,
                            Flux.just(buildEvent("final", toJson(response))),
                            Flux.just(buildEvent("done", "[DONE]"))
                    );
                });
    }

    private List<String> buildAgentProgressSteps(List<ChatInsightResponse> insights) {
        List<String> steps = new ArrayList<>();
        steps.add("Agent 已接收需求");
        steps.add("Agent 正在检索候选商品");
        steps.add("Agent 正在匹配场景与预算");

        if (insights != null) {
            insights.stream()
                    .filter(item -> item != null && item.label() != null && item.value() != null)
                    .filter(item -> item.label().contains("Tool调用轨迹"))
                    .findFirst()
                    .ifPresent(item -> {
                        String[] traceParts = item.value().split("\\|");
                        for (String trace : traceParts) {
                            String normalized = trace == null ? "" : trace.trim();
                            if (!normalized.isEmpty()) {
                                steps.add("Tool: " + normalized);
                            }
                        }
                    });
        }

        steps.add("Agent 正在生成推荐结论");
        return steps;
    }

    private int resolveDelay(int length) {
        if (length > 520) {
            return 3;
        }
        if (length > 260) {
            return 5;
        }
        return 6;
    }

    private List<String> splitChunks(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }

        List<String> chunks = new ArrayList<>();
        int step = text.length() > 520 ? 12 : text.length() > 260 ? 9 : 7;
        int cursor = 0;

        while (cursor < text.length()) {
            char current = text.charAt(cursor);
            if (isPunctuation(current)) {
                chunks.add(String.valueOf(current));
                cursor += 1;
                continue;
            }

            int end = Math.min(text.length(), cursor + step);
            chunks.add(text.substring(cursor, end));
            cursor = end;
        }

        return chunks;
    }

    private boolean isPunctuation(char value) {
        return "，。！？；：,.!?;:".indexOf(value) >= 0;
    }

    private ServerSentEvent<String> buildEvent(String event, String data) {
        return ServerSentEvent.<String>builder()
                .event(event)
                .data(data == null ? "" : data)
                .build();
    }

    private String toJson(Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException error) {
            return "{}";
        }
    }

    private String summarizeError(Throwable error) {
        if (error == null) {
            return "unknown_error";
        }
        String message = error.getMessage();
        if (message == null || message.isBlank()) {
            return error.getClass().getSimpleName();
        }
        return message;
    }

    private record StreamProgress(int index, int total, String step) {
    }
}
