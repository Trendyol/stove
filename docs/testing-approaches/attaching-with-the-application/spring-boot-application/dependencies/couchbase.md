# Couchbase

```kotlin
.withCouchbase(bucket = "Stove") { cfg ->
    listOf(
        "couchbase.hosts=${cfg.hostsWithPort}",
        "couchbase.username=${cfg.username}",
        "couchbase.password=${cfg.password}"
    )
}
```

!!! note
    The Couchbase configuration
    name in the `application.yml` is `couchbase.hosts`, this might differ for your project.

Stove exposes the generated configuration by the execution,
so you can pass the real connection strings and configurations to your Spring application before it starts.
So, your application will start with the physical dependencies that are spun-up by the framework.
