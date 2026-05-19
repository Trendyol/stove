# Couchbase

Real Couchbase in a container or wired to an existing cluster. KV ops, N1QL queries, multi-collection support.

<a class="open-in-wizard" data-sys="couchbase">Open in setup wizard</a>

<!--{wizard:snippet id=sys.couchbase parts=gradle,configure,test}-->

<div class="stove-tldr" markdown>
<span class="stove-tldr-title">In 30 seconds</span>
Register <code>couchbase { CouchbaseSystemOptions(bucketName = "...", ...) }</code>. Use <code>save()</code> / <code>shouldGet&lt;T&gt;()</code> / <code>shouldDelete()</code> / <code>shouldQuery&lt;T&gt;(n1ql)</code>. Bucket name must match what your app reads. Collection-aware: pass collection to scope KV ops.
</div>

## Configure

```kotlin
Stove().with {
  couchbase {
    CouchbaseSystemOptions(
      bucketName = "testbucket",
      configureExposedConfiguration = { cfg ->
        listOf(
          "couchbase.hosts=${cfg.hostsWithPort}",
          "couchbase.username=${cfg.username}",
          "couchbase.password=${cfg.password}"
        )
      }
    )
  }
}.run()
```

## DSL

### KV ops

```kotlin
stove {
  couchbase {
    save("doc-1", Product(id = "1", name = "Laptop"), collection = "products")
    saveToDefaultCollection("u-1", User(id = "1"))

    shouldGet<Product>("doc-1", collection = "products") {
      it.name shouldBe "Laptop"
    }

    shouldDelete("doc-1", collection = "products")
    shouldNotExist("doc-1", collection = "products")
  }
}
```

### N1QL query

```kotlin
stove {
  couchbase {
    shouldQuery<Product>(
      "SELECT META().id, p.* FROM `testbucket` p WHERE p.type = 'product'"
    ) { products ->
      products shouldHaveSize 3
    }
  }
}
```

### Pause / unpause (container mode)

```kotlin
couchbase {
  pause()    // simulate downtime
  unpause()
}
```

## Migrations

```kotlin
class SeedProducts : DatabaseMigration<Cluster> {
  override val order = 1
  override suspend fun execute(cluster: Cluster) {
    cluster.query("CREATE PRIMARY INDEX ON `testbucket`")
  }
}

couchbase {
  CouchbaseSystemOptions(/* ... */).migrations {
    register<SeedProducts>()
  }
}
```

## Pitfalls

| Symptom | Fix |
|---|---|
| `BucketNotFoundException` | App uses different bucket; mirror `bucketName` exactly |
| N1QL returns empty | Index missing; add `CREATE PRIMARY INDEX` migration |
| `Collection not found` | Pass collection arg; default collection is `_default` |

## Pairs well with

- [Provided Instances](11-provided-instances.md) for shared CB clusters
- [Recipes](../recipes/index.md) for multi-system flows
