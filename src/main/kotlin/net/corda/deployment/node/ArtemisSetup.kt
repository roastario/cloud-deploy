package net.corda.deployment.node

import com.azure.storage.file.share.ShareFileClient
import com.microsoft.azure.management.Azure
import com.microsoft.azure.management.compute.Disk
import com.microsoft.azure.management.compute.DiskSkuTypes
import com.microsoft.azure.management.resources.ResourceGroup
import io.kubernetes.client.openapi.ApiClient
import io.kubernetes.client.openapi.models.V1Deployment
import io.kubernetes.client.openapi.models.V1Service
import net.corda.deployment.node.kubernetes.SecretCreator
import net.corda.deployment.node.kubernetes.simpleApply
import net.corda.deployment.node.storage.AzureFilesDirectory
import net.corda.deployment.node.storage.enforceExistence
import net.corda.deployments.node.config.ArtemisConfigParams
import org.apache.commons.lang3.RandomStringUtils
import kotlin.IllegalStateException

class ArtemisSetup(
    private val azure: Azure,
    private val resourceGroup: ResourceGroup,
    private val namespace: String,
    private val apiSource: () -> ApiClient
) {

//    private var secrets: ArtemisSecrets? = null

    private fun createDiskForArtemis(): Disk {
        return azure.disks().define("artemis-disk")
            .withRegion(resourceGroup.region())
            .withExistingResourceGroup(resourceGroup)
            .withData()
            .withSizeInGB(500)
            .withSku(DiskSkuTypes.PREMIUM_LRS)
            .create()
    }

    fun generateArtemisSecrets(): ArtemisSecrets {
        val artemisSecretsName = "artemis-secrets"
        val artemisStorePassSecretKey = "artemisstorepass"
        val artemisTrustPassSecretKey = "artemistrustpass"
        val artemisClusterPassSecretKey = "artemisclusterpass"
        if (!SecretCreator.secretExists(artemisSecretsName, namespace, apiSource)) {
            SecretCreator.createStringSecret(
                artemisSecretsName,
                listOf(
                    artemisStorePassSecretKey to RandomStringUtils.randomAlphanumeric(32),
                    artemisTrustPassSecretKey to RandomStringUtils.randomAlphanumeric(32),
                    artemisClusterPassSecretKey to RandomStringUtils.randomAlphanumeric(32)
                ).toMap(),
                namespace,
                apiSource
            )
        }
        return ArtemisSecrets(
            artemisSecretsName,
            artemisStorePassSecretKey,
            artemisTrustPassSecretKey,
            artemisClusterPassSecretKey
        )
    }

    suspend fun generateArtemisStores(
        artemisSecrets: ArtemisSecrets,
        artemisShare: AzureFilesDirectory,
        nodeArtemisShare: AzureFilesDirectory,
        bridgeArtemisShare: AzureFilesDirectory
    ) {
        val jobName = "generate-artemis-stores"
        val generateArtemisStoresJob = generateArtemisStoresJob(
            jobName,
            artemisSecrets,
            artemisShare,
            nodeArtemisShare,
            bridgeArtemisShare
        )
        simpleApply.create(generateArtemisStoresJob, namespace, apiSource)
        waitForJob(generateArtemisStoresJob, namespace, apiSource)
        dumpLogsForJob(generateArtemisStoresJob, namespace, apiSource)
    }

    suspend fun configureArtemisBroker(
        artemisSecrets: ArtemisSecrets,
        artemisBrokerDir: AzureFilesDirectory,
        artemisStoresDir: AzureFilesDirectory
    ) {
        val jobName = "configure-artemis-broker"
        val configureArtemisJob = configureArtemis(
            jobName,
            artemisSecrets,
            artemisStoresDir,
            artemisBrokerDir
        )
        simpleApply.create(configureArtemisJob, namespace, apiSource)
        waitForJob(configureArtemisJob, namespace, apiSource)
        dumpLogsForJob(configureArtemisJob, namespace, apiSource)
    }

    fun deploy(
        storesDirectory: AzureFilesDirectory,
        brokerDirectory: AzureFilesDirectory,
        useAzureDiskForData: Boolean = false
    ): ArtemisDeployment {
        val disk = if (useAzureDiskForData) {
            createDiskForArtemis()
        } else {
            null
        }
        val deployment = createArtemisDeployment(namespace, brokerDirectory, storesDirectory, disk)
        val service = createArtemisService(deployment)
        simpleApply.create(deployment, namespace, apiSource)
        simpleApply.create(service, namespace, apiSource)
        return ArtemisDeployment(deployment, service)
    }
}

class ArtemisDeployment(val deployment: V1Deployment, val service: V1Service) {
    val serviceName: String
        get() = service.metadata?.name
            ?: throw IllegalStateException("artemis service name not available, something has gone seriously wrong")
}

class ConfiguredArtemisBroker(val baseDir: AzureFilesDirectory)

class GeneratedArtemisStores(val outputDir: AzureFilesDirectory) {
    val nodeStore: ShareFileClient
        get() {
            return outputDir.modernClient.rootDirectoryClient.getFileClient(ArtemisConfigParams.ARTEMIS_NODE_KEYSTORE_FILENAME)
                .enforceExistence()
        }
    val trustStore: ShareFileClient
        get() {
            return outputDir.modernClient.rootDirectoryClient.getFileClient(ArtemisConfigParams.ARTEMIS_TRUSTSTORE_FILENAME)
                .enforceExistence()
        }
    val bridgeStore: ShareFileClient
        get() {
            return outputDir.modernClient.rootDirectoryClient.getFileClient(ArtemisConfigParams.ARTEMIS_BRIDGE_KEYSTORE_FILENAME)
                .enforceExistence()
        }
}

data class ArtemisSecrets(
    val secretName: String,
    val keyStorePasswordKey: String,
    val trustStorePasswordKey: String,
    val clusterPasswordKey: String
)




