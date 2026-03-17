package com.example.ecommerce.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Locale;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.example.ecommerce.config.AiProperties;
import com.example.ecommerce.dto.AiProviderConfigActionResponse;
import com.example.ecommerce.dto.AiProviderEntryResponse;
import com.example.ecommerce.dto.AiProviderOverviewResponse;
import com.example.ecommerce.dto.AiProviderUpdateRequest;
import com.example.ecommerce.dto.UpdateAiProviderConfigRequest;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.annotation.PostConstruct;

@Service
public class AiProviderConfigService {
    private static final String DEFAULT_DEEPSEEK_BASE_URL = "https://api.deepseek.com/v1";
    private static final String DEFAULT_DEEPSEEK_MODEL = "deepseek-chat";
    private static final String DEFAULT_SILICONFLOW_BASE_URL = "https://api.siliconflow.cn/v1";
    private static final String DEFAULT_SILICONFLOW_MODEL = "Pro/zai-org/GLM-5";

    private final AiProperties aiProperties;
    private final AiShoppingAdvisorService aiShoppingAdvisorService;
    private final ObjectMapper objectMapper;
    private final Path configPath;

    public AiProviderConfigService(AiProperties aiProperties,
                                   AiShoppingAdvisorService aiShoppingAdvisorService,
                                   ObjectMapper objectMapper,
                                   @Value("${ai.runtime-config.path:../.runtime/ecommerce-backend/ai-provider-config.json}") String configPath) {
        this.aiProperties = aiProperties;
        this.aiShoppingAdvisorService = aiShoppingAdvisorService;
        this.objectMapper = objectMapper;
        this.configPath = Path.of(configPath).toAbsolutePath().normalize();
    }

    @PostConstruct
    public void loadPersistedConfig() {
        if (!Files.exists(configPath)) {
            return;
        }

        try {
            PersistedAiProviderConfig persisted = objectMapper.readValue(configPath.toFile(), PersistedAiProviderConfig.class);
            applyPersistedConfig(persisted);
            aiShoppingAdvisorService.reloadProviderConfiguration("PERSISTED_CONFIG_LOADED");
        } catch (IOException error) {
            aiShoppingAdvisorService.reloadProviderConfiguration("PERSISTED_CONFIG_LOAD_FAILED");
        }
    }

    public synchronized AiProviderOverviewResponse getOverview() {
        AiShoppingAdvisorService.AiRuntimeStatus status = aiShoppingAdvisorService.getRuntimeStatus();
        return new AiProviderOverviewResponse(
                normalizeProviderName(aiProperties.getProvider()),
                status.modelName(),
                status.fallback(),
                status.reason(),
                status.consecutiveFallbacks(),
                Files.exists(configPath),
                buildEntry(aiProperties.getDeepseek()),
                buildEntry(aiProperties.getSiliconflow()));
    }

    public synchronized AiProviderConfigActionResponse update(UpdateAiProviderConfigRequest request) {
        if (request == null || isBlank(request.provider())) {
            throw new IllegalArgumentException("provider 不能为空");
        }

        String normalizedProvider = normalizeProviderName(request.provider());
        if (!"deepseek".equals(normalizedProvider) && !"siliconflow".equals(normalizedProvider)) {
            throw new IllegalArgumentException("当前仅支持切换到 deepseek 或 siliconflow");
        }

        applyProviderUpdate("deepseek", aiProperties.getDeepseek(), request.deepseek(), DEFAULT_DEEPSEEK_BASE_URL, DEFAULT_DEEPSEEK_MODEL);
        applyProviderUpdate("siliconflow", aiProperties.getSiliconflow(), request.siliconflow(), DEFAULT_SILICONFLOW_BASE_URL, DEFAULT_SILICONFLOW_MODEL);
        aiProperties.setProvider(normalizedProvider);

        persistCurrentConfig();
        aiShoppingAdvisorService.reloadProviderConfiguration("CONFIG_UPDATED");
        return new AiProviderConfigActionResponse("AI Provider 配置已保存，并将在重启后继续保留。", getOverview());
    }

    private void applyPersistedConfig(PersistedAiProviderConfig persisted) {
        if (persisted == null) {
            return;
        }

        if (!isBlank(persisted.provider())) {
            aiProperties.setProvider(normalizeProviderName(persisted.provider()));
        }
        applyPersistedProvider("deepseek", aiProperties.getDeepseek(), persisted.deepseek(), DEFAULT_DEEPSEEK_BASE_URL, DEFAULT_DEEPSEEK_MODEL);
        applyPersistedProvider("siliconflow", aiProperties.getSiliconflow(), persisted.siliconflow(), DEFAULT_SILICONFLOW_BASE_URL, DEFAULT_SILICONFLOW_MODEL);
    }

    private void applyPersistedProvider(String providerName,
                                        AiProperties.Provider target,
                                        PersistedAiProvider persisted,
                                        String defaultBaseUrl,
                                        String defaultModelName) {
        if (target == null || persisted == null) {
            return;
        }

        if (!isBlank(persisted.apiKey())) {
            target.setApiKey(persisted.apiKey().trim());
        }
        target.setBaseUrl(isBlank(persisted.baseUrl()) ? defaultBaseUrl : persisted.baseUrl().trim());
        if (!isBlank(persisted.modelName())) {
            target.setModelName(normalizeModelName(providerName, persisted.modelName()));
        } else if (defaultModelName != null && isBlank(target.getModelName())) {
            target.setModelName(defaultModelName);
        }
    }

    private void applyProviderUpdate(String providerName,
                                     AiProperties.Provider target,
                                     AiProviderUpdateRequest update,
                                     String defaultBaseUrl,
                                     String defaultModelName) {
        if (target == null) {
            return;
        }

        if (update != null && !isBlank(update.apiKey())) {
            target.setApiKey(update.apiKey().trim());
        }

        String baseUrl = update == null ? null : update.baseUrl();
        target.setBaseUrl(isBlank(baseUrl) ? fallbackValue(target.getBaseUrl(), defaultBaseUrl) : baseUrl.trim());

        String modelName = update == null ? null : update.modelName();
        if (!isBlank(modelName)) {
            target.setModelName(normalizeModelName(providerName, modelName));
        } else if (defaultModelName != null && isBlank(target.getModelName())) {
            target.setModelName(defaultModelName);
        }
    }

    private String normalizeModelName(String providerName, String modelName) {
        if (isBlank(modelName)) {
            return "";
        }

        String trimmed = modelName.trim();
        if (!"siliconflow".equals(providerName)) {
            return trimmed;
        }

        return switch (trimmed.toLowerCase(Locale.ROOT)) {
            case "glm-5" -> DEFAULT_SILICONFLOW_MODEL;
            case "glm-4.7" -> "Pro/zai-org/GLM-4.7";
            default -> trimmed;
        };
    }

    private void persistCurrentConfig() {
        try {
            Files.createDirectories(configPath.getParent());
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(configPath.toFile(), new PersistedAiProviderConfig(
                    normalizeProviderName(aiProperties.getProvider()),
                    snapshotOf(aiProperties.getDeepseek()),
                    snapshotOf(aiProperties.getSiliconflow()),
                    Instant.now().toString()));
        } catch (IOException error) {
            throw new IllegalStateException("保存 AI Provider 配置失败", error);
        }
    }

    private PersistedAiProvider snapshotOf(AiProperties.Provider provider) {
        if (provider == null) {
            return new PersistedAiProvider("", "", "");
        }
        return new PersistedAiProvider(safe(provider.getApiKey()), safe(provider.getBaseUrl()), safe(provider.getModelName()));
    }

    private AiProviderEntryResponse buildEntry(AiProperties.Provider provider) {
        String apiKey = provider == null ? "" : safe(provider.getApiKey());
        return new AiProviderEntryResponse(
                !isBlank(apiKey),
                maskApiKey(apiKey),
                provider == null ? "" : safe(provider.getBaseUrl()),
                provider == null ? "" : safe(provider.getModelName()));
    }

    private String maskApiKey(String apiKey) {
        if (isBlank(apiKey)) {
            return "未配置";
        }
        String trimmed = apiKey.trim();
        if (trimmed.length() <= 8) {
            return "已配置";
        }
        return trimmed.substring(0, 4) + " **** " + trimmed.substring(trimmed.length() - 4);
    }

    private String normalizeProviderName(String provider) {
        return isBlank(provider) ? "deepseek" : provider.trim().toLowerCase(Locale.ROOT);
    }

    private String fallbackValue(String current, String fallback) {
        return isBlank(current) ? fallback : current;
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private record PersistedAiProviderConfig(
            String provider,
            PersistedAiProvider deepseek,
            PersistedAiProvider siliconflow,
            String updatedAt
    ) {
    }

    private record PersistedAiProvider(
            String apiKey,
            String baseUrl,
            String modelName
    ) {
    }
}