package net.corda.deployment.node

import com.microsoft.azure.credentials.AzureCliCredentials
import com.microsoft.azure.management.Azure
import com.microsoft.azure.management.network.Network
import com.microsoft.azure.management.resources.fluentcore.arm.Region
import com.microsoft.azure.management.sql.SqlDatabaseStandardServiceObjective
import com.microsoft.azure.management.sql.SqlServer
import com.microsoft.rest.LogLevel
import org.apache.commons.lang3.RandomStringUtils
import kotlin.math.abs
import kotlin.random.Random

class DbBuilder {
    fun buildAzSqlInstance(
        a: Azure,
        network: Network,
        nodeSubnetName: String
    ): SqlServer {
        return a.sqlServers().define("stefano-testing-sql")
            .withRegion(Region.EUROPE_NORTH)
            .withExistingResourceGroup("stefano-playground")
            .withAdministratorLogin("cordaAdmin")
            .withAdministratorPassword(RandomStringUtils.randomAscii(16))
            .defineVirtualNetworkRule("inboundNetworkingRule")
            .withSubnet(network.id(), nodeSubnetName)
            .attach()
            .defineDatabase("cordaSQL")
            .withStandardEdition(SqlDatabaseStandardServiceObjective.S3)
            .attach()
            .create()
    }
}