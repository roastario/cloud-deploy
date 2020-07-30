package net.corda.deployment.node.infrastructure

import com.microsoft.azure.management.Azure
import com.microsoft.azure.management.resources.ResourceGroup
import net.corda.deployment.node.*
import net.corda.deployment.node.database.SqlServerCreator
import net.corda.deployment.node.float.AzureFloatSetup
import net.corda.deployment.node.float.FloatSetup
import net.corda.deployment.node.hsm.KeyVaultCreator
import net.corda.deployment.node.kubernetes.Clusters
import net.corda.deployment.node.kubernetes.KubernetesClusterCreator
import net.corda.deployment.node.networking.NetworkCreator
import net.corda.deployment.node.networking.PublicIpCreator
import net.corda.deployment.node.principals.ServicePrincipalCreator
import net.corda.deployment.node.storage.AzureFileShareCreator

class AzureInfrastructureDeployer(
    val mngAzure: Azure,
    val resourceGroup: ResourceGroup,
    val runSuffix: String
) {

    fun setupInfrastructure(): AzureInfrastructure {

        val networkCreator = NetworkCreator(azure = mngAzure, resourceGroup = resourceGroup, runSuffix = runSuffix)
        val servicePrincipalCreator = ServicePrincipalCreator(
            azure = mngAzure,
            resourceGroup = resourceGroup,
            runSuffix = runSuffix
        )
        val clusterCreator = KubernetesClusterCreator(azure = mngAzure, resourceGroup = resourceGroup, runSuffix = runSuffix)
        val ipCreator = PublicIpCreator(azure = mngAzure, resourceGroup = resourceGroup, runSuffix = runSuffix)

        val clusterServicePrincipal = servicePrincipalCreator.createServicePrincipalAndCredentials("cluster", true)
        val publicIpForAzureRpc = ipCreator.createPublicIp("rpc")
        val publicIpForAzureP2p = ipCreator.createPublicIp("p2p")
        val networkForClusters = networkCreator.createNetworkForClusters(publicIpForAzureP2p, publicIpForAzureRpc)

        val clusters = clusterCreator.createClusters(
            p2pIpAddress = publicIpForAzureP2p,
            rpcIPAddress = publicIpForAzureRpc,
            servicePrincipal = clusterServicePrincipal,
            network = networkForClusters
        )

        return AzureInfrastructure(clusters, mngAzure, resourceGroup, runSuffix)
    }


    open class AzureInfrastructure(
        internal val clusters: Clusters,
        internal val azure: Azure,
        internal val resourceGroup: ResourceGroup,
        internal val runSuffix: String
    ) {

        private val shareCreators: MutableMap<String, AzureFileShareCreator> = mutableMapOf()

        fun internalShareCreator(namespace: String): AzureFileShareCreator {
            return synchronized(shareCreators) {
                shareCreators.computeIfAbsent("internal-$namespace") {
                    AzureFileShareCreator(azure, resourceGroup, runSuffix, namespace, clusters.nonDmzApiSource())
                }
            }
        }

        fun dmzShareCreator(namespace: String): AzureFileShareCreator {
            return synchronized(shareCreators) {
                shareCreators.computeIfAbsent("dmz-$namespace") {
                    AzureFileShareCreator(azure, resourceGroup, runSuffix, namespace, clusters.dmzApiSource())
                }
            }
        }


        fun floatSetup(namespace: String): FloatSetup {
            return AzureFloatSetup(namespace, dmzShareCreator(namespace), runSuffix, clusters.clusterNetwork)
        }

        fun p2pAddress(): String {
            return clusters.clusterNetwork.p2pAddress.ipAddress()
        }

        fun artemisSetup(namespace: String): ArtemisSetup {
            return ArtemisSetup(azure, resourceGroup, internalShareCreator(namespace), namespace, runSuffix, clusters.nonDmzApiSource())
        }

        fun nodeSpecificInfrastructure(id: String): NodeAzureInfrastructure {
            return NodeAzureInfrastructure(clusters, azure, resourceGroup, runSuffix, id)
        }

        fun firewallSetup(namespace: String): FirewallSetup {
            return FirewallSetup(namespace, internalShareCreator(namespace), runSuffix)
        }

        fun bridgeSetup(namespace: String): BridgeSetup {
            return BridgeSetup(internalShareCreator(namespace), namespace, runSuffix)
        }
    }
}

class NodeAzureInfrastructure(
    clusters: Clusters,
    azure: Azure,
    resourceGroup: ResourceGroup,
    runSuffix: String,
    val nodeId: String
) : AzureInfrastructureDeployer.AzureInfrastructure(clusters, azure, resourceGroup, runSuffix) {
    val dbCreator = SqlServerCreator(azure = azure, resourceGroup = resourceGroup, runSuffix = runSuffix)

    fun nodeSetup(namespace: String): NodeSetup {
        val database = dbCreator.createSQLServerDBForCorda(clusters.clusterNetwork)
        return NodeSetup(
            internalShareCreator(namespace),
            database.toNodeDbParams(),
            namespace,
            clusters.nonDmzApiSource(),
            runSuffix,
            nodeId,
            HsmType.AZURE
        )
    }

    fun keyVaultSetup(namespace: String): KeyVaultSetup {
        val servicePrincipalCreator =
            ServicePrincipalCreator(azure = azure, resourceGroup = resourceGroup, runSuffix = runSuffix)
        val keyVaultCreator = KeyVaultCreator(azure = azure, resourceGroup = resourceGroup, runSuffix = runSuffix)
        val keyVaultServicePrincipal =
            servicePrincipalCreator.createServicePrincipalAndCredentials("vault", permissionsOnResourceGroup = false)
        val keyVault = keyVaultCreator.createKeyVaultAndConfigureServicePrincipalAccess(keyVaultServicePrincipal)
        val keyVaultAndCredentials = KeyVaultSetup.KeyVaultAndCredentials(keyVaultServicePrincipal, keyVault)
        return KeyVaultSetup(
            keyVaultAndCredentials,
            azure,
            resourceGroup,
            internalShareCreator(namespace),
            namespace,
            runSuffix
        )
    }
}