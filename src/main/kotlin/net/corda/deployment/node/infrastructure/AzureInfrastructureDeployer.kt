package net.corda.deployment.node.infrastructure

import com.microsoft.azure.management.Azure
import com.microsoft.azure.management.resources.ResourceGroup
import io.kubernetes.client.openapi.JSON
import io.kubernetes.client.openapi.apis.CoreV1Api
import io.kubernetes.client.openapi.models.V1NamespaceBuilder
import net.corda.deployment.node.*
import net.corda.deployment.node.database.SqlServerCreator
import net.corda.deployment.node.float.AzureFloatSetup
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


class AzureInfrastructureDeployer(
    val mngAzure: Azure,
    val resourceGroup: ResourceGroup
) {

    fun setupInfrastructure(fileToPersistTo: File): AzureInfrastructure {

        val persistableInfrastructure =
            JSON().deserialize<PersistableInfrastructure>(fileToPersistTo.readText(Charsets.UTF_8), PersistableInfrastructure::class.java)
        if (persistableInfrastructure.clusters == null) {
            //we must delete and wait for the resource group to be destroyed
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
                servicePrincipal = clusterServicePrincipal,
                network = networkForClusters
            )
            return AzureInfrastructure(clusters, mngAzure, resourceGroup, fileToPersistTo)
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

        private var firewallSecrets: FirewallTunnelSecrets? = null
        private var artemisDirectories: ArtemisDirectories? = null
        private var artemisDeployment: ArtemisDeployment? = null
        private var artemisConfigured: Boolean = false
        private var artemisStoresGenerated: Boolean = false
        private var artemisSecrets: ArtemisSecrets? = null

        private val internalShareCreators: MutableMap<String, AzureFileShareCreator> = mutableMapOf()
        private val dmzShareCreators: MutableMap<String, AzureFileShareCreator> = mutableMapOf()

        fun internalShareCreator(namespace: String, uniqueId: String = namespace): AzureFileShareCreator {
            return synchronized(internalShareCreators) {
                val key = "internal-$namespace-$uniqueId"
                internalShareCreators.computeIfAbsent(key) {
                    AzureFileShareCreator(key, azure, resourceGroup, namespace, uniqueId)
                }
            }
        }

        fun dmzShareCreator(namespace: String, uniqueId: String = namespace): AzureFileShareCreator {
            return synchronized(dmzShareCreators) {
                val key = "dmz-$namespace-$uniqueId"
                dmzShareCreators.computeIfAbsent(key) {
                    AzureFileShareCreator(key, azure, resourceGroup, namespace, uniqueId)
                }
            }
        }

        fun floatSetup(namespace: String): FloatSetup {
            return AzureFloatSetup(namespace, dmzShareCreator(namespace), clusters.clusterNetwork, resourceGroup, clusters.dmzApiSource())
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

        suspend fun tunnelSecrets(namespace: String) {

            if (this.firewallSecrets == null) {
                val firewallSetup = firewallSetup(namespace)
                this.firewallSecrets =
                    firewallSetup.generateFirewallTunnelSecrets(clusters.nonDmzApiSource(), clusters.dmzApiSource())
            }

        }

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
            return FirewallSetup(namespace, internalShareCreator(namespace))
        }

        fun bridgeSetup(namespace: String): BridgeSetup {
            return BridgeSetup(internalShareCreator(namespace), namespace, clusters.nonDmzApiSource())
        }

        fun toPersistable(): PersistableInfrastructure {
            val internalShares = internalShareCreators.entries.map { (k, v) ->
                v.toPersistable()
            }

            val dmzShares = dmzShareCreators.entries.map { (k, v) ->
                v.toPersistable()
            }

            val persistableClusters = clusters.toPersistable()

            return PersistableInfrastructure(
                persistableClusters,
                resourceGroupName = resourceGroup.name(),
                internalShareCreators = internalShares,
                dmzShareCreators = dmzShares,
                artemisSecrets = artemisSecrets,
                artemisDirShare = artemisDirectories?.artemisStoresShare?.toPersistable(),
                nodeArtemisDirShare = artemisDirectories?.nodeArtemisShare?.toPersistable(),
                bridgeArtemisDirShare = artemisDirectories?.bridgeArtemisShare?.toPersistable(),
                artemisBrokerDir = artemisDirectories?.artemisBrokerDir?.toPersistable(),
                artemisStoresGenerated = artemisStoresGenerated,
                artemisBrokerConfigured = artemisConfigured,
                artemisDeployment = artemisDeployment
            )

        }


        private fun registerInternalCreators(internalShareCreators: Map<String, AzureFileShareCreator>) {
            this.internalShareCreators.putAll(internalShareCreators)
        }

        private fun registerDmzCreators(dmzShareCreators: Map<String, AzureFileShareCreator>) {
            this.dmzShareCreators.putAll(dmzShareCreators)
        }

        fun registerArtemisSecrets(artemisSecrets: ArtemisSecrets) {
            this.artemisSecrets = artemisSecrets
        }

        private fun persist(
        ) {
            val dumps = JSON().serialize(this.toPersistable())
            FileWriter(fileToPersistTo).use {
                it.write(dumps)
            }
//            val objectMapper = ObjectMapper()
//            objectMapper.registerModule(KotlinModule())
//
//            objectMapper.writeValue(
//                fileToPersistTo,
//                this.toPersistable()
//            )
        }


        fun markArtemisStoresGenerated() {
            this.artemisStoresGenerated = true
        }

        fun createArtemisDirectories(namespace: String): ArtemisDirectories {
            if (this.artemisDirectories == null) {
                val artemisShareCreator = this.internalShareCreator(namespace, "artemisfiles")
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

        fun markArtemisBrokerConfigured() {
            this.artemisConfigured = true
        }

        fun registerArtemisDeployment(deployedArtemis: ArtemisDeployment) {
            this.artemisDeployment = deployedArtemis
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
                    val internalCreatorsToRegister = p.internalShareCreators.map { persistableAzureFileShareCreator ->
                        val shareCreator = AzureFileShareCreator.fromPersistable(persistableAzureFileShareCreator, mgmAzure)
                        persistableAzureFileShareCreator.id to shareCreator
                    }.toMap()
                    val dmzCreatorsToRegister = p.dmzShareCreators.map { persistableAzureFileShareCreator ->
                        val shareCreator = AzureFileShareCreator.fromPersistable(persistableAzureFileShareCreator, mgmAzure)
                        persistableAzureFileShareCreator.id to shareCreator
                    }.toMap()
                    if (p.artemisStoresGenerated) {
                        infra.markArtemisStoresGenerated()
                    }
                    infra.registerDmzCreators(dmzCreatorsToRegister)
                    infra.registerInternalCreators(internalCreatorsToRegister)
                    p.artemisSecrets?.let { infra.registerArtemisSecrets(it) }
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
                    p.artemisDeployment?.let { infra.registerArtemisDeployment(it) }
                    if (p.artemisBrokerConfigured) {
                        infra.markArtemisBrokerConfigured()
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
    val internalShareCreators: List<PersistableAzureFileShareCreator> = emptyList(),
    val dmzShareCreators: List<PersistableAzureFileShareCreator> = emptyList(),
    val artemisSecrets: ArtemisSecrets? = null,
    val artemisDirShare: PersistableShare? = null,
    val nodeArtemisDirShare: PersistableShare? = null,
    val bridgeArtemisDirShare: PersistableShare? = null,
    val artemisStoresGenerated: Boolean,
    val artemisBrokerDir: PersistableShare?,
    val artemisBrokerConfigured: Boolean,
    val artemisDeployment: ArtemisDeployment?
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