# Elasticsearch

Real ES in a container or wired to an existing cluster. Save/get/delete by id, query via JSON DSL or the Java client builder, bulk ops via raw `client()`.

<a class="open-in-wizard" data-sys="elasticsearch">Open in setup wizard</a>

<!--{wizard:snippet id=sys.elasticsearch parts=gradle,configure,test}-->

<div class="stove-tldr" markdown>
<span class="stove-tldr-title">In 30 seconds</span>
Register <code>elasticsearch { ElasticsearchSystemOptions(...) }</code>. Use <code>save(...)</code>, <code>shouldGet&lt;T&gt;(index, key)</code>, <code>shouldQuery&lt;T&gt;(jsonOrBuilder, index)</code>, <code>shouldDelete(key, index)</code>. Drop into <code>client()</code> for bulk, aggregation, index admin.
</div>

## Configure

```kotlin
Stove().with {
  elasticsearch {
    ElasticsearchSystemOptions(
      container = ElasticContainerOptions(
        disableSecurity = true,           // simpler local testing
        password = "changeme"
      ),
      configureExposedConfiguration = { cfg ->
        listOf(
          "elasticsearch.url=${cfg.url}",
          "elasticsearch.host=${cfg.host}",
          "elasticsearch.port=${cfg.port}"
        )
      }
    )
  }
}.run()
```

| Field | Use |
|---|---|
| `container.disableSecurity` | Turn off TLS + auth for tests; `true` is sensible default |
| `container.password` | Used when security stays on |
| `clientConfigurer` | Customize the Java `ElasticsearchClient` (httpClient, SSL) |
| `jsonpMapper` | Override the JSON-P mapper (e.g. for Jakarta migration) |
| `serde` | Align with your app's mapper |

## DSL

### Save / fetch / delete

```kotlin
stove {
  elasticsearch {
    save(
      id = "1",
      instance = Product(id = "1", name = "Laptop", price = 999.99),
      index = "products"
    )

    shouldGet<Product>(index = "products", key = "1") {
      it.name shouldBe "Laptop"
    }

    shouldDelete(key = "1", index = "products")
  }
}
```

### Query

```kotlin
stove {
  elasticsearch {
    // JSON DSL
    shouldQuery<Product>(
      query = """
        { "query": { "range": { "price": { "gte": 100 } } } }
      """.trimIndent(),
      index = "products"
    ) { products ->
      products.all { it.price >= 100 } shouldBe true
    }

    // Or via Java client builder
    shouldQuery<Product>(
      query = { q -> q.range { r -> r.field("price").gte(JsonData.of(100)) } },
      index = "products"
    ) { /* ... */ }
  }
}
```

### Bulk / admin (raw client)

```kotlin
stove {
  elasticsearch {
    client().bulk { b ->
      b.operations(/* ... */)
    }

    client().indices().create { it.index("orders") }
  }
}
```

## Migrations

```kotlin
class CreateProductsIndex : DatabaseMigration<ElasticsearchClient> {
  override val order = 1
  override suspend fun execute(client: ElasticsearchClient) {
    client.indices().create { it.index("products") }
  }
}

elasticsearch {
  ElasticsearchSystemOptions(/* ... */).migrations {
    register<CreateProductsIndex>()
  }
}
```

## Pitfalls

| Symptom | Fix |
|---|---|
| `unauthorized` | Set `disableSecurity = true` for local tests, or pass `password` |
| Document not visible right after `save` | ES is near-real-time; `client().indices().refresh()` or assert with timeout |
| Wrong index name | Mirror your app's index naming (prefix with run ID for shared infra) |

## Pairs well with

- [Provided Instances](11-provided-instances.md) for shared ES (prefix index names)
- [Recipes](../recipes/index.md) for multi-system flows
