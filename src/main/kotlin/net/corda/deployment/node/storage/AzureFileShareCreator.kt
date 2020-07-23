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
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeoutException


class AzureFileShareCreator(
    val azure: Azure,
    val resourceGroup: ResourceGroup,
    val runSuffix: String
) {

    fun createDirectoryFor(
        directoryName: String
    ): AzureFilesDirectory {
        val storageAccount = getStorageAccount()
        val cloudFileShare = getLegacyClient(storageAccount, directoryName)
        val modernClient = getModernClient()
        return AzureFilesDirectory(
            cloudFileShare.also { it.createIfNotExists() },
            modernClient.getShareClient(directoryName).also {
                if (!it.exists()) {
                    it.create()
                }
            },
            storageAccount
        )
    }

    private fun getModernClient(): ShareServiceClient {
        val storageAccount = getStorageAccount()
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

    private fun getStorageAccount(): StorageAccount {
        val storageName = "cordafiles$runSuffix"
        return azure.storageAccounts().getByResourceGroup(resourceGroup.name(), storageName)
            ?: azure.storageAccounts().define(storageName)
                .withRegion(resourceGroup.region())
                .withExistingResourceGroup(resourceGroup)
                .withSku(StorageAccountSkuType.PREMIUM_LRS)
                .withFileStorageAccountKind()
                .create()
    }
}

data class AzureFilesDirectory(
    val legacyClient: CloudFileShare,
    val modernClient: ShareClient,
    val storageAccount: StorageAccount
)

fun CloudFile.uploadFromByteArray(array: ByteArray) {
    this.uploadFromByteArray(array, 0, array.size)
}

data class AzureFileSecrets(val secretName: String, )