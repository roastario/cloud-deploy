package net.corda.deployment.node

import com.jcraft.jsch.JSch
import com.jcraft.jsch.KeyPair
import com.microsoft.azure.AzureEnvironment
import com.microsoft.azure.credentials.AzureCliCredentials
import com.microsoft.azure.management.Azure
import com.microsoft.azure.management.containerservice.*
import com.microsoft.azure.management.containerservice.implementation.KubernetesClusterAgentPoolImpl
import com.microsoft.azure.management.containerservice.implementation.KubernetesClusterImpl
import com.microsoft.azure.management.graphrbac.BuiltInRole
import com.microsoft.azure.management.graphrbac.ServicePrincipal
import com.microsoft.azure.management.network.Network
import com.microsoft.azure.management.network.PublicIPAddress
import com.microsoft.azure.management.network.ServiceEndpointPropertiesFormat
import com.microsoft.azure.management.resources.ResourceGroup
import com.microsoft.azure.management.resources.fluentcore.arm.Region
import com.microsoft.rest.LogLevel
import io.kubernetes.client.openapi.ApiException
import org.apache.commons.lang3.RandomStringUtils
import java.io.ByteArrayOutputStream
import java.io.File
import java.lang.IllegalStateException
import java.security.Security
import java.security.cert.Certificate
import kotlin.math.abs
import kotlin.random.Random
import kotlin.random.nextUInt


data class Clusters(
    val nodeCluster: KubernetesCluster,
    val floatCluster: KubernetesCluster,
    val clusterNetwork: Network,
    val nodeSubnetName: String,
    val floatSubnetName: String
)

fun createClusterServicePrincipal(
    azure: Azure,
    randSuffix: String,
    resourceGroup: String
): PrincipalAndCredentials {
    val locatedResourceGroup = azure.resourceGroups().getByName(resourceGroup)
        ?: throw IllegalStateException("no resource group with name: $resourceGroup found")
    val servicePrincipalKeyPair = generateRSAKeyPair()
    val servicePrincipalCert = createSelfSignedCertificate(servicePrincipalKeyPair, "CN=CLI-Login")
    val password = RandomStringUtils.randomAscii(16)
    val createdSP = azure.accessManagement().servicePrincipals().define("testingspforaks$randSuffix")
        .withNewApplication("http://testingspforaks${randSuffix}")
        .withNewRoleInResourceGroup(BuiltInRole.CONTRIBUTOR, locatedResourceGroup)
        .definePasswordCredential("cliLoginPwd")
        .withPasswordValue(password)
        .attach()
        .defineCertificateCredential("cliLoginCert")
        .withAsymmetricX509Certificate()
        .withPublicKey(servicePrincipalCert.encoded)
        .attach()
        .create()

    return PrincipalAndCredentials(createdSP, password, servicePrincipalKeyPair, servicePrincipalCert)
}

data class PrincipalAndCredentials(
    val createdSP: ServicePrincipal,
    val password: String,
    val keyPair: java.security.KeyPair,
    val servicePrincipalCert: Certificate
)

fun createClusters(
    resourceGroup: String,
    p2pIpAddress: PublicIPAddress,
    rpcIPAddress: PublicIPAddress,
    randSuffix: String,
    azure: Azure,
    servicePrincipal: ServicePrincipal,
    servicePrincipalPassword: String
): Clusters {
    val locatedResourceGroup = azure.resourceGroups().getByName(resourceGroup)
    val (nodeSubnetName, floatSubnetName, createdNetwork) = createNetworkForClusters(
        azure,
        randSuffix,
        locatedResourceGroup
    )
    val floatClusterCreate = azure.kubernetesClusters()
        .define("test-cluster${randSuffix}-floats")
        .withRegion(Region.EUROPE_NORTH)
        .withExistingResourceGroup(resourceGroup)
        .withVersion("1.16.9")
        .withRootUsername("cordamanager")
        .withSshKey(String(ByteArrayOutputStream().also {
            KeyPair.genKeyPair(JSch(), KeyPair.RSA).writePublicKey(it, "")
        }.toByteArray()))
        .withServicePrincipalClientId(servicePrincipal.applicationId())
        .withServicePrincipalSecret(servicePrincipalPassword)
        .defineAgentPool("floatsonly")
        .withVirtualMachineSize(ContainerServiceVMSizeTypes.STANDARD_B2MS)
        .withMode(AgentPoolMode.SYSTEM)
        .withOSType(OSType.LINUX)
        .withAgentPoolVirtualMachineCount(1)
        .withAgentPoolType(AgentPoolType.VIRTUAL_MACHINE_SCALE_SETS)
        .withVirtualNetwork(createdNetwork.id(), floatSubnetName)
        .withAutoScale(1, 10)
        .attach()
        .withSku(ManagedClusterSKU().withName(ManagedClusterSKUName.BASIC).withTier(ManagedClusterSKUTier.PAID))
        .withDnsPrefix("test-cluster-stefano-floats-${randSuffix}")
        .defineLoadBalancerAwareNetworkProfile()
        .withLoadBalancerSku(LoadBalancerSku.STANDARD)
        .withLoadBalancerIp(p2pIpAddress)
        .withServiceCidr("10.0.0.0/16")
        .withDnsServiceIP("10.0.0.10")
        .withPodCidr("10.244.0.0/16")
        .withDockerBridgeCidr("172.17.0.1/16")
        .attach()
//            .enablePodSecurityPolicies()
        .enableRBAC()

    val nodeClusterCreate = azure.kubernetesClusters()
        .define("test-cluster${randSuffix}-nodes")
        .withRegion(Region.EUROPE_NORTH)
        .withExistingResourceGroup(resourceGroup)
        .withVersion("1.16.9")
        .withRootUsername("cordamanager")
        .withSshKey(String(ByteArrayOutputStream().also {
            KeyPair.genKeyPair(JSch(), KeyPair.RSA).writePublicKey(it, "")
        }.toByteArray()))
        .withServicePrincipalClientId(servicePrincipal.applicationId())
        .withServicePrincipalSecret(servicePrincipalPassword)
        .defineAgentPool("nofloats")
        .withVirtualMachineSize(ContainerServiceVMSizeTypes.STANDARD_B4MS)
        .withMode(AgentPoolMode.SYSTEM)
        .withOSType(OSType.LINUX)
        .withAgentPoolVirtualMachineCount(1)
        .withAgentPoolType(AgentPoolType.VIRTUAL_MACHINE_SCALE_SETS)
        .withVirtualNetwork(createdNetwork.id(), nodeSubnetName)
        .withAutoScale(1, 10)
        .attach()
        .withSku(ManagedClusterSKU().withName(ManagedClusterSKUName.BASIC).withTier(ManagedClusterSKUTier.PAID))
        .withDnsPrefix("test-cluster-stefano-floats-${randSuffix}")
        .defineLoadBalancerAwareNetworkProfile()
        .withLoadBalancerSku(LoadBalancerSku.STANDARD)
        .withLoadBalancerIp(rpcIPAddress)
        .withServiceCidr("10.0.0.0/16")
        .withDnsServiceIP("10.0.0.10")
        .withPodCidr("10.244.0.0/16")
        .withDockerBridgeCidr("172.17.0.1/16")
        .attach()
//            .enablePodSecurityPolicies()
        .enableRBAC()


    val createdFloatCluster = floatClusterCreate.create()
    val createdNodeCluster = nodeClusterCreate.create()

    return Clusters(createdNodeCluster, createdFloatCluster, createdNetwork, nodeSubnetName, floatSubnetName)


}

private fun createNetworkForClusters(
    azure: Azure,
    randSuffix: String,
    locatedResourceGroup: ResourceGroup
): Triple<String, String, Network> {
    val nodeSubnetName = "nodeClusterSubNet"
    val floatSubnetName = "floatClusterSubNet"
    val createdNetwork = azure.networks().define("stefano-vnet-$randSuffix")
        .withRegion(Region.EUROPE_NORTH)
        .withExistingResourceGroup(locatedResourceGroup)
        .withAddressSpace("192.168.0.0/16")
        .withSubnet(nodeSubnetName, "192.168.1.0/24")
        .withSubnet(floatSubnetName, "192.168.2.0/24")
        .create().also { addSubnetServiceEndPoint(it, nodeSubnetName, "Microsoft.Sql", Region.EUROPE_NORTH, azure) }
    return Triple(nodeSubnetName, floatSubnetName, createdNetwork)
}

fun addSubnetServiceEndPoint(
    it: Network,
    nodeSubnetName: String,
    serviceEndPointName: String,
    region: Region,
    azure: Azure
) {
    val virtualNetworkInner = it.inner()
    val subnet = (virtualNetworkInner.subnets() ?: mutableListOf()).single { it.name() == nodeSubnetName }
    subnet.withServiceEndpoints((subnet.serviceEndpoints() ?: mutableListOf()).let { endpointList ->
        endpointList.add(
            ServiceEndpointPropertiesFormat().withService(serviceEndPointName).withLocations(
                listOf(region.name())
            )
        )
        endpointList
    })
    azure.networks().inner().createOrUpdate(it.resourceGroupName(), it.name(), virtualNetworkInner)
}

private fun KubernetesCluster.DefinitionStages.WithCreate.enableRBAC(): KubernetesCluster.DefinitionStages.WithCreate {
    val parent = this as KubernetesClusterImpl
    parent.inner().withEnableRBAC(true)
    return parent
}

private fun KubernetesCluster.DefinitionStages.WithCreate.enablePodSecurityPolicies(): KubernetesCluster.DefinitionStages.WithCreate {
    val parent = this as KubernetesClusterImpl
    parent.inner().withEnablePodSecurityPolicy(true)
    return parent
}

private fun <ParentT> KubernetesClusterAgentPool.DefinitionStages.WithAttach<ParentT>.withAutoScale(
    minCount: Int,
    maxCount: Int
): KubernetesClusterAgentPool.DefinitionStages.WithAttach<ParentT> {
    (this as KubernetesClusterAgentPool).inner().withEnableAutoScaling(true)
    (this as KubernetesClusterAgentPool).inner().withMinCount(minCount)
    (this as KubernetesClusterAgentPool).inner().withMaxCount(maxCount)
    return this
}

interface LabelAwarePoolDefinition :
    KubernetesClusterAgentPool.DefinitionStages.Blank<KubernetesCluster.DefinitionStages.WithCreate> {
    fun withLabel(key: String, value: String): LabelAwarePoolDefinition
}

private fun KubernetesCluster.DefinitionStages.WithCreate.defineAgentPool(poolName: String): LabelAwarePoolDefinition {
    val delegate = (this as KubernetesCluster.DefinitionStages.WithAgentPool).defineAgentPool(poolName)
    return object : LabelAwarePoolDefinition,
        KubernetesClusterAgentPool.DefinitionStages.Blank<KubernetesCluster.DefinitionStages.WithCreate> by delegate {
        override fun withLabel(key: String, value: String): LabelAwarePoolDefinition {
            (delegate as KubernetesClusterAgentPoolImpl).inner().withNodeLabels(listOf(key to value).toMap())
            return this
        }

    }
}

interface NetworkProfileWithLoadBalancerDefinitionStage :
    KubernetesCluster.DefinitionStages.NetworkProfileDefinitionStages.Blank<KubernetesCluster.DefinitionStages.WithCreate> {
    fun withLoadBalancerSku(sku: LoadBalancerSku): NetworkProfileWithLoadBalancerDefinitionStage
    fun withLoadBalancerIp(ipAddress: PublicIPAddress): NetworkProfileWithLoadBalancerDefinitionStage
}

private fun KubernetesCluster.DefinitionStages.WithNetworkProfile.defineLoadBalancerAwareNetworkProfile(): NetworkProfileWithLoadBalancerDefinitionStage {
    val parent = this as KubernetesClusterImpl
    return object :
        NetworkProfileWithLoadBalancerDefinitionStage {
        private fun ensureNetworkProfile(): ContainerServiceNetworkProfile {
            if (parent.inner().networkProfile() == null) {
                parent.inner().withNetworkProfile(ContainerServiceNetworkProfile())
            }
            return parent.inner().networkProfile()
        }

        override fun withPodCidr(podCidr: String?): KubernetesCluster.DefinitionStages.NetworkProfileDefinitionStages.WithAttach<KubernetesCluster.DefinitionStages.WithCreate> {
            ensureNetworkProfile().withPodCidr(podCidr)
            return this
        }

        override fun withDnsServiceIP(dnsServiceIP: String?): KubernetesCluster.DefinitionStages.NetworkProfileDefinitionStages.WithAttach<KubernetesCluster.DefinitionStages.WithCreate> {
            ensureNetworkProfile().withDnsServiceIP(dnsServiceIP)
            return this
        }

        override fun withServiceCidr(serviceCidr: String?): KubernetesCluster.DefinitionStages.NetworkProfileDefinitionStages.WithAttach<KubernetesCluster.DefinitionStages.WithCreate> {
            ensureNetworkProfile().withServiceCidr(serviceCidr)
            return this
        }

        override fun attach(): KubernetesCluster.DefinitionStages.WithCreate {
            return parent
        }

        override fun withDockerBridgeCidr(dockerBridgeCidr: String?): KubernetesCluster.DefinitionStages.NetworkProfileDefinitionStages.WithAttach<KubernetesCluster.DefinitionStages.WithCreate> {
            ensureNetworkProfile().withDockerBridgeCidr(dockerBridgeCidr)
            return this
        }

        override fun withNetworkPlugin(networkPlugin: NetworkPlugin?): KubernetesCluster.DefinitionStages.NetworkProfileDefinitionStages.WithAttach<KubernetesCluster.DefinitionStages.WithCreate> {
            ensureNetworkProfile().withNetworkPlugin(networkPlugin)
            return this
        }

        override fun withNetworkPolicy(networkPolicy: NetworkPolicy?): KubernetesCluster.DefinitionStages.NetworkProfileDefinitionStages.WithAttach<KubernetesCluster.DefinitionStages.WithCreate> {
            ensureNetworkProfile().withNetworkPolicy(networkPolicy)
            return this
        }

        override fun withLoadBalancerSku(sku: LoadBalancerSku): NetworkProfileWithLoadBalancerDefinitionStage {
            ensureNetworkProfile().withLoadBalancerSku(sku)
            return this
        }

        override fun withLoadBalancerIp(ipAddress: PublicIPAddress): NetworkProfileWithLoadBalancerDefinitionStage {
            ensureNetworkProfile().withLoadBalancerProfile(
                ManagedClusterLoadBalancerProfile().withOutboundIPs(
                    ManagedClusterLoadBalancerProfileOutboundIPs().withPublicIPs(
                        mutableListOf(ResourceReference().withId(ipAddress.id()))
                    )
                )
            )
            return this
        }

    }
}


@ExperimentalUnsignedTypes
fun main() {
    Security.addProvider(bouncyCastleProvider)

    val azureTenant = System.getenv("TENANT")
    val tokencred =
        DeviceCodeTokenCredentials(
            AzureEnvironment.AZURE, if (azureTenant == null || azureTenant == "null") {
                "common"
            } else {
                azureTenant
            }
        )

    val azure: Azure = Azure.configure()
        .withLogLevel(LogLevel.BODY_AND_HEADERS)
        .authenticate(tokencred)
        .withSubscription("c412941a-4362-4923-8737-3d33a8d1cdc6")

//    val list = azure.virtualMachines().list()


    val randSuffix = Random.nextUInt().toString(36).toLowerCase()
    val (servicePrincipal, servicePrincipalPassword, keypair, cert) = createClusterServicePrincipal(
        azure,
        randSuffix,
        "stefano-playground"
    )

    val publicIpForAzureRpc = buildPublicIpForAzure("stefano-playground", "rpc-$randSuffix", azure)
    val publicIpForAzureP2p = buildPublicIpForAzure("stefano-playground", "p2p-$randSuffix", azure)

    val keyVault = createKeyVault("kvtesting-$randSuffix")
    configureServicePrincipalAccessToKeyVault(servicePrincipal, keyVault)
    val keyStoreFile = File("keyvault_login.p12")
    val keyAlias = "my-alias"
    val keystorePassword = "my-password"
    createPksc12Store(keypair.private, cert, keyAlias, keystorePassword, keyStoreFile.absolutePath)

    val clusters = createClusters(
        "stefano-playground",
        publicIpForAzureP2p,
        publicIpForAzureRpc,
        randSuffix,
        azure,
        servicePrincipal,
        servicePrincipalPassword
    )

    val database =
        allowAllFailures { DbBuilder().buildAzSqlInstance(azure, clusters.clusterNetwork, clusters.nodeSubnetName) }
    println("Press Enter to deploy hello world").let { readLine() }
    allowAllFailures { deployHelloWorld(clusters.floatCluster) }


    val delete = println("Delete Resources?").let { readLine() }
    if (delete != null && delete.toLowerCase().startsWith("y")) {
        println("DELETING CLUSTERS")
        allowAllFailures { azure.kubernetesClusters().deleteById(clusters.floatCluster.id()) }
        allowAllFailures { azure.kubernetesClusters().deleteById(clusters.nodeCluster.id()) }
        println("DELETING CLUSTER NODE RESOURCE GROUPS")
        allowAllFailures { azure.resourceGroups().deleteByName(clusters.floatCluster.nodeResourceGroup()) }
        allowAllFailures { azure.resourceGroups().deleteByName(clusters.nodeCluster.nodeResourceGroup()) }
        println("DELETING DATABASES")
        allowAllFailures { azure.sqlServers().deleteById(database?.id()) }
        println("DELETING NETWORK")
        allowAllFailures { azure.networks().deleteById(clusters.clusterNetwork.id()) }
        println("DELETING IP ADDRESSES")
        allowAllFailures { azure.publicIPAddresses().deleteById(publicIpForAzureP2p.id()) }
        allowAllFailures { azure.publicIPAddresses().deleteById(publicIpForAzureRpc.id()) }
    }
}

inline fun <T : Any?> allowAllFailures(block: () -> T): T? {
    try {
        return block.invoke()
    } catch (ae: ApiException) {
        System.err.println(ae.responseBody)
        ae.printStackTrace()
    } catch (e: Exception) {
        e.printStackTrace()
    }
    return null
}