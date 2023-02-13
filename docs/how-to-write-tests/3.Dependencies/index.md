# Dependencies

All the dependencies are pluggable. Stove supports:

- [Http client](05-http.md)
- [Couchbase](01-couchbase.md)
- [Kafka](02-kafka.md)
- [Elasticsearch](03-elasticsearch.md)
- [Wiremock](04-wiremock.md)
- [Postgres Sql](06-postgresql.md)

and more to come.

There is a structure for a dependency to be pluggable in the Stove testing system. Every system connects or starts its 
life with `with` notation. For example, `withCouchbase`, `withHttpClient`, `withKafka`... That means, if you want plug
a physical dependency to your system look for a method that starts with `with` keyword. Having said that, when you write
your own system that Stove does not have, you should use this structure, too.

## Every system has the SystemOptions

Every system accepts has a first parameter that implements `SystemOptions`. For example;
```kotlin
fun TestSystem.withKafka(
    options: KafkaSystemOptions = KafkaSystemOptions(),
)
```
Here `KafkaSystemOptions` implements `SystemOptions` interface. 

If a system exposes its `run-time` configurations then it implements `ConfiguresExposedConfiguration`. This is applicable for
most of the cases. This mechanism allows you to configure your application _(system under test)_ args before it starts.

```kotlin hl_lines="4"
TestSystem()
    .withKafka(
        KafkaSystemOptions(configureExposedConfiguration = { cfg ->
            listOf("kafka.bootstrapServers=${cfg.boostrapServers}")
        })
    )
```
Highlighted list of configurations are passed to the application's `main(args)` function.
