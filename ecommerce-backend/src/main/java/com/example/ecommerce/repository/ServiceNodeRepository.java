package com.example.ecommerce.repository;

import java.util.UUID;

import org.springframework.data.repository.reactive.ReactiveCrudRepository;

import com.example.ecommerce.model.ServiceNode;

import reactor.core.publisher.Flux;

public interface ServiceNodeRepository extends ReactiveCrudRepository<ServiceNode, UUID> {
    Flux<ServiceNode> findByServiceName(String serviceName);
}
