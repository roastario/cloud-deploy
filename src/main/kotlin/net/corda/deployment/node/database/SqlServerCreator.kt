package net.corda.deployment.node.database

import com.microsoft.azure.management.Azure
import com.microsoft.azure.management.resources.ResourceGroup
import com.microsoft.azure.management.sql.SqlDatabaseStandardServiceObjective
import com.microsoft.azure.management.sql.SqlServer
import net.corda.deployment.node.networking.ClusterNetwork
import org.apache.commons.lang3.RandomStringUtils

class SqlServerCreator(val azure: Azure, val resourceGroup: ResourceGroup, val runSuffix: String) {

    fun createSQLServerDBForCorda(clusterNetwork: ClusterNetwork): SqlServerAndCredentials {
        val adminUsername = "cordaAdmin"
        val adminPassword = RandomStringUtils.randomGraph(16)
        val server = azure.sqlServers().define("stefano-testing-sql-${runSuffix}")
            .withRegion(resourceGroup.region())
            .withExistingResourceGroup(resourceGroup)
            .withAdministratorLogin(adminUsername)
            .withAdministratorPassword(adminPassword)
            .defineVirtualNetworkRule("inboundNetworkingRule")
            .withSubnet(clusterNetwork.createdNetwork.id(), clusterNetwork.nodeSubnetName)
            .attach()
            .defineDatabase("cordaSQL")
            .withStandardEdition(SqlDatabaseStandardServiceObjective.S3)
            .attach()
            .create()

        return SqlServerAndCredentials(sqlServer = server, adminUsername = adminUsername, adminPassword = adminPassword)
    }
}

data class SqlServerAndCredentials(val sqlServer: SqlServer, val adminUsername: String, val adminPassword: String)