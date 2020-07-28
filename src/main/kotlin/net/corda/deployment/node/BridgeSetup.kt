package net.corda.deployment.node

import com.azure.storage.file.share.ShareFileClient
import io.kubernetes.client.openapi.ApiClient
import io.kubernetes.client.openapi.models.V1Deployment
import io.kubernetes.client.openapi.models.V1Service
import net.corda.deployment.node.config.ConfigGenerators
import net.corda.deployment.node.kubernetes.SecretCreator
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
    val randomSuffix: String
) {


    private lateinit var deployment: BridgeDeployment
    private lateinit var nodeStoreSecrets: NodeStoresSecrets
    private lateinit var artemisSecrets: ArtemisSecrets
    private lateinit var networkShare: AzureFilesDirectory
    private lateinit var tunnelSecrets: FirewallTunnelSecrets
    private lateinit var configShare: AzureFilesDirectory
    private var config: String? = null
    private var artemisComponents: BridgeArtemisComponents? = null
    private var tunnelComponents: BridgeTunnelComponents? = null
    private var bridgeStores: BridgeStores? = null
    private var bridgeStoreSecrets: BridgeSecrets? = null

    fun generateBridgeStoreSecrets(api: () -> ApiClient): BridgeSecrets {
        val bridgeCertificatesSecretName = "bridge-certs-secerts-$randomSuffix"
        val bridgeSSLKeyStorePasswordSecretKey = "bridgesslpassword"
        SecretCreator.createStringSecret(
            bridgeCertificatesSecretName,
            listOf(
                bridgeSSLKeyStorePasswordSecretKey to RandomStringUtils.randomAlphanumeric(20)
            ).toMap(),
            namespace,
            api
        )

        return BridgeSecrets(bridgeCertificatesSecretName, bridgeSSLKeyStorePasswordSecretKey).also {
            this.bridgeStoreSecrets = it
        }
    }

    fun importNodeKeyStoreIntoBridge(
        nodeStoreSecrets: NodeStoresSecrets,
        initialRegistrationResult: InitialRegistrationResult,
        api: () -> ApiClient
    ): BridgeStores {
        if (bridgeStoreSecrets == null) {
            throw IllegalStateException("must generate bridge ssl secrets before importing node tls keys")
        }
        val importNodeToBridgeJobName = "import-node-ssl-to-bridge-${randomSuffix}"
        val bridgeCertificatesShare = shareCreator.createDirectoryFor("bridge-certs")
        val importNodeKeyStoreToBridgeJob = importNodeKeyStoreToBridgeJob(
            importNodeToBridgeJobName,
            nodeStoreSecrets.secretName,
            nodeStoreSecrets.nodeKeyStorePasswordKey,
            bridgeStoreSecrets!!.secretName,
            bridgeStoreSecrets!!.bridgeSSLKeystorePasswordKey,
            initialRegistrationResult.certificatesDir,
            bridgeCertificatesShare
        )

        simpleApply.create(importNodeKeyStoreToBridgeJob, namespace, api)
        waitForJob(importNodeKeyStoreToBridgeJob, namespace, api)
        dumpLogsForJob(importNodeKeyStoreToBridgeJob, api)
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
        val networkShare = shareCreator.createDirectoryFor("bridge-network-params")
        val bridgeNetworkParamsFileReference =
            networkShare.modernClient.rootDirectoryClient.getFileClient(BridgeConfigParams.BRIDGE_NETWORK_PARAMETERS_FILENAME)
        bridgeNetworkParamsFileReference.createFrom(initialRegistrationResult.networkParameters)
        this.networkShare = networkShare
    }

    fun copyBridgeTunnelStoreComponents(tunnelStores: GeneratedTunnelStores): BridgeTunnelComponents {
        val trustStore: ShareFileClient = tunnelStores.trustStore
        val bridgeTunnelKeyStore = tunnelStores.bridgeStore

        val bridgeTunnelShare = shareCreator.createDirectoryFor("bridge-tunnel")
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

    fun createArtemisSecrets(artemisSecrets: ArtemisSecrets) {
        this.artemisSecrets = artemisSecrets
    }

    fun copyBridgeArtemisStoreComponents(artemisStores: GeneratedArtemisStores): BridgeArtemisComponents {
        val trustStore = artemisStores.trustStore
        val bridgeArtemisKeyStore = artemisStores.bridgeStore

        val bridgeArtemisStoresShare = shareCreator.createDirectoryFor("bridge-artemis-stores")
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

        val bridgeConfig = ConfigGenerators.generateConfigFromParams(bridgeConfigParams)
        return bridgeConfig.also {
            this.config = it
        }
    }

    fun uploadBridgeConfig() {
        if (this.config == null) {
            throw IllegalStateException("must generate config before uploading")
        }
        val bridgeConfigShare = shareCreator.createDirectoryFor("bridge-config")
        val bridgeConfigFileReference =
            bridgeConfigShare.modernClient.rootDirectoryClient.getFileClient(BridgeConfigParams.BRIDGE_CONFIG_FILENAME)
        bridgeConfigFileReference.uploadFromByteArray(config!!.toByteArray(Charsets.UTF_8))
        this.configShare = bridgeConfigShare
    }

    fun deploy(api: () -> ApiClient): BridgeDeployment {
        val bridgeDeployment = createBridgeDeployment(
            namespace,
            randomSuffix,
            configShare,
            tunnelComponents?.bridgeTunnelShare!!,
            artemisComponents?.bridgeArtemisStoresShare!!,
            bridgeStores?.certificatesDir!!,
            networkShare,
            tunnelSecrets,
            artemisSecrets,
            bridgeStoreSecrets!!.secretName,
            bridgeStoreSecrets!!.bridgeSSLKeystorePasswordKey,
            nodeStoreSecrets.secretName,
            nodeStoreSecrets.sharedTrustStorePasswordKey
        )
        simpleApply.create(bridgeDeployment, namespace, api)
        return BridgeDeployment(bridgeDeployment).also {
            this.deployment = it
        }
    }

}

class BridgeSecrets(val secretName: String, val bridgeSSLKeystorePasswordKey: String)

class BridgeStores(val certificatesDir: AzureFilesDirectory)

class BridgeTunnelComponents(val bridgeTunnelShare: AzureFilesDirectory)

class BridgeArtemisComponents(val bridgeArtemisStoresShare: AzureFilesDirectory)

class BridgeDeployment(val deployment: V1Deployment)

