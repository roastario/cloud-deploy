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
import net.corda.deployment.node.storage.AzureFileShareCreator
import net.corda.deployment.node.storage.AzureFilesDirectory
import net.corda.deployment.node.storage.enforceExistence
import net.corda.deployments.node.config.ArtemisConfigParams
import org.apache.commons.lang3.RandomStringUtils
import kotlin.IllegalStateException

class ArtemisSetup(
    private val azure: Azure,
    private val resourceGroup: ResourceGroup,
    private val shareCreator: AzureFileShareCreator,
    private val namespace: String,
    private val randomSuffix: String,
    private val apiSource: () -> ApiClient
) {

    private var configuredBroker: ConfiguredArtemisBroker? = null
    private var generatedStores: GeneratedArtemisStores? = null
    private var secrets: ArtemisSecrets? = null

    private fun createDiskForArtemis(): Disk {
        return azure.disks().define("artemis-$randomSuffix")
            .withRegion(resourceGroup.region())
            .withExistingResourceGroup(resourceGroup)
            .withData()
            .withSizeInGB(200)
            .withSku(DiskSkuTypes.PREMIUM_LRS)
            .create()
    }

    fun generateArtemisSecrets(): ArtemisSecrets {
        val artemisSecretsName = "artemis-secrets-${randomSuffix}"
        val artemisStorePassSecretKey = "artemisstorepass"
        val artemisTrustPassSecretKey = "artemistrustpass"
        val artemisClusterPassSecretKey = "artemisclusterpass";
        val artemisSecret = SecretCreator.createStringSecret(
            artemisSecretsName,
            listOf(
                artemisStorePassSecretKey to RandomStringUtils.randomAlphanumeric(32),
                artemisTrustPassSecretKey to RandomStringUtils.randomAlphanumeric(32),
                artemisClusterPassSecretKey to RandomStringUtils.randomAlphanumeric(32)
            ).toMap()
            , namespace, apiSource
        )

        secrets = ArtemisSecrets(
            artemisSecretsName,
            artemisStorePassSecretKey,
            artemisTrustPassSecretKey,
            artemisClusterPassSecretKey
        )
        return secrets as ArtemisSecrets
    }

    fun generateArtemisStores(): GeneratedArtemisStores {
        if (secrets == null) {
            throw IllegalStateException("Must generate artemis secrets before generating stores")
        }
        val workingDir = shareCreator.createDirectoryFor("artemis-stores")
        val jobName = "generate-artemis-stores-$randomSuffix"
        val generateArtemisStoresJob = generateArtemisStoresJob(jobName, secrets!!, workingDir)
        simpleApply.create(generateArtemisStoresJob, namespace, apiSource)
        waitForJob(generateArtemisStoresJob, namespace, apiSource)
        dumpLogsForJob(generateArtemisStoresJob, namespace, apiSource)
        return GeneratedArtemisStores(workingDir).also {
            this.generatedStores = it
        }
    }

    fun configureArtemisBroker(): ConfiguredArtemisBroker {
        if (generatedStores == null) {
            throw IllegalStateException("Must generate artemis stores before configuring broker")
        }
        val workingDirShare = shareCreator.createDirectoryFor("artemis-broker")
        val jobName = "configure-artemis-broker-$randomSuffix"
        val configureArtemisJob = configureArtemis(
            jobName,
            secrets!!,
            generatedStores!!,
            workingDirShare
        )
        simpleApply.create(configureArtemisJob, namespace, apiSource)
        waitForJob(configureArtemisJob, namespace, apiSource)
        dumpLogsForJob(configureArtemisJob, namespace, apiSource)
        return ConfiguredArtemisBroker(workingDirShare).also {
            this.configuredBroker = it
        }
    }

    fun deploy(
        useAzureDiskForData: Boolean = false
    ): ArtemisDeployment {
        if (configuredBroker == null) {
            throw IllegalStateException("Must configure artemis broker before deploying")
        }
        val disk = if (useAzureDiskForData) {
            createDiskForArtemis()
        } else {
            null
        }
        val deployment = createArtemisDeployment(namespace, configuredBroker!!, generatedStores!!, disk, randomSuffix)
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




