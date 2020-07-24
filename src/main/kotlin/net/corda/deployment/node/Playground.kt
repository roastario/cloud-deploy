package net.corda.deployment.node

import com.azure.storage.file.share.ShareFileClient
import com.microsoft.azure.credentials.AzureCliCredentials
import com.microsoft.azure.management.Azure
import com.microsoft.rest.LogLevel
import io.kubernetes.client.util.ClientBuilder
import io.kubernetes.client.util.Yaml
import net.corda.deployment.node.database.H2_DB
import net.corda.deployment.node.kubernetes.allowAllFailures
import net.corda.deployment.node.storage.AzureFileShareCreator
import org.apache.commons.lang3.RandomStringUtils
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Security
import java.time.Duration
import kotlin.system.exitProcess


@ExperimentalUnsignedTypes
fun main(args: Array<String>) {

    val nodeX500 = "O=BigCorporation,L=New York,C=US"
    val nodeEmail = "stefano.franz@r3.com"
    val nodeP2PAddress = "localhost"
    val doormanURL = "http://networkservices:8080"
    val networkMapURL = "http://networkservices:8080"
    val rpcUsername = "u"
    val rpcPassword = "p"
    val dbParams = H2_DB

    val defaultClientSource = { ClientBuilder.defaultClient() }

    val nmsSetup = Yaml.loadAll(Thread.currentThread().contextClassLoader.getResourceAsStream("yaml/dummynms.yaml").reader())
    val namespace = "testingzone"
    allowAllFailures { simpleApply.apply(nmsSetup, namespace = namespace) }
    val bouncyCastleProvider = BouncyCastleProvider()
    Security.addProvider(bouncyCastleProvider)

    val mngAzure: Azure = Azure.configure()
        .withLogLevel(LogLevel.BODY_AND_HEADERS)
        .authenticate(AzureCliCredentials.create())
        .withSubscription("c412941a-4362-4923-8737-3d33a8d1cdc6")

    val resourceGroup = mngAzure.resourceGroups().getByName("stefano-playground")
    val randSuffix = RandomStringUtils.randomAlphanumeric(8).toLowerCase()
    val azureFileShareCreator = AzureFileShareCreator(
        azure = mngAzure,
        resourceGroup = resourceGroup,
        runSuffix = randSuffix,
        namespace = namespace,
        api = defaultClientSource
    )
    /// END CONSTANTS ///


    //configure key vault
    val keyVaultSetup = KeyVaultSetup(mngAzure, resourceGroup, azureFileShareCreator, namespace, randSuffix)
    val vaultAndCredentials = keyVaultSetup.createKeyVaultWithServicePrincipal()
    val vaultConfig = keyVaultSetup.generateKeyVaultCryptoServiceConfig()
    val vaultSecrets = keyVaultSetup.createKeyVaultSecrets(defaultClientSource)

    //configure and deploy artemis
    val artemisSetup = ArtemisSetup(mngAzure, resourceGroup, azureFileShareCreator, namespace, randSuffix)
    val artemisSecrets = artemisSetup.generateArtemisSecrets(defaultClientSource)
    val generatedArtemisStores = artemisSetup.generateArtemisStores(defaultClientSource)
    val configuredArtemisBroker = artemisSetup.configureArtemisBroker(defaultClientSource)
    val deployedArtemis = artemisSetup.deploy(defaultClientSource)


    //configure and register the node
    val nodeSetup = NodeSetup(azureFileShareCreator, dbParams, namespace, defaultClientSource, randSuffix)
    nodeSetup.generateNodeConfig(
        nodeX500,
        nodeEmail,
        nodeP2PAddress,
        deployedArtemis.serviceName,
        doormanURL,
        networkMapURL,
        rpcUsername,
        rpcPassword
    )
    nodeSetup.uploadNodeConfig()
    nodeSetup.createNodeDatabaseSecrets()
    val nodeStoreSecrets = nodeSetup.createNodeKeyStoreSecrets()
    val initialRegistrationResult = nodeSetup.performInitialRegistration(vaultSecrets, artemisSecrets)

    //setup the firewall tunnel
    val firewallSetup = FirewallSetup(namespace, azureFileShareCreator, randSuffix)
    val firewallTunnelSecrets = firewallSetup.generateFirewallTunnelSecrets(defaultClientSource)
    val firewallTunnelStores = firewallSetup.generateTunnelStores(defaultClientSource)

    //configure and deploy the float
    val floatSetup = FloatSetup(namespace, azureFileShareCreator, randSuffix)
    floatSetup.copyTunnelStoreComponents(firewallTunnelStores)
    floatSetup.createTunnelSecrets(firewallTunnelSecrets)
    floatSetup.generateConfig()
    floatSetup.uploadConfig()
    val floatDeployment = floatSetup.deploy(defaultClientSource)

    //configure and deploy the bridge
    val bridgeSetup = BridgeSetup(azureFileShareCreator, namespace, randSuffix)
    val bridgeStoreSecrets = bridgeSetup.generateBridgeStoreSecrets(defaultClientSource)
    val bridgeStores = bridgeSetup.importNodeKeyStoreIntoBridge(nodeStoreSecrets, initialRegistrationResult, defaultClientSource)
    bridgeSetup.copyTrustStoreFromNodeRegistrationResult(initialRegistrationResult)
    val bridgeTunnelComponents = bridgeSetup.copyBridgeTunnelStoreComponents(firewallTunnelStores)
    val bridgeArtemisComponents = bridgeSetup.copyBridgeArtemisStoreComponents(generatedArtemisStores)
    bridgeSetup.copyNetworkParametersFromNodeRegistrationResult(initialRegistrationResult)
    bridgeSetup.createTunnelSecrets(firewallTunnelSecrets)
    bridgeSetup.generateBridgeConfig(deployedArtemis.serviceName, floatDeployment.internalAddress)
    bridgeSetup.uploadBridgeConfig()
    bridgeSetup.createArtemisSecrets(artemisSecrets)
    val bridgeDeployment = bridgeSetup.deploy(defaultClientSource)


    //continue setting up the node
    nodeSetup.copyArtemisStores(generatedArtemisStores)
    nodeSetup.createArtemisSecrets(artemisSecrets)


    exitProcess(0)
}

fun ShareFileClient.createFrom(source: ShareFileClient, timeout: Duration = Duration.ofMinutes(5)) {
    val sizeToCopy = source.properties.contentLength
    if (!this.exists()) {
        this.create(sizeToCopy)
    } else {
        this.delete()
        this.create(sizeToCopy)
    }
    val poller = this.beginCopy(
        source.fileUrl,
        null,
        null
    )
    poller.waitForCompletion(timeout)
}


fun String.toEnvVar(): String {
    return "\${$this}"
}