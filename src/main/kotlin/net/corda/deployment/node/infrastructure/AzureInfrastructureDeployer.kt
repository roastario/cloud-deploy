package net.corda.deployment.node.infrastructure

import com.microsoft.azure.management.Azure
import com.microsoft.azure.management.resources.ResourceGroup
import io.kubernetes.client.openapi.JSON
import io.kubernetes.client.openapi.apis.CoreV1Api
import io.kubernetes.client.openapi.models.V1NamespaceBuilder
import io.kubernetes.client.util.Yaml
import net.corda.deployment.node.*
import net.corda.deployment.node.database.SqlServerCreator
import net.corda.deployment.node.float.AzureFloatSetup
import net.corda.deployment.node.float.FloatDeployment
import net.corda.deployment.node.float.FloatSetup
import net.corda.deployment.node.hsm.KeyVaultCreator
import net.corda.deployment.node.kubernetes.Clusters
import net.corda.deployment.node.kubernetes.KubernetesClusterCreator
import net.corda.deployment.node.kubernetes.PersistableClusters
import net.corda.deployment.node.kubernetes.allowAllFailures
import net.corda.deployment.node.networking.NetworkCreator
import net.corda.deployment.node.networking.PublicIpCreator
import net.corda.deployment.node.principals.ServicePrincipalCreator
import net.corda.deployment.node.storage.AzureFileShareCreator
import net.corda.deployment.node.storage.AzureFilesDirectory
import net.corda.deployment.node.storage.PersistableAzureFileShareCreator
import net.corda.deployment.node.storage.PersistableShare
import java.io.File
import java.io.FileWriter
import java.io.RandomAccessFile


class AzureInfrastructureDeployer(
    val mngAzure: Azure,
    val resourceGroup: ResourceGroup
) {

    fun setupInfrastructure(fileToPersistTo: File): AzureInfrastructure {

        val persistableInfrastructure = try {
            JSON().deserialize<PersistableInfrastructure>(fileToPersistTo.readText(Charsets.UTF_8), PersistableInfrastructure::class.java)
        } catch (e: Exception) {
            PersistableInfrastructure(null, resourceGroup.name())
        }
        if (persistableInfrastructure.clusters == null) {
            mngAzure.resourceGroups().deleteByName(resourceGroup.name())
            val reCreatedResourceGroup = mngAzure.resourceGroups().define(resourceGroup.name()).withRegion(resourceGroup.region()).create()
            //we must delete and wait for the resource group to be destroyed
            val networkCreator = NetworkCreator(azure = mngAzure, resourceGroup = reCreatedResourceGroup)
            val servicePrincipalCreator = ServicePrincipalCreator(
                azure = mngAzure,
                resourceGroup = reCreatedResourceGroup
            )
            val clusterCreator = KubernetesClusterCreator(azure = mngAzure, resourceGroup = reCreatedResourceGroup)
            val ipCreator = PublicIpCreator(azure = mngAzure, resourceGroup = reCreatedResourceGroup)
            val clusterServicePrincipal = servicePrincipalCreator.createServicePrincipalAndCredentials("cluster", true)
            val publicIpForAzureRpc = ipCreator.createPublicIp("rpc")
            val publicIpForAzureP2p = ipCreator.createPublicIp("p2p")
            val networkForClusters = networkCreator.createNetworkForClusters(publicIpForAzureRpc, publicIpForAzureP2p)
            val clusters = clusterCreator.createClusters(
                servicePrincipal = clusterServicePrincipal,
                network = networkForClusters
            )
            return AzureInfrastructure(clusters, mngAzure, reCreatedResourceGroup, fileToPersistTo).also { it.persist() }
        } else {
            return AzureInfrastructure.fromPersistable(persistableInfrastructure, mngAzure, fileToPersistTo)
        }
    }


    open class AzureInfrastructure(
        internal val clusters: Clusters,
        internal val azure: Azure,
        internal val resourceGroup: ResourceGroup,
        private val fileToPersistTo: File
    ) {
        //DEPLOYMENTS
        private var artemisDeployment: ArtemisDeployment? = null

        //DEPENDENCIES
        private var floatDeployment: FloatDeployment? = null
        private var firewallSecrets: FirewallTunnelSecrets? = null
        private var artemisDirectories: ArtemisDirectories? = null
        var artemisSecrets: ArtemisSecrets? = null


        //STATE
        private var artemisConfigured: Boolean = false
        private var artemisStoresGenerated: Boolean = false
        private var firewallTunnelStoresGenerated: Boolean = false

        private val shareCreators: MutableMap<String, AzureFileShareCreator> = mutableMapOf()

        fun shareCreator(namespace: String, uniqueId: String): AzureFileShareCreator {
            return synchronized(shareCreators) {
                val key = "$namespace-$uniqueId"
                shareCreators.computeIfAbsent(key) {
                    AzureFileShareCreator(key, azure, resourceGroup, namespace, uniqueId)
                }
            }
        }

        fun floatSetup(namespace: String): FloatSetup {
            return AzureFloatSetup(
                namespace,
                shareCreator(namespace, "float-setup"),
                clusters.clusterNetwork,
                resourceGroup,
                clusters.dmzApiSource()
            )
        }

        fun p2pAddress(): String {
            return clusters.clusterNetwork.p2pAddress.ipAddress()
        }

        fun createNamespace(namespace: String) {
            val namespaceToCreate = V1NamespaceBuilder()
                .withKind("Namespace")
                .withNewMetadata()
                .withName(namespace)
                .endMetadata().build()

            clusters.dmzApiSource().run {
                allowAllFailures {
                    CoreV1Api(this()).createNamespace(namespaceToCreate, null, null, null)
                }
            }

            clusters.nonDmzApiSource().run {
                allowAllFailures {
                    CoreV1Api(this()).createNamespace(namespaceToCreate, null, null, null)
                }
            }

        }

        private fun tunnelSecrets(namespace: String): FirewallTunnelSecrets {
            if (this.firewallSecrets == null) {
                val firewallSetup = firewallSetup(namespace)
                this.firewallSecrets =
                    firewallSetup.generateFirewallTunnelSecrets(clusters.nonDmzApiSource(), clusters.dmzApiSource())
            }
            this.persist()
            return this.firewallSecrets!!
        }

        suspend fun prepareFirewallInfrastructure(namespaceName: String) {
            val tunnelSecrets = tunnelSecrets(namespaceName)
            if (!this.firewallTunnelStoresGenerated) {
                val floatTunnelStoresDir = floatTunnelDir(namespaceName)
                val bridgeTunnelStoresDir = bridgeTunnelDir(namespaceName)
                //generate firewall tunnel stores
                val firewallSetup = firewallSetup(namespaceName)
                firewallSetup.generateTunnelStores2(
                    tunnelSecrets,
                    floatTunnelStoresDir,
                    bridgeTunnelStoresDir,
                    clusters.nonDmzApiSource()
                )
                this.firewallTunnelStoresGenerated = true
                persist()
            }
        }

        fun deployFloat(namespaceName: String): FloatDeployment {
            if (this.floatDeployment == null) {
                val floatConfigDir = floatShareCreator(namespaceName).createDirectoryFor("float-config", clusters.dmzApiSource())
                val floatSetup = floatSetup(namespaceName)
                val floatConfig = floatSetup.generateConfig()
                floatSetup.uploadConfig(floatConfig, floatConfigDir)
                val floatDeployment =
                    floatSetup.deploy(clusters.dmzApiSource(), tunnelSecrets(namespaceName), floatTunnelDir(namespaceName), floatConfigDir)
                this.floatDeployment = floatDeployment
                persist()
            }
            return this.floatDeployment!!
        }

        private fun bridgeTunnelDir(namespaceName: String): AzureFilesDirectory {
            return bridgeShareCreator(namespaceName).createDirectoryFor(
                "bridge-tunnel-stores",
                clusters.nonDmzApiSource()
            )
        }

        private fun floatTunnelDir(namespaceName: String): AzureFilesDirectory {
            return floatShareCreator(namespaceName).createDirectoryFor(
                "float-tunnel-stores",
                clusters.dmzApiSource(),
                //also need to create the secrets on the internal one as we will be running within it
                clusters.nonDmzApiSource()
            )
        }

        private fun floatShareCreator(namespaceName: String) = shareCreator(namespaceName, "floatfiles")

        private fun bridgeShareCreator(namespaceName: String) = shareCreator(namespaceName, "bridgefiles")

        suspend fun setupArtemis(namespace: String): DeployedArtemis {
            if (this.artemisDeployment != null) {
                return DeployedArtemis(
                    this.artemisDeployment!!, this.artemisDirectories!!
                )
            }
            val artemisSetup = ArtemisSetup(azure, resourceGroup, namespace, clusters.nonDmzApiSource())

            if (this.artemisSecrets == null) {
                this.artemisSecrets = artemisSetup.generateArtemisSecrets()
                persist()
            }
            if (this.artemisDirectories == null) {
                this.artemisDirectories = this.createArtemisDirectories(namespace)
                persist()
            }

            if (!this.artemisStoresGenerated) {
                artemisSetup.generateArtemisStores(
                    artemisSecrets!!,
                    artemisDirectories!!.artemisStoresShare,
                    artemisDirectories!!.nodeArtemisShare,
                    artemisDirectories!!.bridgeArtemisShare
                )
                this.artemisStoresGenerated = true
                persist()
            }

            if (!this.artemisConfigured) {
                artemisSetup.configureArtemisBroker(
                    artemisSecrets!!,
                    this.artemisDirectories!!.artemisBrokerDir,
                    this.artemisDirectories!!.artemisStoresShare
                )
                this.artemisConfigured = true
                persist()
            }

            if (this.artemisDeployment == null) {
                this.artemisDeployment = artemisSetup.deploy(
                    this.artemisDirectories!!.artemisStoresShare,
                    this.artemisDirectories!!.artemisBrokerDir,
                    useAzureDiskForData = true
                )
                persist()
            }

            return DeployedArtemis(this.artemisDeployment!!, this.artemisDirectories!!)

        }

        fun nodeSpecificInfrastructure(id: String): NodeAzureInfrastructure {
            return NodeAzureInfrastructure(clusters, azure, resourceGroup, id, fileToPersistTo)
        }

        fun firewallSetup(namespace: String): FirewallSetup {
            return FirewallSetup(namespace, shareCreator(namespace, "firewall-setup"))
        }

        fun bridgeSetup(namespace: String): BridgeSetup {
            return BridgeSetup(shareCreator(namespace, "firewall-setup"), namespace, clusters.nonDmzApiSource())
        }

        fun toPersistable(): PersistableInfrastructure {
            val shareCreators = shareCreators.entries.map { (k, v) ->
                v.toPersistable()
            }

            val persistableClusters = clusters.toPersistable()

            return PersistableInfrastructure(
                persistableClusters,
                resourceGroupName = resourceGroup.name(),
                shareCreators = shareCreators,
                artemisSecrets = artemisSecrets,
                artemisDirShare = artemisDirectories?.artemisStoresShare?.toPersistable(),
                nodeArtemisDirShare = artemisDirectories?.nodeArtemisShare?.toPersistable(),
                bridgeArtemisDirShare = artemisDirectories?.bridgeArtemisShare?.toPersistable(),
                artemisBrokerDir = artemisDirectories?.artemisBrokerDir?.toPersistable(),
                artemisStoresGenerated = artemisStoresGenerated,
                artemisBrokerConfigured = artemisConfigured,
                artemisDeployment = artemisDeployment,
                firewallTunnelStoresGenerated = firewallTunnelStoresGenerated,
                floatDeployment = floatDeployment
            )

        }

        private fun registerShareCreator(internalShareCreators: Map<String, AzureFileShareCreator>) {
            this.shareCreators.putAll(internalShareCreators)
        }


        fun persist() {
            val dumps = JSON().serialize(this.toPersistable())
            FileWriter(fileToPersistTo).use {
                it.write(dumps)
            }
        }

        fun createArtemisDirectories(namespace: String): ArtemisDirectories {
            if (this.artemisDirectories == null) {
                val artemisShareCreator = this.shareCreator(namespace, "artemisfiles")
                val nodeArtemisShare = artemisShareCreator.createDirectoryFor("node-artemis-files", this.clusters.nonDmzApiSource())
                val bridgeArtemisShare = artemisShareCreator.createDirectoryFor("bridge-artemis-files", this.clusters.nonDmzApiSource())
                val artemisStoresShare = artemisShareCreator.createDirectoryFor("artemis-files", this.clusters.nonDmzApiSource())
                val artemisBrokerDir = artemisShareCreator.createDirectoryFor("artemis-broker", this.clusters.nonDmzApiSource())
                this.artemisDirectories = ArtemisDirectories(
                    artemisStoresShare = artemisStoresShare,
                    nodeArtemisShare = nodeArtemisShare,
                    bridgeArtemisShare = bridgeArtemisShare,
                    artemisBrokerDir = artemisBrokerDir
                )
                return this.artemisDirectories!!
            } else {
                return this.artemisDirectories!!
            }
        }

        companion object {
            fun fromPersistable(
                p: PersistableInfrastructure,
                mgmAzure: Azure,
                fileToPersistTo: File
            ): AzureInfrastructure {
                val clusters: Clusters = p.clusters?.let { Clusters.fromPersistable(it, mgmAzure) }
                    ?: throw IllegalStateException("cannot create infra with null k8s clusters")

                val resourceGroup = mgmAzure.resourceGroups().getByName(p.resourceGroupName)
                return AzureInfrastructure(clusters, mgmAzure, resourceGroup, fileToPersistTo).also { infra ->
                    val shareCreators = p.shareCreators.map { persistableAzureFileShareCreator ->
                        val shareCreator = AzureFileShareCreator.fromPersistable(persistableAzureFileShareCreator, mgmAzure)
                        persistableAzureFileShareCreator.id to shareCreator
                    }.toMap()
                    infra.registerShareCreator(shareCreators)
                    if (p.artemisStoresGenerated) {
                        infra.artemisStoresGenerated = true
                    }
                    if (p.firewallTunnelStoresGenerated) {
                        infra.firewallTunnelStoresGenerated = true
                    }
                    p.artemisSecrets?.let { infra.artemisSecrets = it }
                    p.artemisDirShare?.let { AzureFilesDirectory.fromPersistable(it, mgmAzure) }?.let { artemisShare ->
                        p.bridgeArtemisDirShare?.let { AzureFilesDirectory.fromPersistable(it, mgmAzure) }?.let { bridgeArtemisShare ->
                            p.nodeArtemisDirShare?.let { AzureFilesDirectory.fromPersistable(it, mgmAzure) }?.let { nodeArtemisShare ->
                                p.artemisBrokerDir?.let { AzureFilesDirectory.fromPersistable(it, mgmAzure) }?.let { brokerDir ->
                                    infra.artemisDirectories = ArtemisDirectories(
                                        nodeArtemisShare = nodeArtemisShare,
                                        bridgeArtemisShare = bridgeArtemisShare,
                                        artemisStoresShare = artemisShare,
                                        artemisBrokerDir = brokerDir
                                    )
                                }
                            }
                        }
                    }
                    p.artemisDeployment?.let { infra.artemisDeployment = it }
                    infra.floatDeployment = p.floatDeployment
                    if (p.artemisBrokerConfigured) {
                        infra.artemisConfigured = true
                    }

                }
            }
        }

    }


}

data class ArtemisDirectories(
    val nodeArtemisShare: AzureFilesDirectory,
    val bridgeArtemisShare: AzureFilesDirectory,
    val artemisStoresShare: AzureFilesDirectory,
    val artemisBrokerDir: AzureFilesDirectory
)

data class DeployedArtemis(val deployment: ArtemisDeployment, val directories: ArtemisDirectories)

data class PersistableInfrastructure(
    val clusters: PersistableClusters?,
    val resourceGroupName: String,
    val shareCreators: List<PersistableAzureFileShareCreator> = emptyList(),
    val artemisSecrets: ArtemisSecrets? = null,
    val artemisDirShare: PersistableShare? = null,
    val nodeArtemisDirShare: PersistableShare? = null,
    val bridgeArtemisDirShare: PersistableShare? = null,
    val artemisStoresGenerated: Boolean = false,
    val artemisBrokerDir: PersistableShare? = null,
    val artemisBrokerConfigured: Boolean = false,
    val artemisDeployment: ArtemisDeployment? = null,
    val firewallTunnelStoresGenerated: Boolean = false,
    val floatDeployment: FloatDeployment? = null
)

class NodeAzureInfrastructure(
    clusters: Clusters,
    azure: Azure,
    resourceGroup: ResourceGroup,
    val nodeId: String,
    fileToPersistTo: File
) : AzureInfrastructureDeployer.AzureInfrastructure(clusters, azure, resourceGroup, fileToPersistTo) {
    val dbCreator = SqlServerCreator(azure = azure, resourceGroup = resourceGroup)

    fun nodeSetup(namespace: String): NodeSetup {
        val database = dbCreator.createSQLServerDBForCorda(clusters.clusterNetwork)
        return NodeSetup(
            shareCreator(namespace, "node-setup"),
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