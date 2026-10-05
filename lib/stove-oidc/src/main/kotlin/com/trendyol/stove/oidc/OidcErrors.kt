package com.trendyol.stove.oidc

import kotlinx.coroutines.CancellationException

internal fun requireConfiguration(valid: Boolean, field: String, guidance: String) {
  if (!valid) throw OidcConfigurationException(field, guidance)
}

internal inline fun <T> withOidcContext(operation: String, action: () -> T): T = try {
  action()
} catch (e: CancellationException) {
  throw e
} catch (e: InterruptedException) {
  Thread.currentThread().interrupt()
  throw e
} catch (e: OidcTokenEndpointException) {
  throw e
} catch (e: OidcOperationException) {
  throw e
} catch (e: Exception) {
  throw OidcOperationException(operation, e.message ?: e.javaClass.simpleName, e)
}
