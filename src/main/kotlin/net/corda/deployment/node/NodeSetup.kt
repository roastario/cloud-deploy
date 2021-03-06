package net.corda.deployment.node

import com.azure.storage.file.share.ShareFileClient
import freighter.utils.GradleUtils
import io.kubernetes.client.openapi.ApiClient
import io.kubernetes.client.util.Yaml
import net.corda.deployment.node.config.ConfigGenerators
import net.corda.deployment.node.database.DatabaseConfigParams
import net.corda.deployment.node.kubernetes.SecretCreator
import net.corda.deployment.node.kubernetes.simpleApply
import net.corda.deployment.node.storage.AzureFileShareCreator
import net.corda.deployment.node.storage.AzureFilesDirectory
import net.corda.deployment.node.storage.enforceExistence
import net.corda.deployment.node.storage.uploadFromByteArray
import net.corda.deployments.node.config.ArtemisConfigParams
import net.corda.deployments.node.config.NodeConfigParams
import org.apache.commons.lang3.RandomStringUtils
import java.io.File
import java.nio.file.Files

class NodeSetup(
    val shareCreator: AzureFileShareCreator,
    val dbParams: DatabaseConfigParams,
    val namespace: String,
    val api: () -> ApiClient,
    val nodeId: String,
    val hsm: HsmType
) {
    private lateinit var cordappsDirShare: AzureFilesDirectory
    private lateinit var driversDirShare: AzureFilesDirectory
    private lateinit var vaultSecrets: KeyVaultSecrets
    private lateinit var artemisStoresDir: AzureFilesDirectory
    private lateinit var artemisSecrets: ArtemisSecrets
    private var initialRegistrationResult: InitialRegistrationResult? = null
    private var nodeStoresSecrets: NodeStoresSecrets? = null
    private var databaseSecrets: NodeDatabaseSecrets? = null
    private var configDirectory: AzureFilesDirectory? = null
    private var generatedNodeConfig: String? = null

    fun generateNodeConfig(
        nodeX500: String,
        nodeEmail: String,
        nodeP2PAddress: String,
        artemisAddress: String,
        doormanURL: String,
        networkMapURL: String,
        rpcUsername: String,
        rpcPassword: String
    ): String {
        val nodeConfigParams = NodeConfigParams.builder()
            .withX500Name(nodeX500)
            .withEmailAddress(nodeEmail)
            .withNodeSSLKeystorePassword(NodeConfigParams.NODE_SSL_KEYSTORE_PASSWORD_ENV_VAR_NAME.toEnvVar())
            .withNodeTrustStorePassword(NodeConfigParams.NODE_TRUSTSTORE_PASSWORD_ENV_VAR_NAME.toEnvVar())
            .withP2pAddress(nodeP2PAddress)
            .withP2pPort(NodeConfigParams.NODE_P2P_PORT)
            .withArtemisServerAddress(artemisAddress)
            .withArtemisServerPort(ArtemisConfigParams.ARTEMIS_ACCEPTOR_PORT)
            .withArtemisSSLKeyStorePath(NodeConfigParams.NODE_ARTEMIS_SSL_KEYSTORE_PATH)
            .withArtemisSSLKeyStorePass(NodeConfigParams.NODE_ARTEMIS_SSL_KEYSTORE_PASSWORD_ENV_VAR_NAME.toEnvVar())
            .withArtemisTrustStorePath(NodeConfigParams.NODE_ARTEMIS_TRUSTSTORE_PATH)
            .withArtemisTrustStorePass(NodeConfigParams.NODE_ARTEMIS_TRUSTSTORE_PASSWORD_ENV_VAR_NAME.toEnvVar())
            .withRpcPort(NodeConfigParams.NODE_RPC_PORT)
            .withRpcAdminPort(NodeConfigParams.NODE_RPC_ADMIN_PORT)
            .withDoormanURL(doormanURL)
            .withNetworkMapURL(networkMapURL)
            .withRpcUsername(rpcUsername)
            .withRpcPassword(rpcPassword)
            .withDataSourceClassName(dbParams.type.dataSourceClass)
            .withDataSourceURL(dbParams.jdbcURL)
            .withDataSourceUsername(NodeConfigParams.NODE_DATASOURCE_USERNAME_ENV_VAR_NAME.toEnvVar())
            .withDataSourcePassword(NodeConfigParams.NODE_DATASOURCE_PASSWORD_ENV_VAR_NAME.toEnvVar())
            .withAzureKeyVaultConfPath(NodeConfigParams.NODE_AZ_KV_CONFIG_PATH)
            .build()

        return ConfigGenerators.generateConfigFromParams(nodeConfigParams).also { this.generatedNodeConfig = it }
    }

    fun uploadNodeConfig(): AzureFilesDirectory {
        val configDirectory = shareCreator.createDirectoryFor("node-config", api)
        configDirectory.modernClient.rootDirectoryClient.getFileClient("node.conf")
            .uploadFromByteArray(generatedNodeConfig!!.toByteArray(Charsets.UTF_8))
        return configDirectory.also { this.configDirectory = it }
    }

    fun createNodeDatabaseSecrets(): NodeDatabaseSecrets {
        val nodeDatasourceSecretName = "node-datasource-secrets-$nodeId"
        val nodeDatasourceURLSecretKey = "node-datasource-url-$nodeId"
        val nodeDatasourceUsernameSecretKey = "node-datasource-user-$nodeId"
        val nodeDatasourcePasswordSecretyKey = "node-datasource-password-$nodeId"
        SecretCreator.createStringSecret(
            nodeDatasourceSecretName,
            listOf(
                nodeDatasourceURLSecretKey to dbParams.jdbcURL,
                nodeDatasourceUsernameSecretKey to dbParams.username,
                nodeDatasourcePasswordSecretyKey to dbParams.password
            ).toMap(),
            namespace,
            api
        )

        return NodeDatabaseSecrets(
            nodeDatasourceSecretName,
            nodeDatasourceURLSecretKey,
            nodeDatasourceUsernameSecretKey,
            nodeDatasourcePasswordSecretyKey
        ).also {
            this.databaseSecrets = it
        }
    }


    fun createNodeKeyStoreSecrets(): NodeStoresSecrets {
        val nodeStoresSecretName = "node-keystores-secrets-$nodeId"
        val nodeKeyStorePasswordSecretKey = "node-ssl-keystore-password-$nodeId"
        val sharedTrustStorePasswordSecretKey = "shared-truststore-password-$nodeId"

        SecretCreator.createStringSecret(
            nodeStoresSecretName,
            listOf(
                nodeKeyStorePasswordSecretKey to RandomStringUtils.randomAlphanumeric(20),
                sharedTrustStorePasswordSecretKey to RandomStringUtils.randomAlphanumeric(20)
            ).toMap(),
            namespace,
            api
        )

        return NodeStoresSecrets(nodeStoresSecretName, nodeKeyStorePasswordSecretKey, sharedTrustStorePasswordSecretKey).also {
            this.nodeStoresSecrets = it
        }
    }

    fun createKeyVaultSecrets(keyVaultSecrets: KeyVaultSecrets) {
        this.vaultSecrets = keyVaultSecrets
    }

    fun createArtemisSecrets(artemisSecrets: ArtemisSecrets) {
        this.artemisSecrets = artemisSecrets
    }


    fun copyArtemisStores(artemisStores: GeneratedArtemisStores) {
        val nodeArtemisDir = shareCreator.createDirectoryFor("node-artemis-stores-${nodeId}", api)

        val nodeArtemisTrustStore =
            nodeArtemisDir.modernClient.rootDirectoryClient.getFileClient(ArtemisConfigParams.ARTEMIS_TRUSTSTORE_FILENAME)
        val nodeArtemisKeyStore =
            nodeArtemisDir.modernClient.rootDirectoryClient.getFileClient(ArtemisConfigParams.ARTEMIS_NODE_KEYSTORE_FILENAME)

        nodeArtemisTrustStore.createFrom(artemisStores.trustStore)
        nodeArtemisKeyStore.createFrom(artemisStores.nodeStore)

        this.artemisStoresDir = nodeArtemisDir
    }

    suspend fun performInitialRegistration(
        keyVaultSecrets: KeyVaultSecrets,
        artemisSecrets: ArtemisSecrets,
        trustRootConfig: TrustRootConfig
    ): InitialRegistrationResult {
        val jobName = "initial-registration-${nodeId}"

        val initialRegResultDir = shareCreator.createDirectoryFor("node-initial-reg-result", api)
        val networkParamsDir = shareCreator.createDirectoryFor("network-params-result", api)

        val initialRegistrationJob = initialRegistrationJob(
            jobName,
            configDirectory!!,
            keyVaultSecrets,
            databaseSecrets!!,
            artemisSecrets,
            nodeStoresSecrets!!,
            initialRegResultDir,
            networkParamsDir,
            trustRootConfig
        )

        simpleApply.create(initialRegistrationJob, namespace, api)
        waitForJob(initialRegistrationJob, namespace, api)
        dumpLogsForJob(initialRegistrationJob, namespace, api)

        return InitialRegistrationResult(initialRegResultDir, networkParamsDir).also {
            this.initialRegistrationResult = it
        }
    }

    fun copyToDriversDir() {
        val driversDirShare = shareCreator.createDirectoryFor("node-drivers", api)
        val allDriverJars = (hsm.requiredDriverJars + dbParams.type.driverDependencies).flatMap {
            GradleUtils.getArtifactAndDependencies(it.driverGroup, it.driverArtifact, it.driverVersion)
        }

        val driverRoot = driversDirShare.modernClient.rootDirectoryClient
        allDriverJars.sorted().forEach { dependencyPath ->
            val fileName = dependencyPath.fileName.toString()
            println("uploading: ${dependencyPath.toFile().absolutePath} to drivers/${fileName}")
            val fileClient = driverRoot.getFileClient(fileName)
            if (!fileClient.exists()) {
                fileClient.create(Files.size(dependencyPath))
            }
            fileClient.uploadFromFile(dependencyPath.toAbsolutePath().toString())
        }

        this.driversDirShare = driversDirShare
    }

    fun deploy() {
        val nodeDeployment = createNodeDeployment(
            namespace,
            nodeId,
            artemisStoresDir,
            initialRegistrationResult!!.certificatesDir,
            configDirectory!!,
            driversDirShare,
            cordappsDirShare,
            artemisSecrets,
            nodeStoresSecrets!!,
            vaultSecrets,
            databaseSecrets!!
        )
        println(Yaml.dump(nodeDeployment))
        simpleApply.create(nodeDeployment, namespace, api)
    }

    fun copyToCordappsDir(cordapps: List<File>, gradleCordapps: List<File>) {
        val cordappsDir = shareCreator.createDirectoryFor("node-cordapps", api)

        (gradleCordapps + cordapps).forEach { cordapp ->
            println("Uploading cordapp: ${cordapp.absolutePath}")
            cordappsDir.modernClient.rootDirectoryClient.getFileClient(cordapp.name).also { client ->
                if (client.exists().not()) {
                    client.create(Files.size(cordapp.toPath()))
                }
            }.uploadFromFile(cordapp.absolutePath)
        }
        this.cordappsDirShare = cordappsDir
    }


}

enum class HsmType(
    val requiredDriverJars: List<GradleDependency>
) {
    AZURE(
        listOf(
            GradleDependency("net.corda.azure.hsm", "azure-keyvault-jar-builder", "1.0")
        )
    )
}

data class NodeDatabaseSecrets(
    val secretName: String,
    val nodeDataSourceURLKey: String,
    val nodeDataSourceUsernameKey: String,
    val nodeDatasourcePasswordKey: String
)

data class NodeStoresSecrets(
    val secretName: String,
    val nodeKeyStorePasswordKey: String,
    val sharedTrustStorePasswordKey: String
)

class InitialRegistrationResult(val certificatesDir: AzureFilesDirectory, val networkParamsDir: AzureFilesDirectory) {

    val networkParameters: ShareFileClient
        get() {
            return networkParamsDir.modernClient.rootDirectoryClient.getFileClient(NodeConfigParams.NETWORK_PARAMETERS_FILENAME)
                .enforceExistence()
        }
    val sharedTrustStore: ShareFileClient
        get() {
            return certificatesDir.modernClient.rootDirectoryClient.getFileClient(NodeConfigParams.NODE_TRUSTSTORE_FILENAME)
                .enforceExistence()
        }
}