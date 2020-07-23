package net.corda.deployment.node

import io.kubernetes.client.openapi.ApiClient
import net.corda.deployment.node.kubernetes.SecretCreator
import org.apache.commons.lang3.RandomStringUtils

class FirewallSetup(private val namespace: String, private val randomSuffix: String) {

    fun generateFirewallTunnelSecrets(api: () -> ApiClient): FirewallTunnelSecrets {
        val tunnelSecretName = "tunnel-store-secrets-$randomSuffix"
        val tunnelEntryPasswordKey = "tunnelentrypassword"
        val tunnelKeyStorePasswordKey = "tunnelsslkeystorepassword"
        val tunnelTrustStorePasswordKey = "tunneltruststorepassword";
        val tunnelSecret = SecretCreator.createStringSecret(
            tunnelSecretName,
            listOf(
                tunnelEntryPasswordKey to RandomStringUtils.randomAlphanumeric(32),
                tunnelKeyStorePasswordKey to RandomStringUtils.randomAlphanumeric(32),
                tunnelTrustStorePasswordKey to RandomStringUtils.randomAlphanumeric(32)
            ).toMap()
            , namespace, api
        )
        return FirewallTunnelSecrets(tunnelSecretName, tunnelEntryPasswordKey, tunnelKeyStorePasswordKey, tunnelTrustStorePasswordKey)
    }

}


data class FirewallTunnelSecrets(
    val secretName: String,
    val entryPasswordKey: String,
    val keystorePasswordKey: String,
    val truststorePasswordKey: String
)