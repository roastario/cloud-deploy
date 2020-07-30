package net.corda.deployment.node.networking

import com.microsoft.azure.management.Azure
import com.microsoft.azure.management.network.PublicIPAddress
import com.microsoft.azure.management.network.PublicIPSkuType
import com.microsoft.azure.management.resources.ResourceGroup

class PublicIpCreator(
    val azure: Azure,
    private val resourceGroup: ResourceGroup
) {

    fun createPublicIp(ipName: String): PublicIPAddress {
        return azure.publicIPAddresses().define(ipName)
            .withRegion(resourceGroup.region())
            .withExistingResourceGroup(resourceGroup)
            .withSku(PublicIPSkuType.STANDARD)
            .withStaticIP()
            .create()
    }

}