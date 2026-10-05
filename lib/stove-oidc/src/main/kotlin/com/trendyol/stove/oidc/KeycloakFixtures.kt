package com.trendyol.stove.oidc

import org.keycloak.representations.idm.ClientRepresentation

/** A confidential client for client-credentials tests; further native configuration remains available. */
fun serviceAccountClient(
  clientId: String,
  secret: String,
  configure: ClientRepresentation.() -> Unit = {}
): ClientRepresentation = ClientRepresentation().apply {
  this.clientId = clientId
  this.secret = secret
  isEnabled = true
  isPublicClient = false
  isServiceAccountsEnabled = true
  isStandardFlowEnabled = false
  isDirectAccessGrantsEnabled = false
  protocol = "openid-connect"
  configure()
}
