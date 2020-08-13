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
    val resourceGroup: ResourceGroup
) {

    fun setupInfrastructure(): AzureInfrastructure {

        val networkCreator = NetworkCreator(azure = mngAzure, resourceGroup = resourceGroup)
        val servicePrincipalCreator = ServicePrincipalCreator(
            azure = mngAzure,
            resourceGroup = resourceGroup
        )
        val clusterCreator = KubernetesClusterCreator(azure = mngAzure, resourceGroup = resourceGroup)
        val ipCreator = PublicIpCreator(azure = mngAzure, resourceGroup = resourceGroup)

        val clusterServicePrincipal = servicePrincipalCreator.createServicePrincipalAndCredentials("cluster", true)
        val publicIpForAzureRpc = ipCreator.createPublicIp("rpc")
        val publicIpForAzureP2p = ipCreator.createPublicIp("p2p")
        val networkForClusters = networkCreator.createNetworkForClusters(publicIpForAzureRpc, publicIpForAzureP2p)

        val clusters = clusterCreator.createClusters(
            p2pIpAddress = publicIpForAzureP2p,
            rpcIPAddress = publicIpForAzureRpc,
            servicePrincipal = clusterServicePrincipal,
            network = networkForClusters
        )

        return AzureInfrastructure(clusters, mngAzure, resourceGroup)
    }


    open class AzureInfrastructure(
        internal val clusters: Clusters,
        internal val azure: Azure,
        internal val resourceGroup: ResourceGroup
    ) {

        private val shareCreators: MutableMap<String, AzureFileShareCreator> = mutableMapOf()

        fun internalShareCreator(namespace: String): AzureFileShareCreator {
            return synchronized(shareCreators) {
                shareCreators.computeIfAbsent("internal-$namespace") {
                    AzureFileShareCreator(azure, resourceGroup, namespace, clusters.nonDmzApiSource())
                }
            }
        }

        fun dmzShareCreator(namespace: String): AzureFileShareCreator {
            return synchronized(shareCreators) {
                shareCreators.computeIfAbsent("dmz-$namespace") {
                    AzureFileShareCreator(azure, resourceGroup, namespace, clusters.dmzApiSource())
                }
            }
        }

        fun floatSetup(namespace: String): FloatSetup {
            return AzureFloatSetup(namespace, dmzShareCreator(namespace), clusters.clusterNetwork, resourceGroup)
        }

        fun p2pAddress(): String {
            return clusters.clusterNetwork.p2pAddress.ipAddress()
        }

        fun artemisSetup(namespace: String): ArtemisSetup {
            return ArtemisSetup(azure, resourceGroup, internalShareCreator(namespace), namespace, clusters.nonDmzApiSource())
        }

        fun nodeSpecificInfrastructure(id: String): NodeAzureInfrastructure {
            return NodeAzureInfrastructure(clusters, azure, resourceGroup, id)
        }

        fun firewallSetup(namespace: String): FirewallSetup {
            return FirewallSetup(namespace, internalShareCreator(namespace))
        }

        fun bridgeSetup(namespace: String): BridgeSetup {
            return BridgeSetup(internalShareCreator(namespace), namespace, clusters.nonDmzApiSource())
        }
    }
}

class NodeAzureInfrastructure(
    clusters: Clusters,
    azure: Azure,
    resourceGroup: ResourceGroup,
    val nodeId: String
) : AzureInfrastructureDeployer.AzureInfrastructure(clusters, azure, resourceGroup) {
    val dbCreator = SqlServerCreator(azure = azure, resourceGroup = resourceGroup)

    fun nodeSetup(namespace: String): NodeSetup {
        val database = dbCreator.createSQLServerDBForCorda(clusters.clusterNetwork)
        return NodeSetup(
            internalShareCreator(namespace),
            database.toNodeDbParams(),
            namespace,
            clusters.nonDmzApiSource(),
            nodeId,
            HsmType.AZURE
        )
    }

    fun keyVaultSetup(namespace: String): KeyVaultSetup {
        val servicePrincipalCreator =
            ServicePrincipalCreator(azure = azure, resourceGroup = resourceGroup)
        val keyVaultCreator =
            KeyVaultCreator(azure = azure, resourceGroup = resourceGroup, clusterNetwork = clusters.clusterNetwork, nodeId = nodeId)
        val keyVaultServicePrincipal =
            servicePrincipalCreator.createServicePrincipalAndCredentials("vault", permissionsOnResourceGroup = false)
        val keyVault = keyVaultCreator.createKeyVaultAndConfigureServicePrincipalAccess(keyVaultServicePrincipal)
        val keyVaultAndCredentials = KeyVaultSetup.KeyVaultAndCredentials(keyVaultServicePrincipal, keyVault)
        return KeyVaultSetup(
            keyVaultAndCredentials,
            resourceGroup,
            namespace,
            nodeId,
            clusters.nonDmzApiSource()
        )
    }
}