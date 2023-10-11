# CHANGELOG

## [0.5.0](https://github.com/Trendyol/stove4k/releases/tag/0.5.0) - 2023-10-09 08:19:57

## What's Changed

### Breaking Changes

* simplify the api of systems and introcude deprecations by @osoykan in https://github.com/Trendyol/stove4k/pull/214

### Kafka
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

## [0.4.0](https://github.com/Trendyol/stove4k/releases/tag/0.4.0) - 2023-09-08 07:45:25

## What's Changed
* chore(deps): update dependency gradle to v8.3 by @renovate in https://github.com/Trendyol/stove4k/pull/192
* fix(deps): update dependency co.elastic.clients:elasticsearch-java to v8.9.1 by @renovate in https://github.com/Trendyol/stove4k/pull/193
* fix(deps): update testcontainers-java monorepo to v1.19.0 by @renovate in https://github.com/Trendyol/stove4k/pull/195
* fix(deps): update kotest to v5.7.2 - autoclosed by @renovate in https://github.com/Trendyol/stove4k/pull/203
* chore(deps): update gradle/gradle-build-action action to v2.8.0 by @renovate in https://github.com/Trendyol/stove4k/pull/194
* fix(deps): update spring kafka to v2.9.12 by @renovate in https://github.com/Trendyol/stove4k/pull/196
* fix(deps): update kotlin monorepo to v1.9.10 by @renovate in https://github.com/Trendyol/stove4k/pull/197
* fix(deps): update dependency com.github.tomakehurst:wiremock-jre8 to v2.35.1 by @renovate in https://github.com/Trendyol/stove4k/pull/204
* chore(deps): update dependency org.jetbrains.kotlin.plugin.spring to v1.9.10 - autoclosed by @renovate in https://github.com/Trendyol/stove4k/pull/198
* fix(deps): update ktor to v2.3.4 by @renovate in https://github.com/Trendyol/stove4k/pull/201
* fix(deps): update dependency io.arrow-kt:arrow-core to v1.2.1 by @renovate in https://github.com/Trendyol/stove4k/pull/205
* chore(deps): update dependency org.jetbrains.dokka to v1.9.0 by @renovate in https://github.com/Trendyol/stove4k/pull/202
* chore(deps): update spring boot to v2.7.15 by @renovate in https://github.com/Trendyol/stove4k/pull/199
* chore(deps): update actions/checkout action to v4 by @renovate in https://github.com/Trendyol/stove4k/pull/207
* fix(deps): update slf4j to v2.0.9 - autoclosed by @renovate in https://github.com/Trendyol/stove4k/pull/206
* chore(deps): update mikepenz/action-junit-report action to v4 by @renovate in https://github.com/Trendyol/stove4k/pull/208
* fix(deps): update dependency com.github.tomakehurst:wiremock-jre8 to v3 by @renovate in https://github.com/Trendyol/stove4k/pull/200
* fix(deps): update dependency co.elastic.clients:elasticsearch-java to v8.9.2 by @renovate in https://github.com/Trendyol/stove4k/pull/209


**Full Changelog**: https://github.com/Trendyol/stove4k/compare/0.3.3...0.4.0

### Bug Fixes

- deps:
  - update dependency co.elastic.clients:elasticsearch-java to v8.9.2 (#209) ([fd2d5a3](https://github.com/Trendyol/stove4k/commit/fd2d5a3f87b26a8cd85427329b243463ad5d419f)) ([#209](https://github.com/Trendyol/stove4k/pull/209))
  - update dependency com.github.tomakehurst:wiremock-jre8 to v3 (#200) ([1f1ca59](https://github.com/Trendyol/stove4k/commit/1f1ca598af41e05fc8d75b0e4358b5ecc43e5548)) ([#200](https://github.com/Trendyol/stove4k/pull/200))
  - update slf4j to v2.0.9 (#206) ([131fd92](https://github.com/Trendyol/stove4k/commit/131fd9220633e2734474d65fec22a3e854c06783)) ([#206](https://github.com/Trendyol/stove4k/pull/206))
  - update dependency io.arrow-kt:arrow-core to v1.2.1 (#205) ([25bea77](https://github.com/Trendyol/stove4k/commit/25bea771d2dbab18ec867b8b1d9c36de5446ca25)) ([#205](https://github.com/Trendyol/stove4k/pull/205))
  - update ktor to v2.3.4 (#201) ([bc70a01](https://github.com/Trendyol/stove4k/commit/bc70a01e3f6550345dc08de473f4514e740b8e46)) ([#201](https://github.com/Trendyol/stove4k/pull/201))
  - update dependency com.github.tomakehurst:wiremock-jre8 to v2.35.1 (#204) ([3dd9de7](https://github.com/Trendyol/stove4k/commit/3dd9de74666611dfe50ed530ba4ef5e289fd81ec)) ([#204](https://github.com/Trendyol/stove4k/pull/204))
  - update kotlin monorepo to v1.9.10 (#197) ([c804614](https://github.com/Trendyol/stove4k/commit/c8046144d89b9564774bca039fce368d327483ad)) ([#197](https://github.com/Trendyol/stove4k/pull/197))
  - update spring kafka to v2.9.12 (#196) ([a49d17e](https://github.com/Trendyol/stove4k/commit/a49d17e11acd06f73185935c6e4d39ebe9bc0af9)) ([#196](https://github.com/Trendyol/stove4k/pull/196))
  - update kotest to v5.7.2 (#203) ([4914322](https://github.com/Trendyol/stove4k/commit/49143228aa0e5356570c498b771e36045fef525e)) ([#203](https://github.com/Trendyol/stove4k/pull/203))
  - update testcontainers-java monorepo to v1.19.0 (#195) ([7aa2140](https://github.com/Trendyol/stove4k/commit/7aa2140e94169aae07ea0477fd0865cdcafa4416)) ([#195](https://github.com/Trendyol/stove4k/pull/195))
  - update dependency co.elastic.clients:elasticsearch-java to v8.9.1 (#193) ([7768a16](https://github.com/Trendyol/stove4k/commit/7768a16d59d7f26da923c7ff3e0a0e32c0ecc502)) ([#193](https://github.com/Trendyol/stove4k/pull/193))

## [0.3.3](https://github.com/Trendyol/stove4k/releases/tag/0.3.3) - 2023-08-16 07:59:30

## What's Changed

### Changes
* Fix for Elasticsearch state capture when security enabled by @osoykan in https://github.com/Trendyol/stove4k/pull/191

### Deps
* fix(deps): update dependency org.mockito.kotlin:mockito-kotlin to v5.1.0 by @renovate in https://github.com/Trendyol/stove4k/pull/187
* chore(deps): update dependency io.spring.dependency-management to v1.1.3 by @renovate in https://github.com/Trendyol/stove4k/pull/188
* chore(deps): update plugin kotlinter to v3.16.0 by @renovate in https://github.com/Trendyol/stove4k/pull/189
* fix(deps): update dependency io.projectreactor:reactor-core to v3.5.9 by @renovate in https://github.com/Trendyol/stove4k/pull/190


**Full Changelog**: https://github.com/Trendyol/stove4k/compare/0.3.2...0.3.3

### Bug Fixes

- deps:
  - update dependency io.projectreactor:reactor-core to v3.5.9 (#190) ([c17ba77](https://github.com/Trendyol/stove4k/commit/c17ba77d2b984a23b05810daedf2f82a193527d9)) ([#190](https://github.com/Trendyol/stove4k/pull/190))
  - update dependency org.mockito.kotlin:mockito-kotlin to v5.1.0 (#187) ([9637e1e](https://github.com/Trendyol/stove4k/commit/9637e1e7cc81b07734aa551dec2de36bc2d3968c)) ([#187](https://github.com/Trendyol/stove4k/pull/187))

## [0.3.2](https://github.com/Trendyol/stove4k/releases/tag/0.3.2) - 2023-08-07 07:57:33

## What's Changed

### Features
* feature: add migration order to the migrations by @osoykan in https://github.com/Trendyol/stove4k/pull/184

### Deps
* fix(deps): update dependency com.couchbase.client:java-client to v3.4.9 by @renovate in https://github.com/Trendyol/stove4k/pull/185
* fix(deps): update dependency com.couchbase.client:metrics-micrometer to v0.4.9 by @renovate in https://github.com/Trendyol/stove4k/pull/186


**Full Changelog**: https://github.com/Trendyol/stove4k/compare/0.3.1...0.3.2

### Feature

- general:
  - feature: add migration order to the migrations (#184) ([682c2ec](https://github.com/Trendyol/stove4k/commit/682c2ec2e2bf853ea492aa566a926ba3a6dd4851)) ([#184](https://github.com/Trendyol/stove4k/pull/184))

### Bug Fixes

- deps:
  - update dependency com.couchbase.client:metrics-micrometer to v0.4.9 (#186) ([aa2ab16](https://github.com/Trendyol/stove4k/commit/aa2ab16a247c0ac586c05d6233a4d384f81c8d66)) ([#186](https://github.com/Trendyol/stove4k/pull/186))
  - update dependency com.couchbase.client:java-client to v3.4.9 (#185) ([0575221](https://github.com/Trendyol/stove4k/commit/057522149d956ef23a7f831666fad30ca7866970)) ([#185](https://github.com/Trendyol/stove4k/pull/185))

## [0.3.1](https://github.com/Trendyol/stove4k/releases/tag/0.3.1) - 2023-08-03 10:38:25

## What's Changed

### Changes
* Update build.yml by @osoykan in https://github.com/Trendyol/stove4k/pull/182

### Deps
* chore(deps): update dependency org.jetbrains.kotlinx.kover to v0.7.3 by @renovate in https://github.com/Trendyol/stove4k/pull/178
* fix(deps): update dependency co.elastic.clients:elasticsearch-java to v8.9.0 by @renovate in https://github.com/Trendyol/stove4k/pull/177
* fix(deps): update koin to v3.4.3 by @renovate in https://github.com/Trendyol/stove4k/pull/179
* chore(deps): update madrapps/jacoco-report action to v1.6 by @renovate in https://github.com/Trendyol/stove4k/pull/180
* fix(deps): update ktor to v2.3.3 by @renovate in https://github.com/Trendyol/stove4k/pull/181


**Full Changelog**: https://github.com/Trendyol/stove4k/compare/0.3.0...0.3.1

### Bug Fixes

- deps:
  - make rdbms deps open to users ([eb024a2](https://github.com/Trendyol/stove4k/commit/eb024a28d899dfefe35d96f22b329cffdf8b2d7c))
  - update ktor to v2.3.3 (#181) ([4b412ab](https://github.com/Trendyol/stove4k/commit/4b412ab30a319467106bc5c30322119611cecb6e)) ([#181](https://github.com/Trendyol/stove4k/pull/181))
  - update koin to v3.4.3 (#179) ([db55c7d](https://github.com/Trendyol/stove4k/commit/db55c7d59b1c46626d4078b967e3d000a56be03e)) ([#179](https://github.com/Trendyol/stove4k/pull/179))
  - update dependency co.elastic.clients:elasticsearch-java to v8.9.0 (#177) ([4a535f7](https://github.com/Trendyol/stove4k/commit/4a535f719d5f26a3daead7baacf39ccb722ca30d)) ([#177](https://github.com/Trendyol/stove4k/pull/177))

## [0.3.0](https://github.com/Trendyol/stove4k/releases/tag/0.3.0) - 2023-07-27 09:00:53

## What's Changed

### Features
* Support for bridging app to the validation context by @osoykan in https://github.com/Trendyol/stove4k/pull/176, also thanks to @oguzhaneren
* add headers to http requests by @osoykan in https://github.com/Trendyol/stove4k/pull/172

### Deps
* Update dependency gradle to v8.2.1 by @renovate in https://github.com/Trendyol/stove4k/pull/152
* Update gradle/gradle-build-action action to v2.6.0 by @renovate in https://github.com/Trendyol/stove4k/pull/153
* Update dependency org.mongodb:mongodb-driver-reactivestreams to v4.10.2 by @renovate in https://github.com/Trendyol/stove4k/pull/154
* Update dependency io.projectreactor:reactor-core to v3.5.8 by @renovate in https://github.com/Trendyol/stove4k/pull/155
* Update dependency io.spring.dependency-management to v1.1.1 by @renovate in https://github.com/Trendyol/stove4k/pull/158
* Update dependency io.arrow-kt:arrow-core to v1.2.0 by @renovate in https://github.com/Trendyol/stove4k/pull/157
* fix(deps): update spring core to v5.3.29 by @renovate in https://github.com/Trendyol/stove4k/pull/161
* chore(deps): update plugin ktfmt to v0.13.0 by @renovate in https://github.com/Trendyol/stove4k/pull/163
* chore(deps): update dependency io.spring.dependency-management to v1.1.2 by @renovate in https://github.com/Trendyol/stove4k/pull/164
* chore(deps): update gradle/gradle-build-action action to v2.6.1 by @renovate in https://github.com/Trendyol/stove4k/pull/166
* fix(deps): update spring kafka to v2.9.10 by @renovate in https://github.com/Trendyol/stove4k/pull/165
* fix(deps): update dependency com.couchbase.client:metrics-micrometer to v0.4.8 by @renovate in https://github.com/Trendyol/stove4k/pull/168
* fix(deps): update dependency com.couchbase.client:java-client to v3.4.8 by @renovate in https://github.com/Trendyol/stove4k/pull/167
* chore(deps): update spring boot to v2.7.14 by @renovate in https://github.com/Trendyol/stove4k/pull/169
* fix(deps): update dependency org.apache.kafka:kafka-clients to v3.5.1 by @renovate in https://github.com/Trendyol/stove4k/pull/170
* fix(deps): update junit5 monorepo to v5.10.0 by @renovate in https://github.com/Trendyol/stove4k/pull/171
* chore(deps): update gradle/gradle-build-action action to v2.7.0 by @renovate in https://github.com/Trendyol/stove4k/pull/173
* chore(deps): update madrapps/jacoco-report action to v1.5 by @renovate in https://github.com/Trendyol/stove4k/pull/174
* fix(deps): update kotlinx to v1.7.3 by @renovate in https://github.com/Trendyol/stove4k/pull/175


**Full Changelog**: https://github.com/Trendyol/stove4k/compare/0.2.2...0.3.0

### Bug Fixes

- deps:
  - update kotlinx to v1.7.3 (#175) ([32bfb4b](https://github.com/Trendyol/stove4k/commit/32bfb4b34fb3c1c8a7ae3de18cc4336fd5e944ed)) ([#175](https://github.com/Trendyol/stove4k/pull/175))
  - update junit5 monorepo to v5.10.0 (#171) ([d10bd4b](https://github.com/Trendyol/stove4k/commit/d10bd4bdde73123684a59470ef171f49fd2a5199)) ([#171](https://github.com/Trendyol/stove4k/pull/171))
  - update dependency org.apache.kafka:kafka-clients to v3.5.1 (#170) ([635df6e](https://github.com/Trendyol/stove4k/commit/635df6e742bea09143e24ebc0c96422a3e327a92)) ([#170](https://github.com/Trendyol/stove4k/pull/170))
  - update dependency com.couchbase.client:java-client to v3.4.8 (#167) ([ea59f6a](https://github.com/Trendyol/stove4k/commit/ea59f6a4ad5dd6429572eb3fbbec4630de2a4bab)) ([#167](https://github.com/Trendyol/stove4k/pull/167))
  - update dependency com.couchbase.client:metrics-micrometer to v0.4.8 (#168) ([e14171b](https://github.com/Trendyol/stove4k/commit/e14171b8f99b7c12619dca38f0c7cd74d0facce3)) ([#168](https://github.com/Trendyol/stove4k/pull/168))
  - update spring kafka to v2.9.10 (#165) ([dc27942](https://github.com/Trendyol/stove4k/commit/dc2794210aeb7ba40d1312aaa05b85f111b76525)) ([#165](https://github.com/Trendyol/stove4k/pull/165))
  - update spring core to v5.3.29 (#161) ([c0b8bae](https://github.com/Trendyol/stove4k/commit/c0b8baeb60969b8781c42e4bbd555d2feb628c87)) ([#161](https://github.com/Trendyol/stove4k/pull/161))

- general:
  - arrow breaking changes ([3aae35f](https://github.com/Trendyol/stove4k/commit/3aae35fa65952392b4ab3b3a54c981ac2c117e7b)) ([#157](https://github.com/Trendyol/stove4k/pull/157))

## [0.2.2](https://github.com/Trendyol/stove4k/releases/tag/0.2.2) - 2023-07-07 14:18:09

**Full Changelog**: https://github.com/Trendyol/stove4k/compare/0.2.1...0.2.2

### Bug Fixes

- general:
  - fix tests ([45ba431](https://github.com/Trendyol/stove4k/commit/45ba4319557d1820edd0ccc4bf9310f90eeb97bc))

## [0.2.1](https://github.com/Trendyol/stove4k/releases/tag/0.2.1) - 2023-07-07 12:36:02

## What's New

* Extensions on HttpSystem are cleaned up

**Full Changelog**: https://github.com/Trendyol/stove4k/compare/0.2.0...0.2.1

## [0.2.0](https://github.com/Trendyol/stove4k/releases/tag/0.2.0) - 2023-07-07 10:16:25

## What's Changed

### Enhancements
* Interface abstractions removed by @osoykan in https://github.com/Trendyol/stove4k/pull/136

### Package Updates
* Update jackson to v2.15.2 by @renovate in https://github.com/Trendyol/stove4k/pull/121
* Update madrapps/jacoco-report action to v1.4 - autoclosed by @renovate in https://github.com/Trendyol/stove4k/pull/122
* Update ktor to v2.3.1 by @renovate in https://github.com/Trendyol/stove4k/pull/123
* Update koin to v3.4.1 by @renovate in https://github.com/Trendyol/stove4k/pull/124
* Update dependency org.jetbrains.kotlinx.kover to v0.7.1 by @renovate in https://github.com/Trendyol/stove4k/pull/125
* Update dependency org.mockito.kotlin:mockito-kotlin to v5 - autoclosed by @renovate in https://github.com/Trendyol/stove4k/pull/126
* Update dependency org.apache.kafka:kafka-clients to v3.4.1 by @renovate in https://github.com/Trendyol/stove4k/pull/128
* Update dependency org.jetbrains.dokka to v1.8.20 by @renovate in https://github.com/Trendyol/stove4k/pull/127
* Update plugin ktlint to v11.4.0 by @renovate in https://github.com/Trendyol/stove4k/pull/129
* Update kotlin monorepo to v1.8.22 by @renovate in https://github.com/Trendyol/stove4k/pull/131
* Update dependency org.jetbrains.kotlin.plugin.spring to v1.8.22 by @renovate in https://github.com/Trendyol/stove4k/pull/130
* Update dependency co.elastic.clients:elasticsearch-java to v8.8.1 by @renovate in https://github.com/Trendyol/stove4k/pull/132
* Update dependency com.couchbase.client:java-client to v3.4.7 by @renovate in https://github.com/Trendyol/stove4k/pull/133
* Update dependency com.couchbase.client:metrics-micrometer to v0.4.7 by @renovate in https://github.com/Trendyol/stove4k/pull/134
* Update dependency io.projectreactor:reactor-core to v3.5.7 by @renovate in https://github.com/Trendyol/stove4k/pull/137
* Update spring core to v5.3.28 by @renovate in https://github.com/Trendyol/stove4k/pull/138
* Update dependency org.mongodb:mongodb-driver-reactivestreams to v4.10.0 by @renovate in https://github.com/Trendyol/stove4k/pull/140
* Update dependency org.apache.kafka:kafka-clients to v3.5.0 by @renovate in https://github.com/Trendyol/stove4k/pull/139
* Update spring boot to v2.7.13 by @renovate in https://github.com/Trendyol/stove4k/pull/141
* Update dependency org.mongodb:mongodb-driver-reactivestreams to v4.10.1 by @renovate in https://github.com/Trendyol/stove4k/pull/142
* Update dependency org.jetbrains.kotlinx.kover to v0.7.2 by @renovate in https://github.com/Trendyol/stove4k/pull/143
* Update gradle/gradle-build-action action to v2.5.0 by @renovate in https://github.com/Trendyol/stove4k/pull/145
* Update ktor to v2.3.2 by @renovate in https://github.com/Trendyol/stove4k/pull/144
* Update kotlinx to v1.7.2 by @renovate in https://github.com/Trendyol/stove4k/pull/147
* Update dependency gradle to v8.2 by @renovate in https://github.com/Trendyol/stove4k/pull/149
* Update gradle/gradle-build-action action to v2.5.1 by @renovate in https://github.com/Trendyol/stove4k/pull/148
* Update dependency co.elastic.clients:elasticsearch-java to v8.8.2 by @renovate in https://github.com/Trendyol/stove4k/pull/146
* Update kotlin monorepo to v1.9.0 by @renovate in https://github.com/Trendyol/stove4k/pull/151
* Update dependency org.jetbrains.kotlin.plugin.spring to v1.9.0 - autoclosed by @renovate in https://github.com/Trendyol/stove4k/pull/150


**Full Changelog**: https://github.com/Trendyol/stove4k/compare/0.1.1...0.2.0

## [0.1.1](https://github.com/Trendyol/stove4k/releases/tag/0.1.1) - 2023-05-26 08:12:51

## What's Changed
* Update dependency co.elastic.clients:elasticsearch-java to v8.8.0 by @renovate in https://github.com/Trendyol/stove4k/pull/117
* es support for index #118 by @osoykan in https://github.com/Trendyol/stove4k/pull/119


**Full Changelog**: https://github.com/Trendyol/stove4k/compare/0.1.0...0.1.1

## [0.1.0](https://github.com/Trendyol/stove4k/releases/tag/0.1.0) - 2023-05-23 16:35:42

Stove promoted to maven release repository!

## [0.0.15-SNAPSHOT](https://github.com/Trendyol/stove4k/releases/tag/0.0.15-SNAPSHOT) - 2023-05-22 14:10:55

## What's Changed

### New Features
* Validation Dsl created #73 by @osoykan in https://github.com/Trendyol/stove4k/pull/81
* Add ability to validate Kafka failing consumers/messages by @osoykan in https://github.com/Trendyol/stove4k/pull/82
* Create a WithDsl for constructing/configuring the entire system by @osoykan in https://github.com/Trendyol/stove4k/pull/88
* add deleteAndExpectBodilessResponse method to httpSystem by @Bramix in https://github.com/Trendyol/stove4k/pull/92
* Fix get many from http system by @Bramix in https://github.com/Trendyol/stove4k/pull/101
* shouldNotGet function added for the CouchbaseSystem by @AksalBilal in https://github.com/Trendyol/stove4k/pull/98
* Provide a way to change kafka container tag and image by @osoykan in https://github.com/Trendyol/stove4k/pull/105

### Library Updates
* Update plugin gitVersioning to v3 by @renovate in https://github.com/Trendyol/stove4k/pull/64
* Update all non-major dependencies by @renovate in https://github.com/Trendyol/stove4k/pull/65
* Update all non-major dependencies to v1.18.0 by @renovate in https://github.com/Trendyol/stove4k/pull/67
* Update dependency org.mongodb:mongodb-driver-reactivestreams to v4.9.1 by @renovate in https://github.com/Trendyol/stove4k/pull/68
* Update dependency io.projectreactor:reactor-core to v3.5.5 by @renovate in https://github.com/Trendyol/stove4k/pull/69
* Update dependency com.couchbase.client:metrics-micrometer to v0.4.5 by @renovate in https://github.com/Trendyol/stove4k/pull/75
* Update spring kafka to v2.9.8 by @renovate in https://github.com/Trendyol/stove4k/pull/76
* Update gradle/gradle-build-action action to v2.4.2 by @renovate in https://github.com/Trendyol/stove4k/pull/79
* Update dependency gradle to v8.1 by @renovate in https://github.com/Trendyol/stove4k/pull/77
* Update kotest to v5.6.1 by @renovate in https://github.com/Trendyol/stove4k/pull/78
* Update spring core to v5.3.27 by @renovate in https://github.com/Trendyol/stove4k/pull/80
* Update dependency com.couchbase.client:java-client to v3.4.5 by @renovate in https://github.com/Trendyol/stove4k/pull/74
* Update actions/checkout action to v3 by @renovate in https://github.com/Trendyol/stove4k/pull/85
* Update ktor to v2.3.0 by @renovate in https://github.com/Trendyol/stove4k/pull/83
* Update spring boot to v2.7.11 by @renovate in https://github.com/Trendyol/stove4k/pull/86
* Update dependency org.springframework.boot:spring-boot-starter-test to v2.7.11 by @renovate in https://github.com/Trendyol/stove4k/pull/87
* Update dependency co.elastic.clients:elasticsearch-java to v8.7.1 by @renovate in https://github.com/Trendyol/stove4k/pull/102
* Update dependency com.couchbase.client:metrics-micrometer to v0.4.6 by @renovate in https://github.com/Trendyol/stove4k/pull/104
* Update dependency com.couchbase.client:java-client to v3.4.6 by @renovate in https://github.com/Trendyol/stove4k/pull/103
* Update kotlinx to v1.7.0 by @renovate in https://github.com/Trendyol/stove4k/pull/106

## [0.0.14-SNAPSHOT](https://github.com/Trendyol/stove4k/releases/tag/0.0.14-SNAPSHOT) - 2023-03-28 14:26:44

## What's Changed
* Add support for keeping dependencies running when test are finished to speed up the next run (on-local) by @osoykan in https://github.com/Trendyol/stove4k/pull/55
* Update all non-major dependencies to v2.0.7 by @renovate in https://github.com/Trendyol/stove4k/pull/56
* Update dependency org.springframework:spring-beans to v5.3.26 by @renovate in https://github.com/Trendyol/stove4k/pull/57
* Update all non-major dependencies to v2.9.7 by @renovate in https://github.com/Trendyol/stove4k/pull/58
* Update all non-major dependencies to v2.7.10 by @renovate in https://github.com/Trendyol/stove4k/pull/59
* HttpSytem GET considers queryParams and use exposedConfiguration instead of container's by @osoykan in https://github.com/Trendyol/stove4k/pull/61
* Update all non-major dependencies to v3.4.0 by @renovate in https://github.com/Trendyol/stove4k/pull/62
* add java.time.Instant support by @Bramix in https://github.com/Trendyol/stove4k/pull/63


**Full Changelog**: https://github.com/Trendyol/stove4k/compare/0.0.13-SNAPSHOT...0.0.14-SNAPSHOT

### Bug Fixes

- general:
  - fix objectMapper configuration for elasticsearchSystem (#63) ([f7c0dac](https://github.com/Trendyol/stove4k/commit/f7c0dac524b08e4f547ff855ea4588e0a2b752d3)) ([#63](https://github.com/Trendyol/stove4k/pull/63))

## [0.0.13-SNAPSHOT](https://github.com/Trendyol/stove4k/releases/tag/0.0.13-SNAPSHOT) - 2023-03-15 16:17:42

## What's Changed
* Update dependency co.elastic.clients:elasticsearch-java to v8.6.2 by @renovate in https://github.com/Trendyol/stove4k/pull/38
* Update all non-major dependencies by @renovate in https://github.com/Trendyol/stove4k/pull/39
* #37 support for mongodb by @osoykan in https://github.com/Trendyol/stove4k/pull/40
* #42 Now elastic exposes its certificate to the configuration by @osoykan in https://github.com/Trendyol/stove4k/pull/43
* Provide way to disable security #44 by @osoykan in https://github.com/Trendyol/stove4k/pull/45
* Update all non-major dependencies by @renovate in https://github.com/Trendyol/stove4k/pull/46
* Update all non-major dependencies to v2.2.4 by @renovate in https://github.com/Trendyol/stove4k/pull/47
* Bearer token not properly passed to http headers by @osoykan in https://github.com/Trendyol/stove4k/pull/49
* Update all non-major dependencies by @renovate in https://github.com/Trendyol/stove4k/pull/50
* Update all non-major dependencies by @renovate in https://github.com/Trendyol/stove4k/pull/52
* Adds support for Couchbase multiple collections and migrations by @osoykan in https://github.com/Trendyol/stove4k/pull/54
* Update plugin gitVersioning to v2 by @renovate in https://github.com/Trendyol/stove4k/pull/51

**Full Changelog**: https://github.com/Trendyol/stove4k/compare/0.0.12-SNAPSHOT...0.0.13-SNAPSHOT

## [0.0.12-SNAPSHOT](https://github.com/Trendyol/stove4k/releases/tag/0.0.12-SNAPSHOT) - 2023-02-15 16:39:52

**Full Changelog**: https://github.com/Trendyol/stove4k/compare/0.0.11-SNAPSHOT...0.0.12-SNAPSHOT

### Bug Fixes

- general:
  - fix when container is not 8.x while creating sslcontext ([af4d6ab](https://github.com/Trendyol/stove4k/commit/af4d6ab5c3c957ce2d6cc86a6c5893088ac9877b))

## [0.0.11-SNAPSHOT](https://github.com/Trendyol/stove4k/releases/tag/0.0.11-SNAPSHOT) - 2023-02-14 15:54:40

## What's Changed
* Update all non-major dependencies by @renovate in https://github.com/Trendyol/stove4k/pull/33
* ObjectMapper is the first class citizen for ser/de #34 by @osoykan in https://github.com/Trendyol/stove4k/pull/35


**Full Changelog**: https://github.com/Trendyol/stove4k/compare/0.0.9-SNAPSHOT...0.0.11-SNAPSHOT

## [0.0.9-SNAPSHOT](https://github.com/Trendyol/stove4k/releases/tag/0.0.9-SNAPSHOT) - 2023-02-14 09:12:35

## What's Changed
* Update all non-major dependencies to v5.5.5 by @renovate in https://github.com/Trendyol/stove4k/pull/27
* Update dependency org.apache.kafka:kafka-clients to v3.4.0 by @renovate in https://github.com/Trendyol/stove4k/pull/29
* Update all non-major dependencies to v3.3.1 by @renovate in https://github.com/Trendyol/stove4k/pull/30
* [#10] Feature/elasticsearch support by @Bramix in https://github.com/Trendyol/stove4k/pull/28
* Update all non-major dependencies by @renovate in https://github.com/Trendyol/stove4k/pull/31
* Update dependency gradle to v8 by @renovate in https://github.com/Trendyol/stove4k/pull/32

## New Contributors
* @Bramix made their first contribution in https://github.com/Trendyol/stove4k/pull/28

**Full Changelog**: https://github.com/Trendyol/stove4k/compare/0.0.8-SNAPSHOT...0.0.9-SNAPSHOT

### Bug Fixes

- general:
  - fix edit uri ([ab3caa5](https://github.com/Trendyol/stove4k/commit/ab3caa57d128585f2e17535735f8f89c8d0111d4))
  - fix doc links ([ded507b](https://github.com/Trendyol/stove4k/commit/ded507b9919e474154a4ef89b10b4f5830aa4bd9))
  - fix typo ([377dcf2](https://github.com/Trendyol/stove4k/commit/377dcf2cbd47431c08d3599e0edc3a38dcc71373))

## [0.0.8-SNAPSHOT](https://github.com/Trendyol/stove4k/releases/tag/0.0.8-SNAPSHOT) - 2023-02-02 17:16:19

## What's Changed
* Update all non-major dependencies by @osoykan in https://github.com/Trendyol/stove4k/pull/16
* #15 create a spring example application for show case by @AksalBilal in https://github.com/Trendyol/stove4k/pull/17
* Update all non-major dependencies to v1.8.10 by @renovate in https://github.com/Trendyol/stove4k/pull/24
* #22 support Ktor framework, support Postgres over rdbms abstraction by @CanerPatir in https://github.com/Trendyol/stove4k/pull/23

## New Contributors
* @AksalBilal made their first contribution in https://github.com/Trendyol/stove4k/pull/17
* @renovate made their first contribution in https://github.com/Trendyol/stove4k/pull/24
* @CanerPatir made their first contribution in https://github.com/Trendyol/stove4k/pull/23

**Full Changelog**: https://github.com/Trendyol/stove4k/compare/0.0.7-SNAPSHOT...0.0.8-SNAPSHOT

## [0.0.7-SNAPSHOT](https://github.com/Trendyol/stove4k/releases/tag/0.0.7-SNAPSHOT) - 2023-01-25 13:34:36

## What's Changed
* Update all non-major dependencies to v2.7.8 by @osoykan in https://github.com/Trendyol/stove4k/pull/11
**Full Changelog**: https://github.com/Trendyol/stove4k/commits/0.0.7-SNAPSHOT

### Documentation

- general:
  - docs ([86bd95c](https://github.com/Trendyol/stove4k/commit/86bd95c6ea17f2d106ca7cab93e91431992a4f48))

\* *This CHANGELOG was automatically generated by [auto-generate-changelog](https://github.com/BobAnkh/auto-generate-changelog)*
