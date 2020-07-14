package net.corda.deployment.node

import com.microsoft.azure.credentials.AzureCliCredentials
import com.microsoft.azure.management.Azure
import com.microsoft.rest.LogLevel
import io.kubernetes.client.util.ClientBuilder
import net.corda.deployment.node.config.ConfigGenerators
import net.corda.deployment.node.database.H2_DB
import net.corda.deployment.node.hsm.KeyVaultCreator
import net.corda.deployment.node.kubernetes.SecretCreator
import net.corda.deployment.node.principals.ServicePrincipalCreator
import net.corda.deployment.node.storage.AzureFileShareCreator
import net.corda.deployment.node.storage.uploadFromByteArray
import net.corda.deployments.node.config.AzureKeyVaultConfigParams
import net.corda.deployments.node.config.NodeConfigParams
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Security
import kotlin.random.Random
import kotlin.random.nextUInt

@ExperimentalUnsignedTypes
fun main(args: Array<String>) {

    val bouncyCastleProvider = BouncyCastleProvider()
    Security.addProvider(bouncyCastleProvider)

    val mngAzure: Azure = Azure.configure()
        .withLogLevel(LogLevel.BODY_AND_HEADERS)
        .authenticate(AzureCliCredentials.create())
        .withSubscription("c412941a-4362-4923-8737-3d33a8d1cdc6")


    val resourceGroup = mngAzure.resourceGroups().getByName("stefano-playground")
    val randSuffix = Random.nextUInt().toString(36).toLowerCase()

    val defaultClient = ClientBuilder.defaultClient().also { it.isDebugging = true }

    val azureFileShareCreator = AzureFileShareCreator(azure = mngAzure, resourceGroup = resourceGroup, runSuffix = randSuffix)

    val configDir = azureFileShareCreator.createDirectoryFor("config")
    val hsmDir = azureFileShareCreator.createDirectoryFor("hsm")
    SecretCreator.createStringSecret(
        "azure-files-secret",
        listOf(
            "azurestorageaccountname" to configDir.storageAccount.name(),
            "azurestorageaccountkey" to configDir.storageAccount.keys[0].value()
        ).toMap()
        , "default", defaultClient
    )

    val dbParams = H2_DB

    val nodeConfigParams = NodeConfigParams.builder()
        .withX500Name("O=BigCorporation,L=New York,C=US")
        .withNodeSSLKeystorePassword("thisIsAP@ssword")
        .withNodeTrustStorePassword("thisIsAP@ssword")
        .withP2pAddress("localhost")
        .withP2pPort(1234)
        .withArtemisServerAddress("localhost")
        .withArtemisServerPort(1234)
        .withArtemisSSLKeyStorePath("/opt/corda/somewhere")
        .withArtemisSSLKeyStorePass("thisIsAP@ssword")
        .withArtemisTrustStorePath("/opt/corda/somewhere")
        .withArtemisTrustStorePass("thisIsAP@ssword")
        .withRpcPort(1234)
        .withRpcAdminPort(1234)
        .withDoormanURL("http://springbootnetworkmap:8080")
        .withNetworkMapURL("http://springbootnetworkmap:8080")
        .withRpcUsername("u")
        .withRpcPassword("p")
        .withDataSourceClassName(dbParams.type.dataSourceClass)
        .withDataSourceURL(dbParams.jdbcURL)
        .withDataSourceUsername(dbParams.username)
        .withDataSourcePassword(dbParams.password)
        .withAzureKeyVaultConfPath(NodeConfigParams.NODE_AZ_KV_CONFIG_PATH)
        .build()

    val servicePrincipalCreator = ServicePrincipalCreator(azure = mngAzure, resourceGroup = resourceGroup, runSuffix = randSuffix)
    val keyVaultCreator = KeyVaultCreator(azure = mngAzure, resourceGroup = resourceGroup, runSuffix = randSuffix)
    val servicePrincipal = servicePrincipalCreator.createClusterServicePrincipal()
    val vault = keyVaultCreator.createKeyVaultAndConfigureServicePrincipalAccess(servicePrincipal)

    val keyVaultParams = AzureKeyVaultConfigParams
        .builder()
        .withServicePrincipalCredentialsFilePath(AzureKeyVaultConfigParams.CREDENTIALS_P12_PATH)
        .withServicePrincipalCredentialsFilePassword(servicePrincipal.p12FilePassword)
        .withKeyVaultClientId(servicePrincipal.servicePrincipal.applicationId())
        .withKeyAlias(servicePrincipal.p12KeyAlias)
        .withKeyVaultURL(vault.vaultUri())
        .withKeyVaultProtectionMode(AzureKeyVaultConfigParams.KEY_PROTECTION_MODE_SOFTWARE)
        .build()

    val keyVaultCredentialFileReference = hsmDir.fileShare.rootDirectoryReference.getFileReference(AzureKeyVaultConfigParams.CREDENTIALS_P12_FILENAME)
    //upload p12 ready for use to auth
    keyVaultCredentialFileReference.uploadFromByteArray(servicePrincipal.p12File.readBytes())

    val nodeConfigFileReference = configDir.fileShare.rootDirectoryReference.getFileReference(NodeConfigParams.NODE_CONFIG_FILENAME)
    val nodeConf = ConfigGenerators.generateConfigFromParams(nodeConfigParams)
    //upload node.conf
    nodeConfigFileReference.uploadFromByteArray(nodeConf.toByteArray())


    val azureKeyVaultConfigFileReference = configDir.fileShare.rootDirectoryReference.getFileReference(NodeConfigParams.NODE_AZ_KV_CONFIG_FILENAME)
    val azKvConf = ConfigGenerators.generateConfigFromParams(keyVaultParams)
    azureKeyVaultConfigFileReference.uploadFromByteArray(azKvConf.toByteArray())
    println()

}