package com.example.ecommerce.controller;

import java.time.Instant;
import java.util.UUID;

import org.springframework.validation.annotation.Validated;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.ecommerce.dto.HeartbeatRequest;
import com.example.ecommerce.dto.RegisterServiceRequest;
import com.example.ecommerce.model.ServiceNode;
import com.example.ecommerce.repository.ServiceNodeRepository;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/monitor")
public class MonitorController {
    private final ServiceNodeRepository serviceNodeRepository;
    private final R2dbcEntityTemplate entityTemplate;

    public MonitorController(ServiceNodeRepository serviceNodeRepository, R2dbcEntityTemplate entityTemplate) {
        this.serviceNodeRepository = serviceNodeRepository;
        this.entityTemplate = entityTemplate;
    }

    @PostMapping("/register")
    public Mono<ServiceNode> register(@Validated @RequestBody RegisterServiceRequest request) {
        ServiceNode node = new ServiceNode(UUID.randomUUID(), request.serviceName(), request.host(), request.port(), "ONLINE", Instant.now());
        return entityTemplate.insert(ServiceNode.class).using(node);
    }

    @PostMapping("/heartbeat")
    public Mono<ServiceNode> heartbeat(@Validated @RequestBody HeartbeatRequest request) {
        return serviceNodeRepository.findById(request.serviceId())
                .flatMap(node -> {
                    node.setStatus("ONLINE");
                    node.setLastHeartbeat(Instant.now());
                    return serviceNodeRepository.save(node);
                });
    }

    @PostMapping("/offline")
    public Mono<ServiceNode> offline(@Validated @RequestBody HeartbeatRequest request) {
        return serviceNodeRepository.findById(request.serviceId())
                .flatMap(node -> {
                    node.setStatus("OFFLINE");
                    node.setLastHeartbeat(Instant.now());
                    return serviceNodeRepository.save(node);
                });
    }

    @GetMapping("/list")
    public Flux<ServiceNode> list() {
        return serviceNodeRepository.findAll();
    }
}
