package com.example.ecommerce.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "ai")
public class AiProperties {
    private String provider = "deepseek";
    private Provider deepseek = new Provider();
    private Provider chatgpt = new Provider();
    private Provider siliconflow = new Provider();

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public Provider getDeepseek() {
        return deepseek;
    }

    public void setDeepseek(Provider deepseek) {
        this.deepseek = deepseek;
    }

    public Provider getChatgpt() {
        return chatgpt;
    }

    public void setChatgpt(Provider chatgpt) {
        this.chatgpt = chatgpt;
    }

    public Provider getSiliconflow() {
        return siliconflow;
    }

    public void setSiliconflow(Provider siliconflow) {
        this.siliconflow = siliconflow;
    }

    public static class Provider {
        private String apiKey = "";
        private String baseUrl = "";
        private String modelName = "";
        private Duration timeout = Duration.ofSeconds(90);
        private Integer maxRetries = 1;
        private boolean logRequests = false;
        private boolean logResponses = false;

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getModelName() {
            return modelName;
        }

        public void setModelName(String modelName) {
            this.modelName = modelName;
        }

        public Duration getTimeout() {
            return timeout;
        }

        public void setTimeout(Duration timeout) {
            this.timeout = timeout;
        }

        public Integer getMaxRetries() {
            return maxRetries;
        }

        public void setMaxRetries(Integer maxRetries) {
            this.maxRetries = maxRetries;
        }

        public boolean isLogRequests() {
            return logRequests;
        }

        public void setLogRequests(boolean logRequests) {
            this.logRequests = logRequests;
        }

        public boolean isLogResponses() {
            return logResponses;
        }

        public void setLogResponses(boolean logResponses) {
            this.logResponses = logResponses;
        }
    }
}
