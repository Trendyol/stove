package stove.ktor.example.app

import com.fasterxml.jackson.databind.ObjectMapper
import org.koin.dsl.*
import stove.ktor.example.application.*
import stove.ktor.example.domain.ProductRepository
import stove.ktor.example.infrastructure.FeatureToggleClient
import stove.ktor.example.infrastructure.PricingClient

val objectMapperRef: ObjectMapper = ObjectMapper().apply {
  findAndRegisterModules()
}

fun app(cfg: AppConfiguration) = module {
  // External gRPC clients - both can point to the same mock server in tests
  single { FeatureToggleClient(cfg.featureToggle.host, cfg.featureToggle.port) }
  single { PricingClient(cfg.pricing.host, cfg.pricing.port) }

  single { ProductRepository(get()) }
  single { ProductService(get(), get(), get(), get(), get()) }
  single { MutexLockProvider() }.bind<LockProvider>()
}
