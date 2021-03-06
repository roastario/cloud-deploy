package net.corda.deployment.node.database

import com.microsoft.azure.management.Azure
import com.microsoft.azure.management.resources.ResourceGroup
import com.microsoft.azure.management.sql.SqlDatabaseStandardServiceObjective
import com.microsoft.azure.management.sql.SqlServer
import net.corda.deployment.node.networking.ClusterNetwork
import org.apache.commons.lang3.RandomStringUtils

class SqlServerCreator(
    val azure: Azure,
    val resourceGroup: ResourceGroup
) {

    fun createSQLServerDBForCorda(clusterNetwork: ClusterNetwork): DatabaseAndCredentials {

        val instanceId = RandomStringUtils.randomAlphanumeric(16).toLowerCase()
        val adminUsername = "cordaAdmin-${RandomStringUtils.randomAlphanumeric(16).toLowerCase()}"
        val adminPassword = RandomStringUtils.randomGraph(16)
        val databaseName = "cordaSQL-$instanceId"
        val server = azure.sqlServers().define("corda-node-db-${instanceId}")
            .withRegion(resourceGroup.region())
            .withExistingResourceGroup(resourceGroup)
            .withAdministratorLogin(adminUsername)
            .withAdministratorPassword(adminPassword)
            .withoutAccessFromAzureServices()
            .defineVirtualNetworkRule("inboundNetworkingRule")
            .withSubnet(clusterNetwork.createdNetwork.id(), clusterNetwork.nodeSubnetName)
            .attach()
            .defineDatabase(databaseName)
            .withStandardEdition(SqlDatabaseStandardServiceObjective.S2)
            .attach()
            .create()

        return DatabaseAndCredentials(
            sqlServer = server,
            adminUsername = adminUsername,
            adminPassword = adminPassword,
            databaseName = databaseName
        )
    }
}

data class DatabaseAndCredentials(
    val sqlServer: SqlServer,
    val databaseName: String,
    val adminUsername: String,
    val adminPassword: String
) {
    fun toNodeDbParams(): DatabaseConfigParams {
        val jdbcString = "jdbc:sqlserver://" +
                "${sqlServer.fullyQualifiedDomainName()}:1433;" +
                "database=${databaseName};" +
                "encrypt=true;" +
                "trustServerCertificate=false;" +
                "hostNameInCertificate=*.database.windows.net;" +
                "loginTimeout=30"
        return DatabaseConfigParams(jdbcString, adminUsername, adminPassword, DatabaseType.MS_SQL)
    }
}