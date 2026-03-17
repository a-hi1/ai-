package com.example.ecommerce.service.ai;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.example.ecommerce.model.Product;

@Component
public class AdvisorToolContext {
    // 使用 InheritableThreadLocal 确保 LangChain4j 在工具调用时若产生子线程，上下文也能正确传递
    private final InheritableThreadLocal<RequestState> stateHolder = new InheritableThreadLocal<>();

    public void begin(UUID userId, String message) {
        stateHolder.set(new RequestState(userId, message));
    }

    public UUID currentUserId() {
        RequestState state = requireState();
        return state.userId();
    }

    public String currentMessage() {
        RequestState state = requireState();
        return state.message();
    }

    public void recordProducts(String toolName, List<Product> products) {
        if (products == null || products.isEmpty()) {
            recordTrace(toolName + ": 0 products");
            return;
        }

        RequestState state = requireState();
        for (Product product : products) {
            if (product.getId() != null) {
                state.productsById().put(product.getId(), product);
            }
        }
        recordTrace(toolName + ": " + products.size() + " products");
    }

    public void recordTrace(String trace) {
        RequestState state = requireState();
        state.traces().add(trace);
    }

    public AdvisorToolSnapshot finish() {
        RequestState state = requireState();
        stateHolder.remove();
        return new AdvisorToolSnapshot(List.copyOf(state.productsById().values()), List.copyOf(state.traces()));
    }

    public void clear() {
        stateHolder.remove();
    }

    private RequestState requireState() {
        RequestState state = stateHolder.get();
        if (state == null) {
            throw new IllegalStateException("AdvisorToolContext is not active");
        }
        return state;
    }

    public record AdvisorToolSnapshot(List<Product> products, List<String> traces) {
        public boolean hasProducts() {
            return !products.isEmpty();
        }
    }

    private record RequestState(UUID userId,
                                String message,
                                Map<UUID, Product> productsById,
                                List<String> traces) {
        private RequestState(UUID userId, String message) {
            this(userId, message, new LinkedHashMap<>(), new ArrayList<>());
        }
    }
}