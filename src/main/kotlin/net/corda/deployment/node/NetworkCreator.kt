package net.corda.deployment.node

import com.microsoft.azure.management.Azure
import com.microsoft.azure.management.network.Network
import com.microsoft.azure.management.network.ServiceEndpointPropertiesFormat
import com.microsoft.azure.management.resources.ResourceGroup
import com.microsoft.azure.management.resources.fluentcore.arm.Region

data class ClusterNetwork(val nodeSubnetName: String, val floatSubnetName: String, val createdNetwork: Network)

class NetworkCreator(
    val azure: Azure,
    val resourceGroup: ResourceGroup,
    val runSuffix: String
) {
    fun createNetworkForClusters(
        azure: Azure,
        randSuffix: String,
        locatedResourceGroup: ResourceGroup
    ): ClusterNetwork {
        val nodeSubnetName = "nodeClusterSubNet"
        val floatSubnetName = "floatClusterSubNet"
        val createdNetwork = azure.networks().define("stefano-vnet-$randSuffix")
            .withRegion(Region.EUROPE_NORTH)
            .withExistingResourceGroup(locatedResourceGroup)
            .withAddressSpace("192.168.0.0/16")
            .withSubnet(nodeSubnetName, "192.168.1.0/24")
            .withSubnet(floatSubnetName, "192.168.2.0/24")
            .create().also { addSubnetServiceEndPoint(it, nodeSubnetName, "Microsoft.Sql", Region.EUROPE_NORTH, azure) }
        return ClusterNetwork(nodeSubnetName, floatSubnetName, createdNetwork)
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