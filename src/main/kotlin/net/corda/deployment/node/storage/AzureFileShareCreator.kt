package net.corda.deployment.node.storage

import com.microsoft.azure.management.Azure
import com.microsoft.azure.management.resources.ResourceGroup
import com.microsoft.azure.management.storage.StorageAccount
import com.microsoft.azure.storage.CloudStorageAccount
import com.microsoft.azure.storage.file.CloudFile
import com.microsoft.azure.storage.file.CloudFileShare

class AzureFileShareCreator(val azure: Azure, val resourceGroup: ResourceGroup, val runSuffix: String) {

    fun createDirectoryFor(
        directoryName: String
    ): AzureFilesDirectory {
        val storageAccount = getStorageAccount(azure, resourceGroup)
        val cloudFileShare = CloudStorageAccount.parse(
            "DefaultEndpointsProtocol=https;" +
                    "AccountName=${storageAccount.name()};" +
                    "AccountKey=${storageAccount.keys[0].value()};" +
                    "EndpointSuffix=core.windows.net"
        ).createCloudFileClient().getShareReference(directoryName)
        return AzureFilesDirectory(cloudFileShare.also { it.createIfNotExists() }, storageAccount)
    }

    private fun getStorageAccount(azure: Azure, resourceGroup: ResourceGroup): StorageAccount {
        val storageName = "cordafiles$runSuffix"
        return azure.storageAccounts().getByResourceGroup(resourceGroup.name(), storageName)
            ?: azure.storageAccounts().define(storageName)
                .withRegion(resourceGroup.region())
                .withExistingResourceGroup(resourceGroup)
                .create()
    }
}

data class AzureFilesDirectory(val fileShare: CloudFileShare, val storageAccount: StorageAccount)

fun CloudFile.uploadFromByteArray(array: ByteArray) {
    this.uploadFromByteArray(array, 0, array.size)
}