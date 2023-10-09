# CHANGELOG

## [0.5.0](https://github.com/Trendyol/stove4k/releases/tag/0.5.0) - 2023-10-09 08:19:57

## What's Changed

### Breaking Changes
* Support for assertion Key, Headers and Topic of a Kafka Message by @osoykan in https://github.com/Trendyol/stove4k/pull/215

With this change,

* You will have breaking changes. Kafka assertions are unified.

`shouldBeConsumedOnCondition` -> `shouldBeConsumed`
`shouldBePublishedOnCondition` -> `shouldBePublished`

* You don't need to write `actual ->` when you start an assertion, it is already in the scope and accessible with the same name.
Easiest way of making it work is just deleting the arrow(`actual ->`).

* You can also assert your headers and keys for the messages that you've published.
```kotlin
 kafka {
     publish("trendyol.stove.service.product.create.0", createProductCommand)
     shouldBePublished<ProductCreatedEvent> {
         actual.id == createProductCommand.id &&
             actual.name == createProductCommand.name &&
             actual.supplierId == createProductCommand.supplierId &&
             metadata.headers["X-UserEmail"] == "stove@trendyol.com"
     }
 }
```

### Package Bumps
* fix(deps): update dependency com.couchbase.client:java-client to v3.4.10 by @renovate in https://github.com/Trendyol/stove4k/pull/211
* fix(deps): update dependency com.couchbase.client:metrics-micrometer to v0.4.10 by @renovate in https://github.com/Trendyol/stove4k/pull/212
* fix(deps): update dependency org.wiremock:wiremock-standalone to v3.0.4 by @renovate in https://github.com/Trendyol/stove4k/pull/213
* simplify the api of systems and introcude deprecations by @osoykan in https://github.com/Trendyol/stove4k/pull/214
* fix(deps): update dependency co.elastic.clients:elasticsearch-java to v8.10.0 by @renovate in https://github.com/Trendyol/stove4k/pull/217
* fix(deps): update dependency io.projectreactor:reactor-core to v3.5.10 by @renovate in https://github.com/Trendyol/stove4k/pull/216
* fix(deps): update koin to v3.5.0 by @renovate in https://github.com/Trendyol/stove4k/pull/218
* fix(deps): update spring core to v5.3.30 by @renovate in https://github.com/Trendyol/stove4k/pull/219
* fix(deps): update koin to v3.5.1 by @renovate in https://github.com/Trendyol/stove4k/pull/220
* fix(deps): update dependency org.wiremock:wiremock-standalone to v3.1.0 by @renovate in https://github.com/Trendyol/stove4k/pull/221
* fix(deps): update dependency co.elastic.clients:elasticsearch-java to v8.10.1 by @renovate in https://github.com/Trendyol/stove4k/pull/222
* chore(deps): update spring boot to v2.7.16 by @renovate in https://github.com/Trendyol/stove4k/pull/223
* fix(deps): update dependency co.elastic.clients:elasticsearch-java to v8.10.2 by @renovate in https://github.com/Trendyol/stove4k/pull/224
* chore(deps): update gradle/gradle-build-action action to v2.8.1 by @renovate in https://github.com/Trendyol/stove4k/pull/225
* fix(deps): update dependency org.wiremock:wiremock-standalone to v3.2.0 by @renovate in https://github.com/Trendyol/stove4k/pull/226
* chore(deps): update gradle/gradle-build-action action to v2.9.0 by @renovate in https://github.com/Trendyol/stove4k/pull/227
* fix(deps): update testcontainers-java monorepo to v1.19.1 by @renovate in https://github.com/Trendyol/stove4k/pull/228
* fix(deps): update dependency org.apache.kafka:kafka-clients to v3.6.0 by @renovate in https://github.com/Trendyol/stove4k/pull/229
* chore(deps): update dependency gradle to v8.4 by @renovate in https://github.com/Trendyol/stove4k/pull/232
* fix(deps): update dependency com.couchbase.client:metrics-micrometer to v0.4.11 by @renovate in https://github.com/Trendyol/stove4k/pull/231
* fix(deps): update dependency com.couchbase.client:java-client to v3.4.11 by @renovate in https://github.com/Trendyol/stove4k/pull/230
* fix(deps): update ktor to v2.3.5 by @renovate in https://github.com/Trendyol/stove4k/pull/233
* chore(deps): update plugin kotlinter to v4 by @renovate in https://github.com/Trendyol/stove4k/pull/234


**Full Changelog**: https://github.com/Trendyol/stove4k/compare/0.4.0...0.5.0

### Bug Fixes

- deps:
  - update ktor to v2.3.5 (#233) ([0b3bbbd](https://github.com/Trendyol/stove4k/commit/0b3bbbd785971d3bf3290bd35c1a3edb50cbc978)) ([#233](https://github.com/Trendyol/stove4k/pull/233))
  - update dependency com.couchbase.client:java-client to v3.4.11 (#230) ([297ffc1](https://github.com/Trendyol/stove4k/commit/297ffc171d085d481cfef850e56e3de9a5f41dd3)) ([#230](https://github.com/Trendyol/stove4k/pull/230))
  - update dependency com.couchbase.client:metrics-micrometer to v0.4.11 (#231) ([767c6ea](https://github.com/Trendyol/stove4k/commit/767c6eaa8c9899f29a059e9b2f31743a57484441)) ([#231](https://github.com/Trendyol/stove4k/pull/231))
  - update dependency org.apache.kafka:kafka-clients to v3.6.0 (#229) ([7e95ee1](https://github.com/Trendyol/stove4k/commit/7e95ee13b6ccfebaf78afe1c583f1989ace38de3)) ([#229](https://github.com/Trendyol/stove4k/pull/229))
  - update testcontainers-java monorepo to v1.19.1 (#228) ([1dc9448](https://github.com/Trendyol/stove4k/commit/1dc9448aae553ee11f400654efd850e9a2f2aa2e)) ([#228](https://github.com/Trendyol/stove4k/pull/228))
  - update dependency org.wiremock:wiremock-standalone to v3.2.0 (#226) ([e15c573](https://github.com/Trendyol/stove4k/commit/e15c5737650c2988a302ea1652c6d84aa981b874)) ([#226](https://github.com/Trendyol/stove4k/pull/226))
  - update dependency co.elastic.clients:elasticsearch-java to v8.10.2 (#224) ([af357dc](https://github.com/Trendyol/stove4k/commit/af357dcf57d702e750e3b789c9a9412177f2727f)) ([#224](https://github.com/Trendyol/stove4k/pull/224))
  - update dependency co.elastic.clients:elasticsearch-java to v8.10.1 (#222) ([5b367f1](https://github.com/Trendyol/stove4k/commit/5b367f1f2fecf7092c53a4d1c3c40fbf3fc88ff5)) ([#222](https://github.com/Trendyol/stove4k/pull/222))
  - update dependency org.wiremock:wiremock-standalone to v3.1.0 (#221) ([d0ec933](https://github.com/Trendyol/stove4k/commit/d0ec933627966bcc613b98c96240a381a44e73df)) ([#221](https://github.com/Trendyol/stove4k/pull/221))
  - update koin to v3.5.1 (#220) ([fc92a30](https://github.com/Trendyol/stove4k/commit/fc92a302b95977b00f3cedf72854aec6a7b19cb4)) ([#220](https://github.com/Trendyol/stove4k/pull/220))
  - update spring core to v5.3.30 (#219) ([8a7db71](https://github.com/Trendyol/stove4k/commit/8a7db712093695d7e33350af2425ab5eb63a7362)) ([#219](https://github.com/Trendyol/stove4k/pull/219))
  - update koin to v3.5.0 (#218) ([6e5b9ae](https://github.com/Trendyol/stove4k/commit/6e5b9ae91cc98318a0f0909703019acf5495938b)) ([#218](https://github.com/Trendyol/stove4k/pull/218))
  - update dependency io.projectreactor:reactor-core to v3.5.10 (#216) ([38e739e](https://github.com/Trendyol/stove4k/commit/38e739e039704e33ce29c53179d3ca6f639e4e2d)) ([#216](https://github.com/Trendyol/stove4k/pull/216))
  - update dependency co.elastic.clients:elasticsearch-java to v8.10.0 (#217) ([dddfff9](https://github.com/Trendyol/stove4k/commit/dddfff9b6dc1a57738703a5f350210a0276167da)) ([#217](https://github.com/Trendyol/stove4k/pull/217))
  - update dependency org.wiremock:wiremock-standalone to v3.0.4 (#213) ([e0bdc9a](https://github.com/Trendyol/stove4k/commit/e0bdc9a58955618e86373357829070fa6d4745cb)) ([#213](https://github.com/Trendyol/stove4k/pull/213))
  - update dependency com.couchbase.client:metrics-micrometer to v0.4.10 (#212) ([2f02a37](https://github.com/Trendyol/stove4k/commit/2f02a37cbbd3d1645f1957e5a445491ae839bc4c)) ([#212](https://github.com/Trendyol/stove4k/pull/212))
  - update dependency com.couchbase.client:java-client to v3.4.10 (#211) ([f6071ef](https://github.com/Trendyol/stove4k/commit/f6071ef826b4c5962464c028c393c17b98a35e2b)) ([#211](https://github.com/Trendyol/stove4k/pull/211))

## [0.4.0](https://github.com/Trendyol/stove4k/releases/tag/0.4.0) - 2023-09-07 12:24:45

## [0.3.3](https://github.com/Trendyol/stove4k/releases/tag/0.3.3) - 2023-08-16 07:59:30

## [0.3.2](https://github.com/Trendyol/stove4k/releases/tag/0.3.2) - 2023-08-07 07:57:33

## [0.3.1](https://github.com/Trendyol/stove4k/releases/tag/0.3.1) - 2023-08-03 09:54:08

## [0.3.0](https://github.com/Trendyol/stove4k/releases/tag/0.3.0) - 2023-07-27 09:00:53

## [0.2.2](https://github.com/Trendyol/stove4k/releases/tag/0.2.2) - 2023-07-07 14:18:09

**Full Changelog**: https://github.com/Trendyol/stove4k/compare/0.2.1...0.2.2

### Bug Fixes

- general:
  - fix tests ([45ba431](https://github.com/Trendyol/stove4k/commit/45ba4319557d1820edd0ccc4bf9310f90eeb97bc))

## [0.2.1](https://github.com/Trendyol/stove4k/releases/tag/0.2.1) - 2023-07-07 12:36:02

## [0.2.0](https://github.com/Trendyol/stove4k/releases/tag/0.2.0) - 2023-07-07 10:16:25

## [0.1.1](https://github.com/Trendyol/stove4k/releases/tag/0.1.1) - 2023-05-26 08:12:51

## [0.1.0](https://github.com/Trendyol/stove4k/releases/tag/0.1.0) - 2023-05-23 16:35:42

Stove promoted to maven release repository!

## [0.0.15-SNAPSHOT](https://github.com/Trendyol/stove4k/releases/tag/0.0.15-SNAPSHOT) - 2023-05-22 14:10:55

## [0.0.14-SNAPSHOT](https://github.com/Trendyol/stove4k/releases/tag/0.0.14-SNAPSHOT) - 2023-03-28 14:26:44

## [0.0.13-SNAPSHOT](https://github.com/Trendyol/stove4k/releases/tag/0.0.13-SNAPSHOT) - 2023-03-15 16:17:42

## [0.0.12-SNAPSHOT](https://github.com/Trendyol/stove4k/releases/tag/0.0.12-SNAPSHOT) - 2023-02-15 16:39:52

**Full Changelog**: https://github.com/Trendyol/stove4k/compare/0.0.11-SNAPSHOT...0.0.12-SNAPSHOT

### Bug Fixes

- general:
  - fix when container is not 8.x while creating sslcontext ([af4d6ab](https://github.com/Trendyol/stove4k/commit/af4d6ab5c3c957ce2d6cc86a6c5893088ac9877b))

## [0.0.11-SNAPSHOT](https://github.com/Trendyol/stove4k/releases/tag/0.0.11-SNAPSHOT) - 2023-02-14 15:54:40

## [0.0.9-SNAPSHOT](https://github.com/Trendyol/stove4k/releases/tag/0.0.9-SNAPSHOT) - 2023-02-14 09:12:35

## [0.0.8-SNAPSHOT](https://github.com/Trendyol/stove4k/releases/tag/0.0.8-SNAPSHOT) - 2023-02-02 17:16:19

## [0.0.7-SNAPSHOT](https://github.com/Trendyol/stove4k/releases/tag/0.0.7-SNAPSHOT) - 2023-01-25 13:34:36

\* *This CHANGELOG was automatically generated by [auto-generate-changelog](https://github.com/BobAnkh/auto-generate-changelog)*
