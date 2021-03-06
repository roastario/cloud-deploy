package net.corda.deployment.node.hsm

import com.microsoft.azure.management.Azure
import com.microsoft.azure.management.graphrbac.BuiltInRole
import com.microsoft.azure.management.graphrbac.RoleAssignment
import com.microsoft.azure.management.graphrbac.ServicePrincipal
import com.microsoft.azure.management.keyvault.*
import com.microsoft.azure.management.resources.ResourceGroup
import net.corda.deployment.node.networking.ClusterNetwork
import net.corda.deployment.node.principals.PrincipalAndCredentials
import java.util.*

class KeyVaultCreator(
    private val azure: Azure,
    private val resourceGroup: ResourceGroup,
    private val nodeId: String,
    private val clusterNetwork: ClusterNetwork
) {
    fun createKeyVaultAndConfigureServicePrincipalAccess(
        servicePrincipal: PrincipalAndCredentials
    ): Vault {

        val discoveredVnet = azure.networks().getById(clusterNetwork.createdNetwork.id())
        val nodeSubnet = discoveredVnet.subnets()[clusterNetwork.nodeSubnetName]?.inner()
            ?: throw IllegalStateException("node internal subnet not available")
        val kv = azure.vaults()
            .define("cordaVault-${nodeId}")
            .withRegion(resourceGroup.region()).withExistingResourceGroup(resourceGroup)
            .withEmptyAccessPolicy()
            .withAccessFromSelectedNetworks()
            .withVirtualNetworkRules(listOf(VirtualNetworkRule().withId(nodeSubnet.id())))
            .create()
        return kv.also { configureServicePrincipalAccessToKeyVault(servicePrincipal.servicePrincipal, it) }
    }

    private fun configureServicePrincipalAccessToKeyVault(
        sp: ServicePrincipal,
        kv: Vault
    ): RoleAssignment {
        val createdRole = azure.accessManagement().roleAssignments().define(UUID.randomUUID().toString())
            .forServicePrincipal(sp).withBuiltInRole(BuiltInRole.CONTRIBUTOR)
            .withResourceScope(kv).create()

        val certPermissions = listOf(
            CertificatePermissions.GET,
            CertificatePermissions.LIST,
            CertificatePermissions.UPDATE,
            CertificatePermissions.CREATE,
            CertificatePermissions.IMPORT,
            CertificatePermissions.RECOVER,
            CertificatePermissions.BACKUP,
            CertificatePermissions.RESTORE,
            CertificatePermissions.MANAGECONTACTS,
            CertificatePermissions.MANAGEISSUERS,
            CertificatePermissions.GETISSUERS,
            CertificatePermissions.LISTISSUERS,
            CertificatePermissions.SETISSUERS
        )

        val keyPermissions = listOf(
            KeyPermissions.GET,
            KeyPermissions.LIST,
            KeyPermissions.UPDATE,
            KeyPermissions.CREATE,
            KeyPermissions.IMPORT,
            KeyPermissions.RECOVER,
            KeyPermissions.BACKUP,
            KeyPermissions.RESTORE,
            KeyPermissions.DECRYPT,
            KeyPermissions.ENCRYPT,
            KeyPermissions.UNWRAP_KEY,
            KeyPermissions.WRAP_KEY,
            KeyPermissions.VERIFY,
            KeyPermissions.SIGN
        )

        val secretPermissions = listOf(
            SecretPermissions.GET,
            SecretPermissions.LIST,
            SecretPermissions.SET,
            SecretPermissions.RECOVER,
            SecretPermissions.BACKUP,
            SecretPermissions.RESTORE
        )

        val accessPolicyEntry = AccessPolicyEntry()
            .withPermissions(
                Permissions()
                    .withCertificates(certPermissions)
                    .withKeys(keyPermissions)
                    .withSecrets(secretPermissions)
            )
            .withObjectId(sp.id())
            .withTenantId(UUID.fromString(kv.tenantId()))


        kv.manager().inner().vaults().updateAccessPolicy(
            kv.resourceGroupName(), kv.name(), AccessPolicyUpdateKind.ADD, VaultAccessPolicyProperties().withAccessPolicies(
                listOf(accessPolicyEntry)
            )
        )
        return createdRole
    }
}