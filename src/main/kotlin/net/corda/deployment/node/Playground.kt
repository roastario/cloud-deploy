@file:JvmName(name = "Playgound")

package net.corda.deployment.node

import com.azure.storage.file.share.ShareFileClient
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.options.transformAll
import com.github.ajalt.clikt.parameters.types.file
import com.microsoft.azure.credentials.AzureCliCredentials
import com.microsoft.azure.management.Azure
import com.microsoft.azure.management.postgresql.v2017_12_01.*
import com.microsoft.azure.management.postgresql.v2017_12_01.implementation.PostgreSQLManager
import com.microsoft.azure.management.resources.fluentcore.arm.Region
import com.microsoft.rest.LogLevel
import freighter.utils.GradleUtils
import io.kubernetes.client.openapi.apis.CoreV1Api
import io.kubernetes.client.openapi.models.V1NamespaceBuilder
import kotlinx.coroutines.runBlocking
import net.corda.deployment.node.float.FloatSetup
import net.corda.deployment.node.infrastructure.AzureInfrastructureDeployer
import net.corda.deployment.node.infrastructure.NodeAzureInfrastructure
import net.corda.deployment.node.storage.uploadFromByteArray
import org.apache.commons.lang3.RandomStringUtils
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.io.File
import java.math.BigInteger
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.Security
import java.time.Duration
import kotlin.system.exitProcess


class PrepareInfrastructure : CliktCommand(name = "prepareInfrastructure") {
    override fun run() {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

}

class InitialSetupCommand : CliktCommand(name = "firstNode") {

    val subscriptionId: String by option("-s", "--subscription", help = "Azure Subscription").required()
    val resourceGroupName: String by option("-g", "--resource-group", help = "Azure Resource Group to use").required()
    val resourceGroupRegion: String by option("-r", "--region", help = "Azure region to create resources within").required()
    val x500Name: String by option("-x", "--x500", help = "X500 Name to use for node").required()
    val email: String by option("-e", "--email", help = "email address to use when registering the node").required()
    val doormanURL: String by option("-d", "--doorman", help = "the doorman address to use when registering the node").required()
    val networkMapURL: String by option("-n", "--network-map", help = "the networkmap to use when registering the node").required()
    val trustRootURL: String? by option("-t", "--trust-root-url", help = "the url to download the network-trust-root from")
    val trustRootFile: File? by option("-f", "--trust-root-f", help = "the path to load the network-trust-root from").file()
    val trustRootPassword: String by option("-p", "--trust-root-password", help = "the password for the network-trust-root").required()

    val cordapps: List<File> by option("-c", "--cordapp", help = "Path to cordapp to load into the node").file(
        mustExist = true,
        canBeDir = false
    ).multiple()

    val gradleCordapps: List<File> by option(
        "--gradle-cordapp",
        help = "the gradle coordinates of a cordapp to load into the node <group>:<artifact>:<version>"
    ).transformAll { gradleCords ->
        gradleCords.flatMap { gradleCord ->
            val (group, artifact, version) = gradleCord.split(":")
            GradleUtils.getArtifactAndDependencies(group, artifact, version).map { it.toFile() }
        }
    }

    override fun run() {
        runBlocking {
            performDeployment(
                subscriptionId,
                resourceGroupName,
                resourceGroupRegion,
                x500Name,
                email,
                doormanURL,
                networkMapURL,
                trustRootURL,
                trustRootFile,
                trustRootPassword,
                cordapps,
                gradleCordapps
            )
        }
    }
}

suspend fun performDeployment(
    subscriptionId: String,
    resourceGroupName: String,
    region: String,
    x500Name: String,
    email: String,
    doormanURL: String,
    networkMapURL: String,
    trustRootURL: String?,
    trustRootFile: File?,
    trustRootPassword: String,
    diskCordapps: List<File>,
    gradleCordapps: List<File>
) {
    val mngAzure: Azure = Azure.configure()
        .withLogLevel(LogLevel.BODY_AND_HEADERS)
        .authenticate(AzureCliCredentials.create())
        .withSubscription(subscriptionId)

    val registration = mngAzure.providers().register("Microsoft.DBforPostgreSQL")


    while (mngAzure.providers().getByName("Microsoft.DBforPostgreSQL").registrationState() == "Registering") {
        println("Waiting for PG DB provider to be registered on subscription")
        Thread.sleep(1000)
    }

    val resourceGroup =
        mngAzure.resourceGroups().getByName(resourceGroupName) ?: mngAzure.resourceGroups().define(resourceGroupName).withRegion(
            Region.fromName(region)
        ).create()


    val azureInfrastructureDeployer = AzureInfrastructureDeployer(mngAzure, resourceGroup)
    val infrastructure = azureInfrastructureDeployer.setupInfrastructure()
    val namespace = "corda-zone"
    val trustRootConfig = TrustRootConfig(trustRootURL, trustRootPassword)

    val namespaceToCreate = V1NamespaceBuilder()
        .withKind("Namespace")
        .withNewMetadata()
        .withName(namespace)
        .endMetadata().build()

    infrastructure.clusters.dmzApiSource().run {
        CoreV1Api(this()).createNamespace(namespaceToCreate, null, null, null)
    }

    infrastructure.clusters.nonDmzApiSource().run {
        CoreV1Api(this()).createNamespace(namespaceToCreate, null, null, null)
    }

    val nodeSpecificInfra: NodeAzureInfrastructure = infrastructure.nodeSpecificInfrastructure(x500Name.shortSha())
    //configure key vault for node
    val keyVaultSetup = nodeSpecificInfra.keyVaultSetup(namespace)
    keyVaultSetup.generateKeyVaultCryptoServiceConfig()
    val vaultSecrets = keyVaultSetup.createKeyVaultSecrets()

    //configure and deploy artemis
    val artemisSetup: ArtemisSetup = infrastructure.artemisSetup(namespace)
    val artemisSecrets = artemisSetup.generateArtemisSecrets()
    val generatedArtemisStores = artemisSetup.generateArtemisStores()
    val configuredArtemisBroker = artemisSetup.configureArtemisBroker()
    val deployedArtemis = artemisSetup.deploy(useAzureDiskForData = true)

    //configure and register the node
    val nodeSetup: NodeSetup = nodeSpecificInfra.nodeSetup(namespace)
    nodeSetup.generateNodeConfig(
        x500Name,
        email,
        infrastructure.p2pAddress(),
        deployedArtemis.serviceName,
        doormanURL,
        networkMapURL,
        "u",
        "p"
    )
    nodeSetup.uploadNodeConfig()
    nodeSetup.createNodeDatabaseSecrets()
    val nodeStoreSecrets = nodeSetup.createNodeKeyStoreSecrets()
    val initialRegistrationResult = nodeSetup.performInitialRegistration(vaultSecrets, artemisSecrets, trustRootConfig)

    //setup the firewall tunnel
    val firewallSetup: FirewallSetup = infrastructure.firewallSetup(namespace)
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
    val bridgeSetup: BridgeSetup = infrastructure.bridgeSetup(namespace)
    bridgeSetup.generateBridgeStoreSecrets()
    bridgeSetup.importNodeKeyStoreIntoBridge(nodeStoreSecrets, initialRegistrationResult)
    bridgeSetup.copyTrustStoreFromNodeRegistrationResult(initialRegistrationResult)
    bridgeSetup.copyBridgeTunnelStoreComponents(firewallTunnelStores)
    bridgeSetup.copyBridgeArtemisStoreComponents(generatedArtemisStores)
    bridgeSetup.copyNetworkParametersFromNodeRegistrationResult(initialRegistrationResult)
    bridgeSetup.createTunnelSecrets(firewallTunnelSecrets)
    bridgeSetup.generateBridgeConfig(deployedArtemis.serviceName, floatDeployment.internalService.getInternalAddress())
    bridgeSetup.uploadBridgeConfig()
    bridgeSetup.createArtemisSecrets(artemisSecrets)
    val bridgeDeployment = bridgeSetup.deploy()

    //continue setting up the node
    nodeSetup.copyArtemisStores(generatedArtemisStores)
    nodeSetup.createArtemisSecrets(artemisSecrets)
    nodeSetup.createKeyVaultSecrets(vaultSecrets)
    nodeSetup.copyToDriversDir()
    nodeSetup.copyToCordappsDir(diskCordapps, gradleCordapps)
    nodeSetup.deploy()

    //ADD SECOND NODE
//    val otherX500 = "O=BigCorporation2,L=New York,C=US"
//    val nextNodeInfra = infrastructure.nodeSpecificInfrastructure(otherX500.shortSha())
//
//    val nextNodeKVSetup = nextNodeInfra.keyVaultSetup(namespace)
//    val nextNodeSetup = nextNodeInfra.nodeSetup(namespace)
//    nextNodeKVSetup.generateKeyVaultCryptoServiceConfig()
//    val nextNodeKVSecrets = nextNodeKVSetup.createKeyVaultSecrets()
//
//    nextNodeSetup.generateNodeConfig(
//        otherX500,
//        email,
//        infrastructure.p2pAddress(),
//        deployedArtemis.serviceName,
//        doormanURL,
//        networkMapURL,
//        "u",
//        "p"
//    )
//    nextNodeSetup.uploadNodeConfig()
//    nextNodeSetup.createNodeDatabaseSecrets()
//    val nextNodeStoreSecrets = nextNodeSetup.createNodeKeyStoreSecrets()
//    val nextNodeInitialRegistrationResult = nextNodeSetup.performInitialRegistration(nextNodeKVSecrets, artemisSecrets, trustRootConfig)
//    bridgeSetup.importNodeKeyStoreIntoBridge(
//        nextNodeStoreSecrets,
//        nextNodeInitialRegistrationResult
//    )
//
//    //continue setting up the node
//    nextNodeSetup.copyArtemisStores(generatedArtemisStores)
//    nextNodeSetup.createArtemisSecrets(artemisSecrets)
//    nextNodeSetup.createKeyVaultSecrets(nextNodeKVSecrets)
//    nextNodeSetup.copyToDriversDir()
//    nextNodeSetup.copyToCordappsDir(diskCordapps, gradleCordapps)
//    nextNodeSetup.deploy()
//
//    bridgeDeployment.restart(infrastructure.clusters.nonDmzApiSource())

    exitProcess(0)


}

@ExperimentalUnsignedTypes
fun main(args: Array<String>) {
    val bouncyCastleProvider = BouncyCastleProvider()
    Security.addProvider(bouncyCastleProvider)
    InitialSetupCommand().main(args)
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

fun String.shortSha(): String {
    val digest = MessageDigest.getInstance("SHA")
    val hash = digest.digest(this.toByteArray(StandardCharsets.UTF_8))
    val sha = BigInteger(hash).toString(36)
    return sha.substring(sha.length - 8)
}