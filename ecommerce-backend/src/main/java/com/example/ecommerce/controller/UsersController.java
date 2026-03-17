package com.example.ecommerce.controller;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;

import com.example.ecommerce.dto.AuthUserResponse;
import com.example.ecommerce.dto.CreateUserRequest;
import com.example.ecommerce.dto.LoginRequest;
import com.example.ecommerce.dto.UpdateProfileRequest;
import com.example.ecommerce.model.User;
import com.example.ecommerce.repository.UserRepository;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/users")
@CrossOrigin
public class UsersController {
    private final UserRepository userRepository;
    private final R2dbcEntityTemplate entityTemplate;

    public UsersController(UserRepository userRepository, R2dbcEntityTemplate entityTemplate) {
        this.userRepository = userRepository;
        this.entityTemplate = entityTemplate;
    }

    @PostMapping
    public Mono<User> create(@Validated @RequestBody CreateUserRequest request) {
        return buildUser(request).flatMap(user -> entityTemplate.insert(User.class).using(user));
    }

    @PostMapping("/register")
    public Mono<AuthUserResponse> register(@Validated @RequestBody CreateUserRequest request) {
        return userRepository.findByEmail(request.email())
                .flatMap(existing -> Mono.<AuthUserResponse>error(new ResponseStatusException(HttpStatus.CONFLICT, "Email already exists")))
                .switchIfEmpty(Mono.defer(() -> buildUser(request)
                    .flatMap(user -> entityTemplate.insert(User.class).using(user))
                        .map(this::toAuthResponse)));
    }

    @PostMapping("/login")
    public Mono<AuthUserResponse> login(@Validated @RequestBody LoginRequest request) {
        return userRepository.findByEmail(request.email())
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid email or password")))
                .flatMap(user -> {
                    if (!hashPassword(request.password()).equals(user.getPasswordHash())) {
                        return Mono.error(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid email or password"));
                    }
                    return Mono.just(toAuthResponse(user));
                });
    }

    @GetMapping
    public Flux<User> list() {
        return userRepository.findAll();
    }

    @GetMapping("/health")
    public Mono<Map<String, Object>> health() {
        return Mono.just(Map.of(
                "service", "user-service",
                "status", "UP",
                "resource", "users",
                "timestamp", Instant.now().toString()));
    }

    @GetMapping("/{id}")
    public Mono<AuthUserResponse> get(@PathVariable("id") UUID id) {
        return userRepository.findById(id).map(this::toAuthResponse);
    }

    @PutMapping("/{id}/profile")
    public Mono<AuthUserResponse> updateProfile(@PathVariable("id") UUID id, @Validated @RequestBody UpdateProfileRequest request) {
        return userRepository.findById(id)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found")))
                .flatMap(user -> {
                    user.setDisplayName(request.displayName());
                    user.setPhone(request.phone());
                    user.setCity(request.city());
                    user.setBio(request.bio());
                    return userRepository.save(user);
                })
                .map(this::toAuthResponse);
    }

    private String hashPassword(String rawPassword) {
        return "HASHED:" + rawPassword;
    }

    private AuthUserResponse toAuthResponse(User user) {
        return new AuthUserResponse(user.getId(), user.getEmail(), user.getRole(), user.getDisplayName(), user.getPhone(), user.getCity(), user.getBio());
    }

    private Mono<User> buildUser(CreateUserRequest request) {
        String role = request.role() == null || request.role().isBlank() ? "USER" : request.role();
        String defaultName = request.email().contains("@") ? request.email().substring(0, request.email().indexOf('@')) : request.email();
        User user = new User(UUID.randomUUID(), request.email(), hashPassword(request.password()), role, defaultName, "", "", "智能导购用户", Instant.now());
        return Mono.just(user);
    }
}
