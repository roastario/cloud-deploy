package net.corda.deployment.node.storage

import com.azure.storage.common.StorageSharedKeyCredential
import com.azure.storage.file.share.ShareClient
import com.azure.storage.file.share.ShareFileClient
import com.azure.storage.file.share.ShareServiceClient
import com.azure.storage.file.share.ShareServiceClientBuilder
import com.microsoft.azure.management.Azure
import com.microsoft.azure.management.resources.ResourceGroup
import com.microsoft.azure.management.storage.StorageAccount
import com.microsoft.azure.storage.CloudStorageAccount
import com.microsoft.azure.storage.file.CloudFile
import com.microsoft.azure.storage.file.CloudFileShare
import io.kubernetes.client.openapi.ApiClient
import net.corda.deployment.node.kubernetes.SecretCreator
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeoutException

data class PersistableAzureFileShareCreator(
    val id: String,
    val resourceGroup: String,
    val namespace: String,
    val instanceSpecificSuffix: String
)

data class PersistableShare(
    val shareName: String,
    val storageAccount: String,
    val storageSecrets: AzureFileSecrets
)

class AzureFileShareCreator(
    private val id: String,
    private val azure: Azure,
    private val resourceGroup: ResourceGroup,
    private val namespace: String,
    private val instanceSpecificSuffix: String
) {

    private val storageAccount = lazy {
        val storageName = instanceSpecificSuffix.toLowerCase()
        azure.storageAccounts().getByResourceGroup(resourceGroup.name(), storageName)
            ?: azure.storageAccounts().define(storageName)
                .withRegion(resourceGroup.region())
                .withExistingResourceGroup(resourceGroup)
                .withGeneralPurposeAccountKind()
                .create()
    }

    fun createSecrets(api: () -> ApiClient): AzureFileSecrets {
        val azureFilesSecretName = "files-secret-${instanceSpecificSuffix}"
        val storageAccountNameKey = "azurestorageaccountname"
        val storageAccountKeyKey = "azurestorageaccountkey"
        if (!SecretCreator.secretExists(azureFilesSecretName, namespace, api)) {
            SecretCreator.createStringSecret(
                azureFilesSecretName,
                listOf(
                    storageAccountNameKey to storageAccount.value.name(),
                    storageAccountKeyKey to storageAccount.value.keys[0].value()
                ).toMap(),
                namespace,
                api
            )
        }
        return AzureFileSecrets(azureFilesSecretName, storageAccountNameKey, storageAccountKeyKey)
    }

    fun createDirectoryFor(
        directoryName: String,
        api: () -> ApiClient
    ): AzureFilesDirectory {
        val storageAccount = storageAccount.value
        val cloudFileShare = getLegacyClient(storageAccount, directoryName)
        val modernClient = getModernClient(storageAccount, directoryName)
        val secrets = createSecrets(api)
        return AzureFilesDirectory(
            directoryName,
            cloudFileShare.also { it.createIfNotExists() },
            modernClient,
            storageAccount,
            secrets
        )
    }


    fun toPersistable(): PersistableAzureFileShareCreator {
        return PersistableAzureFileShareCreator(
            id = id,
            namespace = namespace,
            instanceSpecificSuffix = instanceSpecificSuffix,
            resourceGroup = resourceGroup.name()
        )
    }

    companion object {
        fun fromPersistable(p: PersistableAzureFileShareCreator, mgmAzure: Azure): AzureFileShareCreator {
            val resourceGroup = mgmAzure.resourceGroups().getByName(p.resourceGroup)
            return AzureFileShareCreator(
                p.id,
                mgmAzure,
                resourceGroup,
                namespace = p.namespace,
                instanceSpecificSuffix = p.instanceSpecificSuffix
            )
        }
    }
}

data class AzureFilesDirectory(
    val shareName: String,
    val legacyClient: CloudFileShare,
    val modernClient: ShareClient,
    val storageAccount: StorageAccount,
    val azureFileSecrets: AzureFileSecrets
) {
    fun toPersistable(): PersistableShare {
        return PersistableShare(
            shareName = shareName,
            storageAccount = storageAccount.id(),
            storageSecrets = azureFileSecrets
        )
    }

    companion object {
        fun fromPersistable(p: PersistableShare, mgmAzure: Azure): AzureFilesDirectory {
            val storageAccount = mgmAzure.storageAccounts().getById(p.storageAccount)
            val shareName = p.shareName
            val legacyClient = getLegacyClient(storageAccount, shareName)
            val modernClient = getModernClient(storageAccount, shareName)
            return AzureFilesDirectory(shareName, legacyClient, modernClient, storageAccount, p.storageSecrets)
        }
    }
}

fun CloudFile.uploadFromByteArray(array: ByteArray) {
    this.uploadFromByteArray(array, 0, array.size)
}

fun ShareFileClient.uploadFromByteArray(array: ByteArray) {
    if (!this.exists()) {
        this.create(array.size.toLong())
    }
    this.upload(array.inputStream(), array.size.toLong())
}

fun ShareFileClient.enforceExistence(): ShareFileClient {
    return this.also {
        if (!it.exists()) {
            throw IllegalStateException("no such file: ${it.filePath}")
        }
    }
}

private fun getModernClient(storageAccount: StorageAccount, directoryName: String): ShareClient {
    val fileEndPoint = storageAccount.endPoints().primary().file()
    val modernClient = ShareServiceClientBuilder()
        .endpoint(fileEndPoint)
        .credential(StorageSharedKeyCredential(storageAccount.name(), storageAccount.keys[0].value()))
        .buildClient()

    return modernClient.getShareClient(directoryName).also {
        if (!it.exists()) {
            it.create()
        }
    }
}

private fun getLegacyClient(
    storageAccount: StorageAccount,
    directoryName: String,
    timeout: Duration = Duration.ofSeconds(300)
): CloudFileShare {
    val startTime = Instant.now()
    while (Instant.now().isBefore(startTime.plusSeconds(timeout.seconds))) {
        try {
            return CloudStorageAccount.parse(
                "DefaultEndpointsProtocol=https;" +
                        "AccountName=${storageAccount.name()};" +
                        "AccountKey=${storageAccount.keys[0].value()};" +
                        "EndpointSuffix=core.windows.net"
            ).createCloudFileClient().getShareReference(directoryName)
        } catch (e: Exception) {
            println("Still waiting for storage account to be visible on DNS")
            Thread.sleep(1000)
        }
    }
    throw TimeoutException("Storage account could not be retrieved within timeout")
}


data class AzureFileSecrets(
    val secretName: String,
    val storageAccountNameKey: String = "azurestorageaccountname",
    val storageAccountKeyKey: String = "azurestorageaccountkey"
)