package com.trendyol.stove.oidc

import arrow.core.getOrElse
import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.ObjectMapper
import dasniko.testcontainers.keycloak.KeycloakContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import org.keycloak.representations.idm.RealmRepresentation
import java.io.File
import java.io.IOException
import java.io.InputStream

internal class KeycloakOidcBackend(private val provider: OidcProvider.Keycloak) : OidcBackend {
  // The backend is constructed only when a Keycloak system starts; NAV never touches Docker.
  private val container = KeycloakContainer(provider.image)
  private var phase = OidcBackendPhase.Created

  override suspend fun start(): String = runInterruptible(Dispatchers.IO) {
    check(phase == OidcBackendPhase.Created) { "OIDC provider has already been started or closed" }
    withOidcContext("Keycloak container startup") { startContainer() }
    withOidcContext("Keycloak realm configuration") { configureRealm(container) }
    phase = OidcBackendPhase.Running
    provider.issuerUrl.getOrElse { "${container.authServerUrl.trimEnd('/')}/realms/${provider.realm}" }
  }

  private fun startContainer() {
    container.apply(provider.configureContainer)
    container.withReuse(false)
    container.start()
  }

  private fun configureRealm(instance: KeycloakContainer) {
    instance.keycloakAdminClient.use { admin ->
      val realm = realmDefinition()
      requireConfiguration(
        realm.realm == provider.realm,
        "setup.realm",
        "Realm '${realm.realm}' must match configured realm '${provider.realm}'"
      )
      admin.realms().create(realm)
      provider.configureAdmin(admin, provider.realm)
    }
  }

  private fun realmDefinition(): RealmRepresentation = when (val setup = provider.setup) {
    RealmSetup.Empty -> newRealm()

    is RealmSetup.Define -> newRealm().apply(setup.configure)

    is RealmSetup.Classpath -> importRealm {
      javaClass.classLoader.getResourceAsStream(setup.path)
        ?: throw OidcConfigurationException("setup", "Realm resource not found on the classpath: ${setup.path}")
    }

    is RealmSetup.File -> importRealm { File(setup.path).inputStream() }
  }

  private fun newRealm(): RealmRepresentation {
    val realm = RealmRepresentation()
    realm.realm = provider.realm
    realm.isEnabled = true
    return realm
  }

  private fun importRealm(open: () -> InputStream): RealmRepresentation = try {
    open().use { ObjectMapper().readValue(it, RealmRepresentation::class.java) }
      ?: throw OidcConfigurationException("setup", "Realm import must contain a JSON object")
  } catch (error: JsonProcessingException) {
    throw OidcConfigurationException("setup", "Realm import must contain valid Keycloak realm JSON: ${error.message}", error)
  } catch (error: IOException) {
    throw OidcConfigurationException("setup", "Realm import could not be read: ${error.message}", error)
  }

  override fun close() {
    if (phase == OidcBackendPhase.Closed) return
    phase = OidcBackendPhase.Closed
    container.stop()
  }
}
