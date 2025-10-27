package com.trendyol.stove.examples.java.spring.infra.components.product.persistency;

import static com.trendyol.stove.examples.java.spring.infra.components.product.persistency.CollectionConstants.PRODUCT_COLLECTION;

import com.couchbase.client.java.ReactiveBucket;
import com.couchbase.client.java.ReactiveCollection;
import com.trendyol.stove.examples.domain.ddd.EventPublisher;
import com.trendyol.stove.examples.domain.product.Product;
import com.trendyol.stove.examples.java.spring.domain.ProductReactiveRepository;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
public class CouchbaseProductRepository implements ProductReactiveRepository {
  private final ReactiveBucket bucket;
  private final EventPublisher eventPublisher;

  public CouchbaseProductRepository(ReactiveBucket bucket, EventPublisher eventPublisher) {
    this.bucket = bucket;
    this.eventPublisher = eventPublisher;
  }

  @Override
  public Mono<Product> findById(String id) {
    return collection().get(id).map(result -> result.contentAs(Product.class));
  }

  public Mono<Void> save(Product product) {
    return collection()
        .insert(product.getIdAsString(), product)
        .doOnSuccess(result -> eventPublisher.publishFor(product))
        .flatMap(result -> Mono.empty());
  }

  private ReactiveCollection collection() {
    return bucket.collection(PRODUCT_COLLECTION);
  }
}
