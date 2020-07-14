package net.corda.deployment.node.networking

import com.microsoft.azure.management.Azure
import com.microsoft.azure.management.network.PublicIPAddress
import com.microsoft.azure.management.network.PublicIPSkuType
import com.microsoft.azure.management.resources.ResourceGroup

class PublicIpCreator(val azure: Azure, private val resourceGroup: ResourceGroup, private val runSuffix: String) {

    fun createPublicIp(ipName: String): PublicIPAddress {
        val name = "${ipName}$runSuffix"
        return azure.publicIPAddresses().define(name)
            .withRegion(resourceGroup.region())
            .withExistingResourceGroup(resourceGroup)
            .withSku(PublicIPSkuType.STANDARD)
            .withStaticIP()
            .withLeafDomainLabel(name)
            .create()
    }

}