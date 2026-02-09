package com.trendyol.stove.elasticsearch

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

class ElasticsearchOptionsTest :
  FunSpec({

    test("ElasticSearchExposedConfiguration should hold connection details") {
      val cfg = ElasticSearchExposedConfiguration(
        host = "localhost",
        port = 9200,
        password = "secret",
        certificate = null
      )

      cfg.host shouldBe "localhost"
      cfg.port shouldBe 9200
      cfg.password shouldBe "secret"
      cfg.certificate shouldBe null
    }

    test("ElasticsearchSystemOptions.provided should create ProvidedElasticsearchSystemOptions") {
      val options = ElasticsearchSystemOptions.provided(
        host = "es-host",
        port = 9200,
        password = "pass",
        configureExposedConfiguration = { cfg ->
          listOf("es.host=${cfg.host}", "es.port=${cfg.port}")
        }
      )

      options.providedConfig.host shouldBe "es-host"
      options.providedConfig.port shouldBe 9200
      options.providedConfig.password shouldBe "pass"
      options.providedConfig.certificate shouldBe null
      options.runMigrationsForProvided shouldBe true
    }

    test("ProvidedElasticsearchSystemOptions should expose correct properties") {
      val config = ElasticSearchExposedConfiguration(
        host = "remote-es",
        port = 9201,
        password = "p",
        certificate = null
      )
      val options = ProvidedElasticsearchSystemOptions(
        config = config,
        runMigrations = false,
        configureExposedConfiguration = { _ -> listOf() }
      )

      options.config shouldBe config
      options.providedConfig shouldBe config
      options.runMigrationsForProvided shouldBe false
    }

    test("ElasticClientConfigurer should have default configuration") {
      val configurer = ElasticClientConfigurer()
      configurer.httpClientBuilder shouldNotBe null
      configurer.restClientOverrideFn.isNone() shouldBe true
    }

    test("ElasticContainerOptions should have sensible defaults") {
      val opts = ElasticContainerOptions()
      opts.password shouldBe "password"
      opts.disableSecurity shouldBe true
      opts.exposedPorts shouldBe listOf(ElasticContainerOptions.DEFAULT_ELASTIC_PORT)
    }
  })
