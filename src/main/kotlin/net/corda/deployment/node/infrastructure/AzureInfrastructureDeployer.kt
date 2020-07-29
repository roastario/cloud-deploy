package net.corda.deployment.node.infrastructure

import com.microsoft.azure.management.Azure
import com.microsoft.azure.management.resources.ResourceGroup
import net.corda.deployment.node.ArtemisSetup
import net.corda.deployment.node.HsmType
import net.corda.deployment.node.KeyVaultSetup
import net.corda.deployment.node.NodeSetup
import net.corda.deployment.node.database.SqlServerAndCredentials
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
        val servicePrincipalCreator = ServicePrincipalCreator(azure = mngAzure, resourceGroup = resourceGroup, runSuffix = runSuffix)
        val keyVaultCreator = KeyVaultCreator(azure = mngAzure, resourceGroup = resourceGroup, runSuffix = runSuffix)
        val clusterCreator = KubernetesClusterCreator(azure = mngAzure, resourceGroup = resourceGroup, runSuffix = runSuffix)
        val dbCreator = SqlServerCreator(azure = mngAzure, resourceGroup = resourceGroup, runSuffix = runSuffix)
        val ipCreator = PublicIpCreator(azure = mngAzure, resourceGroup = resourceGroup, runSuffix = runSuffix)

        val keyVaultServicePrincipal = servicePrincipalCreator.createServicePrincipalAndCredentials("vault")
        val clusterServicePrincipal = servicePrincipalCreator.createServicePrincipalAndCredentials("cluster")
        val keyVault = keyVaultCreator.createKeyVaultAndConfigureServicePrincipalAccess(keyVaultServicePrincipal)
        val publicIpForAzureRpc = ipCreator.createPublicIp("rpc")
        val publicIpForAzureP2p = ipCreator.createPublicIp("p2p")
        val networkForClusters = networkCreator.createNetworkForClusters(publicIpForAzureP2p, publicIpForAzureRpc)
        val database = dbCreator.createSQLServerDBForCorda(networkForClusters)


        val clusters = clusterCreator.createClusters(
            p2pIpAddress = publicIpForAzureP2p,
            rpcIPAddress = publicIpForAzureRpc,
            servicePrincipal = clusterServicePrincipal,
            network = networkForClusters
        )

        val keyVaultAndCredentials = KeyVaultSetup.KeyVaultAndCredentials(keyVaultServicePrincipal, keyVault)

        return AzureInfrastructure(clusters, keyVaultAndCredentials, database, mngAzure, resourceGroup, runSuffix)
    }

    class AzureInfrastructure(
        val clusters: Clusters,
        val keyVaultAndCredentials: KeyVaultSetup.KeyVaultAndCredentials,
        val database: SqlServerAndCredentials,
        private val azure: Azure,
        private val resourceGroup: ResourceGroup,
        private val runSuffix: String
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

        fun keyVaultSetup(namespace: String): KeyVaultSetup {
            return KeyVaultSetup(
                keyVaultAndCredentials,
                azure,
                resourceGroup,
                internalShareCreator(namespace),
                namespace,
                runSuffix
            )
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

        fun nodeSetup(namespace: String): NodeSetup {
            return NodeSetup(
                internalShareCreator(namespace),
                database.toNodeDbParams(),
                namespace,
                clusters.nonDmzApiSource(),
                runSuffix,
                HsmType.AZURE
            )
        }
    }

}