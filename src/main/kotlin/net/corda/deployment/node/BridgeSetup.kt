package net.corda.deployment.node

import com.azure.storage.file.share.ShareFileClient
import io.kubernetes.client.openapi.ApiClient
import io.kubernetes.client.openapi.apis.AppsV1Api
import io.kubernetes.client.openapi.models.V1Deployment
import io.kubernetes.client.openapi.models.V1EnvVarBuilder
import net.corda.deployment.node.config.ConfigGenerators
import net.corda.deployment.node.kubernetes.SecretCreator
import net.corda.deployment.node.kubernetes.simpleApply
import net.corda.deployment.node.storage.AzureFileShareCreator
import net.corda.deployment.node.storage.AzureFilesDirectory
import net.corda.deployment.node.storage.uploadFromByteArray
import net.corda.deployments.node.config.ArtemisConfigParams
import net.corda.deployments.node.config.BridgeConfigParams
import net.corda.deployments.node.config.FloatConfigParams
import net.corda.deployments.node.config.TunnelConfigParams
import org.apache.commons.lang3.RandomStringUtils

class BridgeSetup(
    val shareCreator: AzureFileShareCreator,
    val namespace: String,
    val api: () -> ApiClient
) {


    private lateinit var deployment: BridgeDeployment
    private lateinit var nodeStoreSecrets: NodeStoresSecrets
    private lateinit var networkShare: AzureFilesDirectory
    private lateinit var tunnelSecrets: FirewallTunnelSecrets
    private lateinit var configShare: AzureFilesDirectory
    private var artemisComponents: BridgeArtemisComponents? = null
    private var tunnelComponents: BridgeTunnelComponents? = null
    private var bridgeStores: BridgeStores? = null
//    private var bridgeStoreSecrets: BridgeSecrets? = null

    fun generateBridgeKeyStoreSecrets(): BridgeSecrets {
        val bridgeCertificatesSecretName = "bridge-stores-secrets"
        val bridgeSSLKeyStorePasswordSecretKey = "bridgesslpassword"
        SecretCreator.createStringSecret(
            bridgeCertificatesSecretName,
            listOf(
                bridgeSSLKeyStorePasswordSecretKey to RandomStringUtils.randomAlphanumeric(20)
            ).toMap(),
            namespace,
            api
        )
        return BridgeSecrets(bridgeCertificatesSecretName, bridgeSSLKeyStorePasswordSecretKey)
    }

    suspend fun importNodeKeyStoreIntoBridge(
        nodeStoreSecrets: NodeStoresSecrets,
        initialRegistrationResult: InitialRegistrationResult,
        bridgeStoreSecrets: BridgeSecrets
    ): BridgeStores {
        val importNodeToBridgeJobName = "import-node-ssl-to-bridge-${RandomStringUtils.randomAlphanumeric(8).toLowerCase()}"
        val bridgeCertificatesShare = shareCreator.createDirectoryFor("bridge-certs", api)
        val importNodeKeyStoreToBridgeJob = importNodeKeyStoreToBridgeJob(
            importNodeToBridgeJobName,
            nodeStoreSecrets.secretName,
            nodeStoreSecrets.nodeKeyStorePasswordKey,
            bridgeStoreSecrets.secretName,
            bridgeStoreSecrets.bridgeSSLKeystorePasswordKey,
            initialRegistrationResult.certificatesDir,
            bridgeCertificatesShare
        )

        simpleApply.create(importNodeKeyStoreToBridgeJob, namespace, api)
        waitForJob(importNodeKeyStoreToBridgeJob, namespace, api)
        dumpLogsForJob(importNodeKeyStoreToBridgeJob, namespace, api)
        return BridgeStores(bridgeCertificatesShare).also {
            this.bridgeStores = it
            this.nodeStoreSecrets = nodeStoreSecrets
        }

    }

    fun copyTrustStoreFromNodeRegistrationResult(initialRegistrationResult: InitialRegistrationResult) {
        if (bridgeStores == null) {
            throw IllegalStateException("must import ssl stores before copying truststore")
        }
        val trustStoreFileReference =
            bridgeStores!!.certificatesDir.modernClient.rootDirectoryClient.getFileClient(BridgeConfigParams.BRIDGE_TRUSTSTORE_FILENAME)
        trustStoreFileReference.createFrom(initialRegistrationResult.sharedTrustStore)
    }

    fun copyNetworkParametersFromNodeRegistrationResult(initialRegistrationResult: InitialRegistrationResult) {
        val networkShare = shareCreator.createDirectoryFor("bridge-network-params", api)
        val bridgeNetworkParamsFileReference =
            networkShare.modernClient.rootDirectoryClient.getFileClient(BridgeConfigParams.BRIDGE_NETWORK_PARAMETERS_FILENAME)
        bridgeNetworkParamsFileReference.createFrom(initialRegistrationResult.networkParameters)
        this.networkShare = networkShare
    }

    fun copyBridgeTunnelStoreComponents(tunnelStores: GeneratedTunnelStores): BridgeTunnelComponents {
        val trustStore: ShareFileClient = tunnelStores.trustStore
        val bridgeTunnelKeyStore = tunnelStores.bridgeStore

        val bridgeTunnelShare = shareCreator.createDirectoryFor("bridge-tunnel", api)
        val bridgeTunnelTrustStoreFileReference =
            bridgeTunnelShare.modernClient.rootDirectoryClient.getFileClient(TunnelConfigParams.TUNNEL_TRUSTSTORE_FILENAME)
        val bridgeTunnelKeyStoreFileReference =
            bridgeTunnelShare.modernClient.rootDirectoryClient.getFileClient(TunnelConfigParams.TUNNEL_BRIDGE_KEYSTORE_FILENAME)
        bridgeTunnelTrustStoreFileReference.createFrom(trustStore)
        bridgeTunnelKeyStoreFileReference.createFrom(bridgeTunnelKeyStore)

        return BridgeTunnelComponents(bridgeTunnelShare).also {
            this.tunnelComponents = it
        }
    }

    fun createTunnelSecrets(firewallTunnelSecrets: FirewallTunnelSecrets) {
        this.tunnelSecrets = firewallTunnelSecrets
    }

    fun copyBridgeArtemisStoreComponents(artemisStores: GeneratedArtemisStores): BridgeArtemisComponents {
        val trustStore = artemisStores.trustStore
        val bridgeArtemisKeyStore = artemisStores.bridgeStore

        val bridgeArtemisStoresShare = shareCreator.createDirectoryFor("bridge-artemis-stores", api)
        val bridgeArtemisKeyStoreFileReference =
            bridgeArtemisStoresShare.modernClient.rootDirectoryClient.getFileClient(ArtemisConfigParams.ARTEMIS_BRIDGE_KEYSTORE_FILENAME)
        val bridgeArtemisTrustStoreFileReference =
            bridgeArtemisStoresShare.modernClient.rootDirectoryClient.getFileClient(ArtemisConfigParams.ARTEMIS_TRUSTSTORE_FILENAME)

        bridgeArtemisTrustStoreFileReference.createFrom(trustStore)
        bridgeArtemisKeyStoreFileReference.createFrom(bridgeArtemisKeyStore)

        return BridgeArtemisComponents(bridgeArtemisStoresShare).also {
            this.artemisComponents = it
        }
    }

    fun generateBridgeConfig(artemisAddress: String, floatAddress: String): String {
        val bridgeConfigParams = BridgeConfigParams.builder()
            .withArtemisAddress(artemisAddress)
            .withArtemisPort(ArtemisConfigParams.ARTEMIS_ACCEPTOR_PORT)
            .withArtemisKeyStorePath(BridgeConfigParams.BRIDGE_ARTEMIS_SSL_KEYSTORE_PATH)
            .withArtemisKeyStorePassword(BridgeConfigParams.BRIDGE_ARTEMIS_KEYSTORE_PASSWORD_ENV_VAR_NAME.toEnvVar())
            .withArtemisTrustStorePath(BridgeConfigParams.BRIDGE_ARTEMIS_TRUSTSTORE_PATH)
            .withArtemisTrustStorePassword(BridgeConfigParams.BRIDGE_ARTEMIS_TRUSTSTORE_PASSWORD_ENV_VAR_NAME.toEnvVar())
            .withFloatAddress(floatAddress)
            .withFloatPort(FloatConfigParams.FLOAT_INTERNAL_PORT)
            .withExpectedFloatCertificateSubject(FloatConfigParams.FLOAT_CERTIFICATE_SUBJECT)
            .withTunnelKeyStorePath(BridgeConfigParams.BRIDGE_TUNNEL_SSL_KEYSTORE_PATH)
            .withTunnelKeyStorePassword(BridgeConfigParams.BRIDGE_TUNNEL_KEYSTORE_PASSWORD_ENV_VAR_NAME.toEnvVar())
            .withTunnelTrustStorePath(BridgeConfigParams.BRIDGE_TUNNEL_TRUSTSTORE_PATH)
            .withTunnelTrustStorePassword(BridgeConfigParams.BRIDGE_TUNNEL_TRUSTSTORE_PASSWORD_ENV_VAR_NAME.toEnvVar())
            .withTunnelEntryPassword(BridgeConfigParams.BRIDGE_TUNNEL_ENTRY_PASSWORD_ENV_VAR_NAME.toEnvVar())
            .withNetworkParamsPath(BridgeConfigParams.BRIDGE_NETWORK_PARAMETERS_PATH)
            .withBridgeKeyStorePath(BridgeConfigParams.BRIDGE_SSL_KEYSTORE_PATH)
            .withBridgeKeyStorePassword(BridgeConfigParams.BRIDGE_KEYSTORE_PASSWORD_ENV_VAR_NAME.toEnvVar())
            .withBridgeTrustStorePath(BridgeConfigParams.BRIDGE_TRUSTSTORE_PATH)
            .withBridgeTrustStorePassword(BridgeConfigParams.BRIDGE_TRUSTSTORE_PASSWORD_ENV_VAR_NAME.toEnvVar())
            .build()

        return ConfigGenerators.generateConfigFromParams(bridgeConfigParams)
    }

    fun uploadBridgeConfig(config: String, bridgeConfigShare: AzureFilesDirectory) {
        val bridgeConfigFileReference =
            bridgeConfigShare.modernClient.rootDirectoryClient.getFileClient(BridgeConfigParams.BRIDGE_CONFIG_FILENAME)
        bridgeConfigFileReference.uploadFromByteArray(config.toByteArray(Charsets.UTF_8))
        this.configShare = bridgeConfigShare
    }

    fun deploy(artemisSecrets: ArtemisSecrets, bridgeStoreSecrets: BridgeSecrets): BridgeDeployment {
        val bridgeDeployment = createBridgeDeployment(
            namespace,
            configShare,
            tunnelComponents?.bridgeTunnelShare!!,
            artemisComponents?.bridgeArtemisStoresShare!!,
            bridgeStores?.certificatesDir!!,
            networkShare,
            tunnelSecrets,
            artemisSecrets,
            bridgeStoreSecrets.secretName,
            bridgeStoreSecrets.bridgeSSLKeystorePasswordKey,
            nodeStoreSecrets.secretName,
            nodeStoreSecrets.sharedTrustStorePasswordKey
        )
        simpleApply.create(bridgeDeployment, namespace, api)
        return BridgeDeployment(bridgeDeployment, namespace).also {
            this.deployment = it
        }
    }


}

class BridgeSecrets(val secretName: String, val bridgeSSLKeystorePasswordKey: String)

class BridgeStores(val certificatesDir: AzureFilesDirectory)

class BridgeTunnelComponents(val bridgeTunnelShare: AzureFilesDirectory)

class BridgeArtemisComponents(val bridgeArtemisStoresShare: AzureFilesDirectory)

class BridgeDeployment(val deployment: V1Deployment, val namespace: String) {
    fun restart(api: () -> ApiClient) {
        val appsApi = AppsV1Api(api())

        val discoveredDeployment = appsApi.listNamespacedDeployment(
            namespace, null, null, null, null,
            "run=${this.deployment.metadata?.name}", null, null, null, null
        ).items.firstOrNull() ?: throw IllegalStateException("Could not find existing bridge - cannot restart")

        val existingEnv = discoveredDeployment.spec?.template?.spec?.containers?.first()?.env!!
        existingEnv.removeAll { it.name == "RESTART_VAR" }
        existingEnv.add(V1EnvVarBuilder().withName("RESTART_VAR").withValue(RandomStringUtils.randomAlphanumeric(10)).build())
        appsApi.replaceNamespacedDeployment(
            discoveredDeployment.metadata?.name,
            namespace,
            discoveredDeployment,
            null,
            null,
            null
        )
    }
}

