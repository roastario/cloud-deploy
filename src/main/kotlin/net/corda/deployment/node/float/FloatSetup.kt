package net.corda.deployment.node.float

import io.kubernetes.client.openapi.ApiClient
import io.kubernetes.client.openapi.models.V1Deployment
import io.kubernetes.client.openapi.models.V1Service
import net.corda.deployment.node.*
import net.corda.deployment.node.config.ConfigGenerators
import net.corda.deployment.node.kubernetes.simpleApply
import net.corda.deployment.node.storage.AzureFileShareCreator
import net.corda.deployment.node.storage.AzureFilesDirectory
import net.corda.deployment.node.storage.uploadFromByteArray
import net.corda.deployments.node.config.BridgeConfigParams
import net.corda.deployments.node.config.FloatConfigParams
import net.corda.deployments.node.config.TunnelConfigParams

open class FloatSetup(
    val namespace: String,
    val shareCreator: AzureFileShareCreator
) {


    private lateinit var deployment: FloatDeployment
    private lateinit var tunnelSecrets: FirewallTunnelSecrets
    private lateinit var configShare: AzureFilesDirectory
    private lateinit var config: String
    private lateinit var tunnelComponents: FloatTunnelComponents


    fun copyTunnelStoreComponents(tunnelStores: GeneratedTunnelStores): FloatTunnelComponents {
        val trustStore = tunnelStores.trustStore
        val keyStore = tunnelStores.floatStore
        val floatTunnelShare = shareCreator.createDirectoryFor("float-tunnel")
        val trustStoreReference =
            floatTunnelShare.modernClient.rootDirectoryClient.getFileClient(TunnelConfigParams.TUNNEL_TRUSTSTORE_FILENAME)
        val keyStoreReference =
            floatTunnelShare.modernClient.rootDirectoryClient.getFileClient(TunnelConfigParams.TUNNEL_FLOAT_KEYSTORE_FILENAME)
        trustStoreReference.createFrom(trustStore)
        keyStoreReference.createFrom(keyStore)

        return FloatTunnelComponents(floatTunnelShare).also {
            this.tunnelComponents = it
        }
    }

    fun generateConfig(): String {
        val floatConfig = FloatConfigParams.builder()
            .withBaseDir(FloatConfigParams.FLOAT_BASE_DIR)
            .withExpectedBridgeCertificateSubject(BridgeConfigParams.BRIDGE_CERTIFICATE_SUBJECT)
            .withExternalBindAddress(FloatConfigParams.ALL_LOCAL_ADDRESSES)
            .withExternalPort(FloatConfigParams.FLOAT_EXTERNAL_PORT)
            .withInternalBindAddress(FloatConfigParams.ALL_LOCAL_ADDRESSES)
            .withInternalPort(FloatConfigParams.FLOAT_INTERNAL_PORT)
            .withNetworkParametersPath(FloatConfigParams.FLOAT_NETWORK_PARAMETERS_PATH)
            .withTunnelKeyStorePassword(FloatConfigParams.FLOAT_TUNNEL_SSL_KEYSTORE_PASSWORD_ENV_VAR_NAME.toEnvVar())
            .withTunnelKeystorePath(FloatConfigParams.FLOAT_TUNNEL_SSL_KEYSTORE_PATH)
            .withTunnelTrustStorePassword(FloatConfigParams.FLOAT_TUNNEL_TRUSTSTORE_PASSWORD_ENV_VAR_NAME.toEnvVar())
            .withTunnelTrustStorePath(FloatConfigParams.FLOAT_TUNNEL_TRUSTSTORE_PATH)
            .withTunnelStoresEntryPassword(FloatConfigParams.FLOAT_TUNNEL_ENTRY_PASSWORD_ENV_VAR_NAME.toEnvVar())
            .build()

        return ConfigGenerators.generateConfigFromParams(floatConfig).also {
            this.config = it
        }
    }

    fun uploadConfig() {
        val configDir = shareCreator.createDirectoryFor("float-config")
        configDir.modernClient.rootDirectoryClient.getFileClient(FloatConfigParams.FLOAT_CONFIG_FILENAME)
            .uploadFromByteArray(config.toByteArray(Charsets.UTF_8))
        this.configShare = configDir
    }

    fun deploy(api: () -> ApiClient): FloatDeployment {
        val floatDeployment = createFloatDeployment(
            namespace,
            configShare,
            tunnelComponents.tunnelShare,
            tunnelSecrets
        )
        val internalService = buildInternalService(floatDeployment)
        simpleApply.create(floatDeployment, namespace, api)
        simpleApply.create(internalService.underlyingService, namespace, api)

        return FloatDeployment(floatDeployment, internalService).also {
            this.deployment = it
        }
    }

    open fun buildInternalService(deployment: V1Deployment): InternalFloatService {
        val underlyingService = createIntraClusterInternalFloatService(deployment)
        return object : InternalFloatService(underlyingService) {
            override fun getInternalAddress(): String {
                return underlyingService.metadata?.name ?: throw IllegalStateException("internal float service name not available")
            }
        }
    }

    fun createTunnelSecrets(secrets: FirewallTunnelSecrets) {
        this.tunnelSecrets = secrets
    }

}

class FloatTunnelComponents(val tunnelShare: AzureFilesDirectory)

class FloatDeployment(val deployment: V1Deployment, val internalService: InternalFloatService, val externalService: V1Service? = null)

abstract class InternalFloatService(val underlyingService: V1Service) {
    abstract fun getInternalAddress(): String
}