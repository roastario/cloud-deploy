package net.corda.deployment.node

import com.azure.storage.file.share.ShareFileClient
import com.microsoft.azure.credentials.AzureCliCredentials
import com.microsoft.azure.management.Azure
import com.microsoft.rest.LogLevel
import io.kubernetes.client.util.Yaml
import net.corda.deployment.node.database.H2_DB
import net.corda.deployment.node.float.FloatSetup
import net.corda.deployment.node.infrastructure.AzureInfrastructureDeployer
import net.corda.deployment.node.storage.AzureFileShareCreator
import net.corda.deployment.node.storage.uploadFromByteArray
import org.apache.commons.lang3.RandomStringUtils
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Security
import java.time.Duration
import kotlin.system.exitProcess


@ExperimentalUnsignedTypes
fun main(args: Array<String>) {

    val bouncyCastleProvider = BouncyCastleProvider()
    Security.addProvider(bouncyCastleProvider)

    val mngAzure: Azure = Azure.configure()
        .withLogLevel(LogLevel.BODY_AND_HEADERS)
        .authenticate(AzureCliCredentials.create())
        .withSubscription("c412941a-4362-4923-8737-3d33a8d1cdc6")

    val resourceGroup = mngAzure.resourceGroups().getByName("stefano-playground")

    val randSuffix = RandomStringUtils.randomAlphanumeric(8).toLowerCase()
    val azureInfrastructureDeployer = AzureInfrastructureDeployer(mngAzure, resourceGroup, randSuffix)
    val infrastructure = azureInfrastructureDeployer.setupInfrastructure()
    val namespace = "testingzone"
    val dmzShareCreator: AzureFileShareCreator = infrastructure.dmzShareCreator(namespace)
    val nonDmzShareCreator: AzureFileShareCreator = infrastructure.internalShareCreator(namespace)

    val nodeX500 = "O=BigCorporation,L=New York,C=US"
    val nodeEmail = "stefano.franz@r3.com"
    val doormanURL = "http://networkservices:8080"
    val networkMapURL = "http://networkservices:8080"
    val rpcUsername = "u"
    val rpcPassword = "p"
    val dbParams = H2_DB

    val namespaceYaml = Yaml.loadAll(Thread.currentThread().contextClassLoader.getResourceAsStream("yaml/namespace.yaml").reader())
    val nmsSetup = Yaml.loadAll(Thread.currentThread().contextClassLoader.getResourceAsStream("yaml/dummynms.yaml").reader())

    simpleApply.apply(namespaceYaml, namespace = namespace, apiClient = infrastructure.clusters.nonDmzApiSource())
    simpleApply.apply(namespaceYaml, namespace = namespace, apiClient = infrastructure.clusters.dmzApiSource())
    simpleApply.apply(nmsSetup, namespace = namespace, apiClient = infrastructure.clusters.nonDmzApiSource())
    /// END CONSTANTS ///

    //configure key vault
    val keyVaultSetup = infrastructure.keyVaultSetup(namespace)
    keyVaultSetup.generateKeyVaultCryptoServiceConfig()
    val vaultSecrets = keyVaultSetup.createKeyVaultSecrets(infrastructure.clusters.nonDmzApiSource())

    //configure and deploy artemis
    val artemisSetup = ArtemisSetup(mngAzure, resourceGroup, nonDmzShareCreator, namespace, randSuffix)
    val artemisSecrets = artemisSetup.generateArtemisSecrets(infrastructure.clusters.nonDmzApiSource())
    val generatedArtemisStores = artemisSetup.generateArtemisStores(infrastructure.clusters.nonDmzApiSource())
    val configuredArtemisBroker = artemisSetup.configureArtemisBroker(infrastructure.clusters.nonDmzApiSource())
    val deployedArtemis = artemisSetup.deploy(infrastructure.clusters.nonDmzApiSource())


    //configure and register the node
    val nodeSetup = NodeSetup(nonDmzShareCreator, dbParams, namespace, infrastructure.clusters.nonDmzApiSource(), randSuffix)
    nodeSetup.generateNodeConfig(
        nodeX500,
        nodeEmail,
        infrastructure.p2pAddress(),
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
    val firewallSetup = FirewallSetup(namespace, nonDmzShareCreator, randSuffix)
    val firewallTunnelSecrets =
        firewallSetup.generateFirewallTunnelSecrets(infrastructure.clusters.nonDmzApiSource(), infrastructure.clusters.dmzApiSource())
    val firewallTunnelStores = firewallSetup.generateTunnelStores(infrastructure.clusters.nonDmzApiSource())

    //configure and deploy the float
    val floatSetup: FloatSetup = infrastructure.floatSetup(namespace)
    floatSetup.copyTunnelStoreComponents(firewallTunnelStores)
    floatSetup.createTunnelSecrets(firewallTunnelSecrets)
    floatSetup.generateConfig()
    floatSetup.uploadConfig()
    val floatDeployment = floatSetup.deploy(infrastructure.clusters.dmzApiSource())

    //configure and deploy the bridge
    val bridgeSetup = BridgeSetup(nonDmzShareCreator, namespace, randSuffix)
    bridgeSetup.generateBridgeStoreSecrets(infrastructure.clusters.nonDmzApiSource())
    bridgeSetup.importNodeKeyStoreIntoBridge(nodeStoreSecrets, initialRegistrationResult, infrastructure.clusters.nonDmzApiSource())
    bridgeSetup.copyTrustStoreFromNodeRegistrationResult(initialRegistrationResult)
    bridgeSetup.copyBridgeTunnelStoreComponents(firewallTunnelStores)
    bridgeSetup.copyBridgeArtemisStoreComponents(generatedArtemisStores)
    bridgeSetup.copyNetworkParametersFromNodeRegistrationResult(initialRegistrationResult)
    bridgeSetup.createTunnelSecrets(firewallTunnelSecrets)
    bridgeSetup.generateBridgeConfig(deployedArtemis.serviceName, floatDeployment.internalService.getInternalAddress())
    bridgeSetup.uploadBridgeConfig()
    bridgeSetup.createArtemisSecrets(artemisSecrets)
    val bridgeDeployment = bridgeSetup.deploy(infrastructure.clusters.nonDmzApiSource())


    //continue setting up the node
    nodeSetup.copyArtemisStores(generatedArtemisStores)
    nodeSetup.createArtemisSecrets(artemisSecrets)
    nodeSetup.createKeyVaultSecrets(vaultSecrets)
    nodeSetup.copyToDriversDir(dbParams, HsmType.AZURE)
    nodeSetup.deploy()

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
    val array = source.openInputStream().readBytes()
    this.uploadFromByteArray(array)
}


fun String.toEnvVar(): String {
    return "\${$this}"
}