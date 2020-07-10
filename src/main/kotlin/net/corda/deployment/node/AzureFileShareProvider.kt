package net.corda.deployment.node

import com.microsoft.azure.management.Azure
import com.microsoft.azure.management.resources.ResourceGroup
import com.microsoft.azure.management.storage.StorageAccount
import com.microsoft.azure.storage.CloudStorageAccount
import com.microsoft.azure.storage.file.CloudFileShare

fun getStorageAccount(azure: Azure, resourceGroup: ResourceGroup, runId: String): StorageAccount {
    return azure.storageAccounts().getByResourceGroup(resourceGroup.name(), "fileStore$runId")
        ?: azure.storageAccounts().define("fileStore$runId")
            .withRegion(resourceGroup.region())
            .withExistingResourceGroup(resourceGroup)
            .create()
}

fun createDirectoryFor(
    azure: Azure,
    storageAccount: StorageAccount,
    runId: String,
    directoryName: String
): CloudFileShare {
    val cloudFileShare = CloudStorageAccount.parse(
        "DefaultEndpointsProtocol=https;" +
                "AccountName=${storageAccount.name()};" +
                "AccountKey=${storageAccount.keys[0]};" +
                "EndpointSuffix=core.windows.net"
    ).createCloudFileClient().getShareReference(directoryName + runId)
    return cloudFileShare.also { it.createIfNotExists() }
}
