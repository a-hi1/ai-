package com.example.ecommerce.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

@Table("products")
public class Product {
    @Id
    private UUID id;
    private String name;
    private String description;
    private BigDecimal price;
    private String imageUrl;
    private String tags;
    private String category;
    private String specs;
    private String sellingPoints;
    private String policy;
    private String sourceProductId;
    private String dataSource;
    private Instant createdAt;
    private Instant updatedAt;

    public Product() {
    }

    public Product(UUID id, String name, String description, BigDecimal price, String imageUrl, String tags, String dataSource, Instant createdAt) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.price = price;
        this.imageUrl = imageUrl;
        this.tags = tags;
        this.category = null;
        this.specs = null;
        this.sellingPoints = null;
        this.policy = null;
        this.sourceProductId = null;
        this.dataSource = dataSource;
        this.createdAt = createdAt;
        this.updatedAt = createdAt;
    }

    public Product(UUID id,
                   String name,
                   String description,
                   BigDecimal price,
                   String imageUrl,
                   String tags,
                   String category,
                   String specs,
                   String sellingPoints,
                   String policy,
                   String sourceProductId,
                   String dataSource,
                   Instant createdAt,
                   Instant updatedAt) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.price = price;
        this.imageUrl = imageUrl;
        this.tags = tags;
        this.category = category;
        this.specs = specs;
        this.sellingPoints = sellingPoints;
        this.policy = policy;
        this.sourceProductId = sourceProductId;
        this.dataSource = dataSource;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public void setPrice(BigDecimal price) {
        this.price = price;
    }

    public String getImageUrl() {
        return imageUrl;
    }

    public void setImageUrl(String imageUrl) {
        this.imageUrl = imageUrl;
    }

    public String getTags() {
        return tags;
    }

    public void setTags(String tags) {
        this.tags = tags;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public String getSpecs() {
        return specs;
    }

    public void setSpecs(String specs) {
        this.specs = specs;
    }

    public String getSellingPoints() {
        return sellingPoints;
    }

    public void setSellingPoints(String sellingPoints) {
        this.sellingPoints = sellingPoints;
    }

    public String getPolicy() {
        return policy;
    }

    public void setPolicy(String policy) {
        this.policy = policy;
    }

    public String getSourceProductId() {
        return sourceProductId;
    }

    public void setSourceProductId(String sourceProductId) {
        this.sourceProductId = sourceProductId;
    }

    public String getDataSource() {
        return dataSource;
    }

    public void setDataSource(String dataSource) {
        this.dataSource = dataSource;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
