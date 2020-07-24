package net.corda.deployment.node.kubernetes

import com.jcraft.jsch.JSch
import com.jcraft.jsch.KeyPair
import com.microsoft.azure.credentials.AzureCliCredentials
import com.microsoft.azure.management.Azure
import com.microsoft.azure.management.containerservice.*
import com.microsoft.azure.management.containerservice.implementation.KubernetesClusterAgentPoolImpl
import com.microsoft.azure.management.containerservice.implementation.KubernetesClusterImpl
import com.microsoft.azure.management.network.Network
import com.microsoft.azure.management.network.PublicIPAddress
import com.microsoft.azure.management.resources.ResourceGroup
import com.microsoft.rest.LogLevel
import io.kubernetes.client.openapi.ApiException
import net.corda.deployment.node.database.SqlServerCreator
import net.corda.deployment.node.hsm.KeyVaultCreator
import net.corda.deployment.node.networking.ClusterNetwork
import net.corda.deployment.node.networking.NetworkCreator
import net.corda.deployment.node.networking.PublicIpCreator
import net.corda.deployment.node.principals.PrincipalAndCredentials
import net.corda.deployment.node.principals.ServicePrincipalCreator
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.io.ByteArrayOutputStream
import java.security.Security
import kotlin.random.Random
import kotlin.random.nextUInt

class KubernetesClusterCreator(
    val azure: Azure,
    val resourceGroup: ResourceGroup,
    val runSuffix: String
) {
    fun createClusters(
        p2pIpAddress: PublicIPAddress,
        rpcIPAddress: PublicIPAddress,
        servicePrincipal: PrincipalAndCredentials,
        network: ClusterNetwork
    ): Clusters {

        val createdNetwork = network.createdNetwork
        val floatSubnetName = network.floatSubnetName
        val nodeSubnetName = network.nodeSubnetName

        val floatClusterCreate = azure.kubernetesClusters()
            .define("test-cluster${runSuffix}-floats")
            .withRegion(resourceGroup.region())
            .withExistingResourceGroup(resourceGroup)
            .withVersion("1.16.10")
            .withRootUsername("cordamanager")
            .withSshKey(String(ByteArrayOutputStream().also {
                KeyPair.genKeyPair(JSch(), KeyPair.RSA).writePublicKey(it, "")
            }.toByteArray()))
            .withServicePrincipalClientId(servicePrincipal.servicePrincipal.applicationId())
            .withServicePrincipalSecret(servicePrincipal.servicePrincipalPassword)
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
            .withDnsPrefix("test-cluster-stefano-floats-${runSuffix}")
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
            .define("test-cluster${runSuffix}-nodes")
            .withRegion(resourceGroup.region())
            .withExistingResourceGroup(resourceGroup)
            .withVersion("1.16.10")
            .withRootUsername("cordamanager")
            .withSshKey(String(ByteArrayOutputStream().also {
                KeyPair.genKeyPair(JSch(), KeyPair.RSA).writePublicKey(it, "")
            }.toByteArray()))
            .withServicePrincipalClientId(servicePrincipal.servicePrincipal.applicationId())
            .withServicePrincipalSecret(servicePrincipal.servicePrincipalPassword)
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
            .withDnsPrefix("test-cluster-stefano-nodes-${runSuffix}")
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

}


data class Clusters(
    val nodeCluster: KubernetesCluster,
    val floatCluster: KubernetesCluster,
    val clusterNetwork: Network,
    val nodeSubnetName: String,
    val floatSubnetName: String
)


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
    val bouncyCastleProvider = BouncyCastleProvider()
    Security.addProvider(bouncyCastleProvider)

    val mngAzure: Azure = Azure.configure()
        .withLogLevel(LogLevel.BODY_AND_HEADERS)
        .authenticate(AzureCliCredentials.create())
        .withSubscription("c412941a-4362-4923-8737-3d33a8d1cdc6")


    val resourceGroup = mngAzure.resourceGroups().getByName("stefano-playground")
    val randSuffix = Random.nextUInt().toString(36).toLowerCase()

    val networkCreator = NetworkCreator(azure = mngAzure, resourceGroup = resourceGroup, runSuffix = randSuffix)
    val servicePrincipalCreator = ServicePrincipalCreator(azure = mngAzure, resourceGroup = resourceGroup, runSuffix = randSuffix)
    val keyVaultCreator = KeyVaultCreator(azure = mngAzure, resourceGroup = resourceGroup, runSuffix = randSuffix)
    val clusterCreator = KubernetesClusterCreator(azure = mngAzure, resourceGroup = resourceGroup, runSuffix = randSuffix)
    val dbCreator = SqlServerCreator(azure = mngAzure, resourceGroup = resourceGroup, runSuffix = randSuffix)
    val ipCreator = PublicIpCreator(azure = mngAzure, resourceGroup = resourceGroup, runSuffix = randSuffix)

    val servicePrincipal = servicePrincipalCreator.createServicePrincipalAndCredentials()
    val keyVault = keyVaultCreator.createKeyVaultAndConfigureServicePrincipalAccess(servicePrincipal)
    val networkForClusters = networkCreator.createNetworkForClusters()
    val database = dbCreator.createSQLServerDBForCorda(networkForClusters)


    val publicIpForAzureRpc = ipCreator.createPublicIp("rpc")
    val publicIpForAzureP2p = ipCreator.createPublicIp("p2p")

    val clusters = clusterCreator.createClusters(
        p2pIpAddress = publicIpForAzureP2p,
        rpcIPAddress = publicIpForAzureRpc,
        servicePrincipal = servicePrincipal,
        network = networkForClusters
    )

    println("Press Enter to deploy hello world").let { readLine() }


    val delete = println("Delete Resources?").let { readLine() }
    if (delete != null && delete.toLowerCase().startsWith("y")) {
        println("DELETING CLUSTERS")
        allowAllFailures { mngAzure.kubernetesClusters().deleteById(clusters.floatCluster.id()) }
        allowAllFailures { mngAzure.kubernetesClusters().deleteById(clusters.nodeCluster.id()) }
        println("DELETING CLUSTER NODE RESOURCE GROUPS")
        allowAllFailures { mngAzure.resourceGroups().deleteByName(clusters.floatCluster.nodeResourceGroup()) }
        allowAllFailures { mngAzure.resourceGroups().deleteByName(clusters.nodeCluster.nodeResourceGroup()) }
        println("DELETING DATABASES")
        allowAllFailures { mngAzure.sqlServers().deleteById(database.sqlServer.id()) }
        println("DELETING NETWORK")
        allowAllFailures { mngAzure.networks().deleteById(clusters.clusterNetwork.id()) }
        println("DELETING IP ADDRESSES")
        allowAllFailures { mngAzure.publicIPAddresses().deleteById(publicIpForAzureP2p.id()) }
        allowAllFailures { mngAzure.publicIPAddresses().deleteById(publicIpForAzureRpc.id()) }
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