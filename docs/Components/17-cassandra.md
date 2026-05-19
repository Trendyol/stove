# Cassandra

Real Cassandra in a container or wired to an existing cluster. CQL execute/query, prepared statements via raw `session()`, keyspace migrations, pause/unpause.

<a class="open-in-wizard" data-sys="cassandra">Open in setup wizard</a>

<!--{wizard:snippet id=sys.cassandra parts=gradle,configure,test}-->

<div class="stove-tldr" markdown>
<span class="stove-tldr-title">In 30 seconds</span>
Register <code>cassandra { CassandraSystemOptions(keyspace, datacenter, ...) }</code>. <code>shouldExecute(cql)</code> and <code>shouldQuery(cql) { resultSet -> }</code>. Use <code>session()</code> for prepared / bound statements. Create keyspace and tables in a <code>CassandraMigration</code>.
</div>

## Configure

```kotlin
Stove().with {
  cassandra {
    CassandraSystemOptions(
      keyspace = "testks",
      datacenter = "datacenter1",
      configureExposedConfiguration = { cfg ->
        listOf(
          "cassandra.contact-points=${cfg.contactPoints}",
          "cassandra.keyspace=${cfg.keyspace}",
          "cassandra.datacenter=${cfg.datacenter}"
        )
      }
    )
  }
}.run()
```

## Migrations

```kotlin
class CreateKeyspaceAndTable : CassandraMigration {
  override val order = 1

  override suspend fun execute(session: CqlSession) {
    session.execute(
      """
      CREATE KEYSPACE IF NOT EXISTS testks
      WITH replication = {'class':'SimpleStrategy', 'replication_factor':1}
      """.trimIndent()
    )
    session.execute(
      """
      CREATE TABLE IF NOT EXISTS testks.events (
        id UUID PRIMARY KEY,
        type TEXT,
        ts TIMESTAMP
      )
      """.trimIndent()
    )
  }
}

cassandra {
  CassandraSystemOptions(/* ... */).migrations {
    register<CreateKeyspaceAndTable>()
  }
}
```

## DSL

### Execute + query

```kotlin
stove {
  cassandra {
    shouldExecute("INSERT INTO testks.events (id, type, ts) VALUES (uuid(), 'created', toTimestamp(now()))")

    shouldQuery("SELECT * FROM testks.events WHERE type = 'created' ALLOW FILTERING") { rs ->
      val rows = rs.all()
      rows shouldHaveSize 1
    }
  }
}
```

### Prepared / bound statements

```kotlin
stove {
  cassandra {
    val stmt = session().prepare("INSERT INTO testks.events (id, type, ts) VALUES (?, ?, ?)")
    session().execute(stmt.bind(UUID.randomUUID(), "shipped", Instant.now()))
  }
}
```

### Cleanup hook

```kotlin
cassandra {
  CassandraSystemOptions(
    keyspace = "testks",
    cleanup = { session -> session.execute("TRUNCATE testks.events") },
    /* ... */
  )
}
```

### Pause / unpause (container mode)

```kotlin
cassandra { pause(); unpause() }
```

## Pitfalls

| Symptom | Fix |
|---|---|
| `Cannot achieve consistency level` | Replication factor 1 in dev; raise only when you're testing multi-DC |
| Query needs `ALLOW FILTERING` | Add a secondary index or use the partition key; not for production but fine in tests |
| `KeyspaceNotFound` | Create in a migration before tests run |

## Pairs well with

- [Provided Instances](11-provided-instances.md) for shared CI clusters
- [Recipes](../recipes/index.md) for multi-system flows
