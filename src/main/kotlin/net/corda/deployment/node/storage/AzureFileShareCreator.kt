package net.corda.deployment.node.storage

import com.azure.storage.common.StorageSharedKeyCredential
import com.azure.storage.file.share.ShareClient
import com.azure.storage.file.share.ShareServiceClient
import com.azure.storage.file.share.ShareServiceClientBuilder
import com.microsoft.azure.management.Azure
import com.microsoft.azure.management.resources.ResourceGroup
import com.microsoft.azure.management.storage.StorageAccount
import com.microsoft.azure.management.storage.StorageAccountSkuType
import com.microsoft.azure.storage.CloudStorageAccount
import com.microsoft.azure.storage.file.CloudFile
import com.microsoft.azure.storage.file.CloudFileShare
import io.kubernetes.client.openapi.ApiClient
import net.corda.deployment.node.kubernetes.SecretCreator
import org.apache.commons.lang3.RandomStringUtils
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeoutException


class AzureFileShareCreator(
    private val azure: Azure,
    private val resourceGroup: ResourceGroup,
    private val runSuffix: String,
    private val namespace: String,
    private val api: () -> ApiClient
) {

    private val instanceSpecificSuffix = RandomStringUtils.randomAlphanumeric(4).toLowerCase()

    private val storageAccount = lazy {
        val storageName = "cordafiles$runSuffix${instanceSpecificSuffix}".toLowerCase()
        azure.storageAccounts().getByResourceGroup(resourceGroup.name(), storageName)
            ?: azure.storageAccounts().define(storageName)
                .withRegion(resourceGroup.region())
                .withExistingResourceGroup(resourceGroup)
                .withSku(StorageAccountSkuType.PREMIUM_LRS)
                .withFileStorageAccountKind()
                .create()
    }

    val secrets = lazy {
        val azureFilesSecretName = "files-secret-$runSuffix-${instanceSpecificSuffix}"
        val storageAccountNameKey = "azurestorageaccountname"
        val storageAccountKeyKey = "azurestorageaccountkey"
        SecretCreator.createStringSecret(
            azureFilesSecretName,
            listOf(
                storageAccountNameKey to storageAccount.value.name(),
                storageAccountKeyKey to storageAccount.value.keys[0].value()
            ).toMap(),
            namespace,
            api
        )
        AzureFileSecrets(azureFilesSecretName, storageAccountNameKey, storageAccountKeyKey)
    }

    fun createDirectoryFor(
        directoryName: String
    ): AzureFilesDirectory {
        val storageAccount = storageAccount.value
        val cloudFileShare = getLegacyClient(storageAccount, directoryName)
        val modernClient = getModernClient()
        return AzureFilesDirectory(
            cloudFileShare.also { it.createIfNotExists() },
            modernClient.getShareClient(directoryName).also {
                if (!it.exists()) {
                    it.create()
                }
            },
            storageAccount,
            secrets.value
        )
    }

    private fun getModernClient(): ShareServiceClient {
        val storageAccount = storageAccount.value
        val shareServiceClient = ShareServiceClientBuilder()
            .endpoint("https://${storageAccount.name()}.file.core.windows.net")
            .credential(StorageSharedKeyCredential(storageAccount.name(), storageAccount.keys[0].value()))
            .buildClient()
        return shareServiceClient
    }

    private fun getLegacyClient(
        storageAccount: StorageAccount,
        directoryName: String,
        timeout: Duration = Duration.ofSeconds(300)
    ): CloudFileShare {

        val startTime = Instant.now()

        while (Instant.now().isBefore(startTime.plusSeconds(timeout.seconds))) {
            try {
                val cloudFileShare = CloudStorageAccount.parse(
                    "DefaultEndpointsProtocol=https;" +
                            "AccountName=${storageAccount.name()};" +
                            "AccountKey=${storageAccount.keys[0].value()};" +
                            "EndpointSuffix=core.windows.net"
                ).createCloudFileClient().getShareReference(directoryName)
                return cloudFileShare
            } catch (e: Exception) {
                println("Still waiting for storage account to be visible on DNS")
                Thread.sleep(10)
            }
        }


        throw TimeoutException("Storage account could not be retrieved within timeout")
    }
}

data class AzureFilesDirectory(
    val legacyClient: CloudFileShare,
    val modernClient: ShareClient,
    val storageAccount: StorageAccount,
    val azureFileSecrets: AzureFileSecrets
)

fun CloudFile.uploadFromByteArray(array: ByteArray) {
    this.uploadFromByteArray(array, 0, array.size)
}

data class AzureFileSecrets(
    val secretName: String,
    val storageAccountNameKey: String = "azurestorageaccountname",
    val storageAccountKeyKey: String = "azurestorageaccountkey"
)