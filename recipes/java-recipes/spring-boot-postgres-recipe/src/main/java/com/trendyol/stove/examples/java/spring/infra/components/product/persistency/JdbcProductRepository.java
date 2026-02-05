package com.trendyol.stove.examples.java.spring.infra.components.product.persistency;

import com.trendyol.stove.examples.domain.ddd.EventPublisher;
import com.trendyol.stove.examples.domain.product.Product;
import com.trendyol.stove.examples.java.spring.domain.ProductReactiveRepository;

import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;

import reactor.core.publisher.Mono;

import java.time.Instant;

@Component
public class JdbcProductRepository implements ProductReactiveRepository {
    private final DatabaseClient databaseClient;
    private final EventPublisher eventPublisher;

    public JdbcProductRepository(DatabaseClient databaseClient, EventPublisher eventPublisher) {
        this.databaseClient = databaseClient;
        this.eventPublisher = eventPublisher;
    }

    @Override
    public Mono<Product> findById(String id) {
        return databaseClient
                .sql("SELECT * FROM products WHERE id = :id")
                .bind("id", id)
                .map(row -> {
                    String productId = row.get("id", String.class);
                    String name = row.get("name", String.class);
                    Double price = row.get("price", Double.class);
                    Integer categoryId = row.get("category_id", Integer.class);
                    Long version = row.get("version", Long.class);

                    return Product.fromPersistency(
                            productId, name, price, categoryId, version != null ? version : 0L);
                })
                .one();
    }

    public Mono<Void> save(Product product) {
        return databaseClient
                .sql("""
                        INSERT INTO products (id, name, price, category_id, created_date, version)
                        VALUES (:id, :name, :price, :categoryId, :createdDate, :version)
                        ON CONFLICT (id) DO UPDATE SET
                          name = EXCLUDED.name,
                          price = EXCLUDED.price,
                          category_id = EXCLUDED.category_id,
                          version = EXCLUDED.version
                        """)
                .bind("id", product.getIdAsString())
                .bind("name", product.getName())
                .bind("price", product.getPrice())
                .bind("categoryId", product.getCategoryId())
                .bind("createdDate", Instant.now())
                .bind("version", product.getVersion())
                .fetch()
                .rowsUpdated()
                .doOnSuccess(result -> eventPublisher.publishFor(product))
                .then();
    }
}
