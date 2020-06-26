package net.corda.deployment.node

import com.jcraft.jsch.JSch
import com.jcraft.jsch.KeyPair
import com.microsoft.azure.AzureEnvironment
import com.microsoft.azure.credentials.AzureCliCredentials
import com.microsoft.azure.credentials.MSICredentials
import com.microsoft.azure.management.Azure
import com.microsoft.azure.management.containerservice.*
import com.microsoft.azure.management.containerservice.implementation.KubernetesClusterAgentPoolImpl
import com.microsoft.azure.management.containerservice.implementation.KubernetesClusterImpl
import com.microsoft.azure.management.graphrbac.BuiltInRole
import com.microsoft.azure.management.graphrbac.ServicePrincipal
import com.microsoft.azure.management.network.Network
import com.microsoft.azure.management.network.PublicIPAddress
import com.microsoft.azure.management.resources.ResourceGroup
import com.microsoft.azure.management.resources.fluentcore.arm.Region
import com.microsoft.rest.LogLevel
import java.io.ByteArrayOutputStream
import java.security.Security
import java.security.cert.Certificate
import kotlin.math.abs
import kotlin.random.Random
import kotlin.random.nextUInt


data class Clusters(
    val nodeCluster: KubernetesCluster,
    val floatCluster: KubernetesCluster,
    val clusterNetwork: Network
)

fun createClusterServicePrincipal(
    azure: Azure,
    randSuffix: String,
    resourceGroup: ResourceGroup
): PrincipalAndCredentials {
    val servicePrincipalKeyPair = generateRSAKeyPair()

    val servicePrincipalCert = createSelfSignedCertificate(servicePrincipalKeyPair, "CN=CLI-Login")
    val password = (0..10).joinToString("") { abs(Random.nextInt()).toString(36).toLowerCase() }

    val createdSP = azure.accessManagement().servicePrincipals().define("testingspforaks$randSuffix")
        .withNewApplication("http://testingspforaks${randSuffix}")
        .withNewRoleInResourceGroup(BuiltInRole.CONTRIBUTOR, resourceGroup)
        .withNewRoleInSubscription(BuiltInRole.READER, azure.currentSubscription.subscriptionId())
        .definePasswordCredential("cliLoginPwd")
        .withPasswordValue(password)
        .attach()
        .defineCertificateCredential("cliLoginCert")
        .withAsymmetricX509Certificate()
        .withPublicKey(servicePrincipalCert.encoded)
        .attach()
        .create()

    return PrincipalAndCredentials(createdSP, password, servicePrincipalCert)
}

data class PrincipalAndCredentials(
    val createdSP: ServicePrincipal,
    val password: String,
    val servicePrincipalCert: Certificate
)

fun createClusters(
    resourceGroup: String,
    p2pIpAddress: PublicIPAddress,
    rpcIPAddress: PublicIPAddress,
    randSuffix: String,
    azure: Azure
): Clusters {

    val locatedResourceGroup = azure.resourceGroups().getByName(resourceGroup)

    val (createdSP, password, cert) = createClusterServicePrincipal(azure, randSuffix, locatedResourceGroup)


    try {

        val nodeSubnetName = "nodeCluster"
        val floatSubnetName = "floatCluster"
        val createdNetwork = azure.networks().define("stefano-vnet-$randSuffix")
            .withRegion(Region.EUROPE_NORTH)
            .withExistingResourceGroup(locatedResourceGroup)
            .withAddressSpace("192.168.0.0/16")
            .withSubnet(floatSubnetName, "192.168.1.0/24")
            .withSubnet(nodeSubnetName, "192.168.2.0/24")
            .create()

        val floatClusterCreate = azure.kubernetesClusters()
            .define("test-cluster${randSuffix}-floats")
            .withRegion(Region.EUROPE_NORTH)
            .withExistingResourceGroup(resourceGroup)
            .withVersion("1.16.9")
            .withRootUsername("cordamanager")
            .withSshKey(String(ByteArrayOutputStream().also {
                KeyPair.genKeyPair(JSch(), KeyPair.RSA).writePublicKey(it, "")
            }.toByteArray()))
            .withServicePrincipalClientId(createdSP.applicationId())
            .withServicePrincipalSecret(password)
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
            .withServicePrincipalClientId(createdSP.applicationId())
            .withServicePrincipalSecret(password)
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

        return Clusters(createdNodeCluster, createdFloatCluster, createdNetwork)

    } finally {
        azure.accessManagement().servicePrincipals().deleteById(createdSP.id())
    }


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


    val a: Azure = Azure.configure()
        .withLogLevel(LogLevel.BODY_AND_HEADERS)
        .authenticate(AzureCliCredentials.create())
        .withSubscription("c412941a-4362-4923-8737-3d33a8d1cdc6")

    val randSuffix = Random.nextUInt().toString(36).toLowerCase()
    val publicIpForAzureRpc = buildPublicIpForAzure("stefano-playground", "rpc-$randSuffix", a)
    val publicIpForAzureP2p = buildPublicIpForAzure("stefano-playground", "p2p-$randSuffix", a)
    val clusters = createClusters("stefano-playground", publicIpForAzureP2p, publicIpForAzureRpc, randSuffix, a)
    println(clusters)
    println()

    val delete = println("Delete Resources?").let { readLine() }
    if (delete != null && delete.toLowerCase().startsWith("y")) {
        println("DELETING CLUSTERS")
        a.kubernetesClusters().deleteById(clusters.floatCluster.id())
        a.kubernetesClusters().deleteById(clusters.nodeCluster.id())
        println("DELETING CLUSTER NODE RESOURCE GROUPS")
        a.resourceGroups().deleteByName(clusters.floatCluster.nodeResourceGroup())
        a.resourceGroups().deleteByName(clusters.nodeCluster.nodeResourceGroup())
        println("DELETING NETWORK")
        a.networks().deleteById(clusters.clusterNetwork.id())
        println("DELETING IP ADDRESSES")
        a.publicIPAddresses().deleteById(publicIpForAzureP2p.id())
        a.publicIPAddresses().deleteById(publicIpForAzureRpc.id())
    }
}