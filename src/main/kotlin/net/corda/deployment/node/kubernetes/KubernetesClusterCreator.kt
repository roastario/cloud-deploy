package net.corda.deployment.node.kubernetes

import com.jcraft.jsch.JSch
import com.jcraft.jsch.KeyPair
import com.microsoft.azure.management.Azure
import com.microsoft.azure.management.containerservice.*
import com.microsoft.azure.management.containerservice.implementation.KubernetesClusterAgentPoolImpl
import com.microsoft.azure.management.containerservice.implementation.KubernetesClusterImpl
import com.microsoft.azure.management.network.PublicIPAddress
import com.microsoft.azure.management.resources.ResourceGroup
import com.microsoft.rest.ServiceCallback
import io.kubernetes.client.openapi.ApiClient
import io.kubernetes.client.openapi.ApiException
import io.kubernetes.client.util.ClientBuilder
import io.kubernetes.client.util.KubeConfig
import net.corda.deployment.node.networking.ClusterNetwork
import net.corda.deployment.node.principals.PrincipalAndCredentials
import org.apache.commons.lang3.RandomStringUtils
import java.io.ByteArrayOutputStream
import java.io.InputStreamReader
import java.util.concurrent.CompletableFuture

class KubernetesClusterCreator(
    val azure: Azure,
    val resourceGroup: ResourceGroup
) {
    fun createClusters(
        p2pIpAddress: PublicIPAddress,
        rpcIPAddress: PublicIPAddress,
        servicePrincipal: PrincipalAndCredentials,
        network: ClusterNetwork,
        dnsSuffix: String = RandomStringUtils.randomAlphanumeric(12).toLowerCase()
    ): Clusters {

        val createdNetwork = network.createdNetwork
        val floatSubnetName = network.floatSubnetName
        val nodeSubnetName = network.nodeSubnetName

        val floatClusterCreate = azure.kubernetesClusters()
            .define("corda-cluster-dmz")
            .withRegion(resourceGroup.region())
            .withExistingResourceGroup(resourceGroup)
            .withVersion("1.16.10")
            .withRootUsername("cordamanager")
            .withSshKey(String(ByteArrayOutputStream().also {
                KeyPair.genKeyPair(JSch(), KeyPair.RSA).writePublicKey(it, "")
            }.toByteArray()))
            .withServicePrincipalClientId(servicePrincipal.servicePrincipal.applicationId())
            .withServicePrincipalSecret(servicePrincipal.servicePrincipalPassword)
            .defineAgentPool("dmzpool")
            .withVirtualMachineSize(ContainerServiceVMSizeTypes.STANDARD_B2MS)
            .withMode(AgentPoolMode.SYSTEM)
            .withOSType(OSType.LINUX)
            .withAgentPoolVirtualMachineCount(1)
            .withAgentPoolType(AgentPoolType.VIRTUAL_MACHINE_SCALE_SETS)
            .withVirtualNetwork(createdNetwork.id(), floatSubnetName)
            .withAutoScale(1, 10)
            .attach()
            .withSku(ManagedClusterSKU().withName(ManagedClusterSKUName.BASIC).withTier(ManagedClusterSKUTier.PAID))
            .withDnsPrefix("corda-dmz-${dnsSuffix}")
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
            .define("corda-cluster-internal")
            .withRegion(resourceGroup.region())
            .withExistingResourceGroup(resourceGroup)
            .withVersion("1.16.10")
            .withRootUsername("cordamanager")
            .withSshKey(String(ByteArrayOutputStream().also {
                KeyPair.genKeyPair(JSch(), KeyPair.RSA).writePublicKey(it, "")
            }.toByteArray()))
            .withServicePrincipalClientId(servicePrincipal.servicePrincipal.applicationId())
            .withServicePrincipalSecret(servicePrincipal.servicePrincipalPassword)
            .defineAgentPool("nondmzpool")
            .withVirtualMachineSize(ContainerServiceVMSizeTypes.STANDARD_B4MS)
            .withMode(AgentPoolMode.SYSTEM)
            .withOSType(OSType.LINUX)
            .withAgentPoolVirtualMachineCount(1)
            .withAgentPoolType(AgentPoolType.VIRTUAL_MACHINE_SCALE_SETS)
            .withVirtualNetwork(createdNetwork.id(), nodeSubnetName)
            .withAutoScale(1, 10)
            .attach()
            .withSku(ManagedClusterSKU().withName(ManagedClusterSKUName.BASIC).withTier(ManagedClusterSKUTier.PAID))
            .withDnsPrefix("corda-internal-$dnsSuffix")
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


        val floatClusterFuture = CompletableFuture<KubernetesCluster>().also { future ->
            floatClusterCreate.createAsync(object : ServiceCallback<KubernetesCluster> {
                override fun failure(t: Throwable) {
                    future.completeExceptionally(t)
                }

                override fun success(result: KubernetesCluster) {
                    future.complete(result)
                }
            })
        }

        val nodeClusterFuture = CompletableFuture<KubernetesCluster>().also { future ->
            nodeClusterCreate.createAsync(object : ServiceCallback<KubernetesCluster> {
                override fun failure(t: Throwable) {
                    future.completeExceptionally(t)
                }

                override fun success(result: KubernetesCluster) {
                    future.complete(result)
                }
            })
        }

        return floatClusterFuture.thenCombineAsync<KubernetesCluster, Clusters>(nodeClusterFuture) { floatCluster, nodeCluster ->
            Clusters(nodeCluster, floatCluster, network, nodeSubnetName, floatSubnetName)
        }.get()
    }

}

data class Clusters(
    val nodeCluster: KubernetesCluster,
    val floatCluster: KubernetesCluster,
    val clusterNetwork: ClusterNetwork,
    val nodeSubnetName: String,
    val floatSubnetName: String
) {
    fun dmzApiSource(): () -> ApiClient {
        return {
            floatCluster.adminKubeConfigContent().inputStream().use { config ->
                ClientBuilder.kubeconfig(KubeConfig.loadKubeConfig(InputStreamReader(config))).build()
            }.also { it.isDebugging = false }
        }
    }

    fun nonDmzApiSource(): () -> ApiClient {
        return {
            nodeCluster.adminKubeConfigContent().inputStream().use { config ->
                ClientBuilder.kubeconfig(KubeConfig.loadKubeConfig(InputStreamReader(config))).build()
            }.also { it.isDebugging = false }
        }
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