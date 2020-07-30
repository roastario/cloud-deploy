package net.corda.deployment.node.database

import com.microsoft.azure.credentials.AzureCliCredentials
import com.microsoft.azure.management.Azure
import com.microsoft.azure.management.postgresql.v2017_12_01.*
import com.microsoft.azure.management.postgresql.v2017_12_01.implementation.PostgreSQLManager
import com.microsoft.azure.management.resources.ResourceGroup
import net.corda.deployment.node.networking.ClusterNetwork
import org.apache.commons.lang3.RandomStringUtils

class PostgresServerCreator {

    companion object {
        val PG_AZURE_NAMING = "Microsoft.DBforPostgreSQL"
    }

    fun create(
        subscriptionId: String,
        resourceGroup: ResourceGroup,
        mngAzure: Azure,
        network: ClusterNetwork
    ) {

        val registration = mngAzure.providers().register(PG_AZURE_NAMING)

        while (mngAzure.providers().getByName(PG_AZURE_NAMING).registrationState() == "Registering") {
            println("Waiting for PG DB provider to be registered on subscription")
            Thread.sleep(1000)
        }


        val postgreSQLManager = PostgreSQLManager.authenticate(AzureCliCredentials.create(), subscriptionId)
        val pgServer = postgreSQLManager.servers().define("corda-pg-${RandomStringUtils.randomAlphanumeric(8).toLowerCase()}")
            .withRegion(resourceGroup.region().name())
            .withExistingResourceGroup(resourceGroup.name())
            .withProperties(
                ServerPropertiesForDefaultCreate()
                    .withAdministratorLogin(RandomStringUtils.randomAlphanumeric(20).toLowerCase())
                    .withAdministratorLoginPassword(RandomStringUtils.randomAlphanumeric(20))
                    .withVersion(ServerVersion.NINE_FULL_STOP_SIX)
                    .withStorageProfile(
                        StorageProfile()
                            .withStorageAutogrow(StorageAutogrow.ENABLED)
                            .withStorageMB(250 * 1024)
                            .withBackupRetentionDays(12)
                            .withGeoRedundantBackup(GeoRedundantBackup.DISABLED)
                    )
                    .withSslEnforcement(SslEnforcementEnum.ENABLED)
            )
            .withSku(
                Sku()
                    .withName("B_Gen5_2")
                    .withTier(SkuTier.BASIC)
            ).create()

        val database = postgreSQLManager
            .databases()
            .define("new-database")
            .withExistingServer(resourceGroup.name(), pgServer.name())
            .create()

//        mngAzure.networks().getById(network.createdNetwork.id()).subnets().map { it.value. }

        postgreSQLManager.virtualNetworkRules().define("cluster-vnet-rule")
            .withExistingServer(resourceGroup.name(), pgServer.name())
            .withVirtualNetworkSubnetId("")
            .withIgnoreMissingVnetServiceEndpoint(false)

    }


}