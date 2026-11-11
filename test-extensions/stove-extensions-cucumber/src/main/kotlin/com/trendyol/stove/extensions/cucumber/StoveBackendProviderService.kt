package com.trendyol.stove.extensions.cucumber

import io.cucumber.core.backend.Backend
import io.cucumber.core.backend.BackendProviderService
import io.cucumber.core.backend.Container
import io.cucumber.core.backend.Lookup
import java.util.function.Supplier

/** Loaded by Cucumber's service loader alongside its Java backend. */
class StoveBackendProviderService : BackendProviderService {
  override fun create(lookup: Lookup, container: Container, classLoader: Supplier<ClassLoader>): Backend =
    StoveBackend(lookup, container, classLoader)
}
