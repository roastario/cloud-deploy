package net.corda.deployment.node.networking

import com.microsoft.azure.management.Azure
import com.microsoft.azure.management.network.Network
import com.microsoft.azure.management.network.PublicIPAddress
import com.microsoft.azure.management.network.ServiceEndpointPropertiesFormat
import com.microsoft.azure.management.resources.ResourceGroup
import com.microsoft.azure.management.resources.fluentcore.arm.Region
import net.corda.deployment.node.networking.ClusterNetwork.Companion.ADDRESS_SPACE
import net.corda.deployment.node.networking.ClusterNetwork.Companion.DMZ_CIDR
import net.corda.deployment.node.networking.ClusterNetwork.Companion.INTERNAL_CIDR

class PersistableNetwork(
    val nodeSubnetName: String,
    val floatSubnetName: String,
    val networkId: String,
    val p2pAddressId: String,
    val controlAddressId: String
) {

}

data class ClusterNetwork(
    val nodeSubnetName: String,
    val floatSubnetName: String,
    val createdNetwork: Network,
    val p2pAddress: PublicIPAddress,
    val controlAddress: PublicIPAddress
) {

    companion object {
        const val ADDRESS_SPACE: String = "192.168.0.0/16"
        const val DMZ_CIDR: String = "192.168.2.0/24"
        const val DMZ_IP_PREFIX = "192.168.2"
        const val INTERNAL_CIDR: String = "192.168.1.0/24"
        const val INTERNAL_IP_PREFIX = "192.168.1"

        fun fromPersistable(p: PersistableNetwork, mgmAzure: Azure): ClusterNetwork {
            val nodeSubnetName: String = p.nodeSubnetName
            val floatSubnetName: String = p.floatSubnetName
            val network = mgmAzure.networks().getById(p.networkId)
            val p2pAddress = mgmAzure.publicIPAddresses().getById(p.p2pAddressId)
            val controlAddress = mgmAzure.publicIPAddresses().getById(p.controlAddressId)
            return ClusterNetwork(nodeSubnetName, floatSubnetName, network, p2pAddress, controlAddress)
        }
    }

    fun toPersistable(): PersistableNetwork {
        return PersistableNetwork(nodeSubnetName, floatSubnetName, createdNetwork.id(), p2pAddress.id(), controlAddress.id())
    }

    @ExperimentalUnsignedTypes
    fun getNextAvailableDMZInternalIP(): String {
        var lastByteOfAddress: UByte = 0u

        do {
            lastByteOfAddress++
            println("checking if: $DMZ_IP_PREFIX.$lastByteOfAddress is available as internalIp")
        } while (!createdNetwork.isPrivateIPAddressAvailable("$DMZ_IP_PREFIX.$lastByteOfAddress") && lastByteOfAddress < 254u)


        if (!createdNetwork.isPrivateIPAddressAvailable("$DMZ_IP_PREFIX.$lastByteOfAddress") || lastByteOfAddress >= 254u) {
            throw IllegalStateException("Could not find an available IP within DMZ subnet")
        } else {
            return "$DMZ_IP_PREFIX.$lastByteOfAddress"
        }
    }
}

class NetworkCreator(
    val azure: Azure,
    val resourceGroup: ResourceGroup
) {
    fun createNetworkForClusters(controlIp: PublicIPAddress, p2pIp: PublicIPAddress): ClusterNetwork {
        val nodeSubnetName = "internalClusterSubNet"
        val floatSubnetName = "dmzClusterSubNet"
        val createdNetwork = azure.networks().define("corda-vnet")
            .withRegion(resourceGroup.region())
            .withExistingResourceGroup(resourceGroup)
            .withAddressSpace(ADDRESS_SPACE)
            .withSubnet(nodeSubnetName, INTERNAL_CIDR)
            .withSubnet(floatSubnetName, DMZ_CIDR)
            .create().also {
                addSubnetServiceEndPoint(it, nodeSubnetName, "Microsoft.Sql", resourceGroup.region(), azure)
                addSubnetServiceEndPoint(it, nodeSubnetName, "Microsoft.KeyVault", resourceGroup.region(), azure)
            }
        return ClusterNetwork(nodeSubnetName, floatSubnetName, createdNetwork, p2pIp, controlIp)
    }

    private fun addSubnetServiceEndPoint(
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
}