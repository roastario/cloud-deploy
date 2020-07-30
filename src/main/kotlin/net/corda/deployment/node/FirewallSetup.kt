package net.corda.deployment.node

import com.azure.storage.file.share.ShareFileClient
import io.kubernetes.client.openapi.ApiClient
import net.corda.deployment.node.kubernetes.SecretCreator
import net.corda.deployment.node.kubernetes.simpleApply
import net.corda.deployment.node.storage.AzureFileShareCreator
import net.corda.deployment.node.storage.AzureFilesDirectory
import net.corda.deployment.node.storage.enforceExistence
import net.corda.deployments.node.config.TunnelConfigParams
import org.apache.commons.lang3.RandomStringUtils

class FirewallSetup(
    private val namespace: String,
    private val shareCreator: AzureFileShareCreator
) {

    private var tunnelStores: GeneratedTunnelStores? = null
    private var tunnelSecrets: FirewallTunnelSecrets? = null

    fun generateFirewallTunnelSecrets(
        nonDmzApiSource: () -> ApiClient,
        dmzApiSource: () -> ApiClient
    ): FirewallTunnelSecrets {
        val tunnelSecretName = "tunnel-store-secrets"
        val tunnelEntryPasswordKey = "tunnelentrypassword"
        val tunnelKeyStorePasswordKey = "tunnelsslkeystorepassword"
        val tunnelTrustStorePasswordKey = "tunneltruststorepassword";
        val entryPair = tunnelEntryPasswordKey to RandomStringUtils.randomAlphanumeric(32)
        val keyStorePair = tunnelKeyStorePasswordKey to RandomStringUtils.randomAlphanumeric(32)
        val trustStorePair = tunnelTrustStorePasswordKey to RandomStringUtils.randomAlphanumeric(32)
        createTunnelSecrets(tunnelSecretName, entryPair, keyStorePair, trustStorePair, nonDmzApiSource)
        createTunnelSecrets(tunnelSecretName, entryPair, keyStorePair, trustStorePair, dmzApiSource)
        return FirewallTunnelSecrets(
            tunnelSecretName,
            tunnelEntryPasswordKey,
            tunnelKeyStorePasswordKey,
            tunnelTrustStorePasswordKey
        ).also {
            this.tunnelSecrets = it
        }
    }

    private fun createTunnelSecrets(
        tunnelSecretName: String,
        tunnelEntryPasswordAndKey: Pair<String, String>,
        tunnelKeyStorePasswordAndKey: Pair<String, String>,
        tunnelTrustStorePasswordAndKey: Pair<String, String>,
        dmzApiSource: () -> ApiClient
    ) {
        SecretCreator.createStringSecret(
            tunnelSecretName,
            listOf(
                tunnelEntryPasswordAndKey,
                tunnelKeyStorePasswordAndKey,
                tunnelTrustStorePasswordAndKey
            ).toMap()
            , namespace, dmzApiSource
        )
    }

    suspend fun generateTunnelStores(api: () -> ApiClient): GeneratedTunnelStores {
        if (tunnelSecrets == null) {
            throw IllegalStateException("must generate tunnel secrets before generating tunnel stores")
        }

        val tunnelStoresShare = shareCreator.createDirectoryFor("tunnel-stores")
        val generateTunnelStoresJobName = "gen-tunnel-stores"
        val generateTunnelStoresJob = generateTunnelStores(
            generateTunnelStoresJobName,
            tunnelSecrets!!,
            tunnelStoresShare
        )

        simpleApply.create(generateTunnelStoresJob, namespace, api)
        waitForJob(generateTunnelStoresJob, namespace, api)
        dumpLogsForJob(generateTunnelStoresJob, namespace, api)

        return GeneratedTunnelStores(tunnelStoresShare).also {
            this.tunnelStores = it
        }
    }

}


data class FirewallTunnelSecrets(
    val secretName: String,
    val entryPasswordKey: String,
    val keystorePasswordKey: String,
    val truststorePasswordKey: String
)

class GeneratedTunnelStores(val tunnelShare: AzureFilesDirectory) {

    val floatStore: ShareFileClient
        get() {
            return tunnelShare.modernClient.rootDirectoryClient.getFileClient(TunnelConfigParams.TUNNEL_FLOAT_KEYSTORE_FILENAME)
                .enforceExistence()
        }
    val bridgeStore: ShareFileClient
        get() {
            return tunnelShare.modernClient.rootDirectoryClient.getFileClient(TunnelConfigParams.TUNNEL_BRIDGE_KEYSTORE_FILENAME)
                .enforceExistence()

        }
    val trustStore: ShareFileClient
        get() {
            return tunnelShare.modernClient.rootDirectoryClient.getFileClient(TunnelConfigParams.TUNNEL_TRUSTSTORE_FILENAME)
                .enforceExistence()
        }
}