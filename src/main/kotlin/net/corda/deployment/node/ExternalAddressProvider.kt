package net.corda.deployment.node

import com.microsoft.azure.management.Azure
import com.microsoft.azure.management.network.PublicIPAddress
import com.microsoft.azure.management.network.PublicIPSkuType
import com.microsoft.azure.management.resources.fluentcore.arm.Region
import io.kubernetes.client.openapi.models.V1Service

fun buildPublicIpForAzure(
    resourceGroup: String,
    runSuffix: String = "",
    azure: Azure
): PublicIPAddress {
    val name = "stefanotestingforaks$runSuffix"
    val publicIPAddress = azure.publicIPAddresses().define(name).withRegion(Region.EUROPE_NORTH)
        .withExistingResourceGroup(resourceGroup).withSku(
            PublicIPSkuType.STANDARD
        ).withStaticIP().withLeafDomainLabel(name).create()
    return publicIPAddress
}