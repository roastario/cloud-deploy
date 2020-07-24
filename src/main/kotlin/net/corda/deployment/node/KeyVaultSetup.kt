package net.corda.deployment.node

import com.microsoft.azure.management.Azure
import com.microsoft.azure.management.keyvault.Vault
import com.microsoft.azure.management.resources.ResourceGroup
import io.kubernetes.client.openapi.ApiClient
import net.corda.deployment.node.config.ConfigGenerators
import net.corda.deployment.node.hsm.KeyVaultCreator
import net.corda.deployment.node.kubernetes.SecretCreator
import net.corda.deployment.node.principals.PrincipalAndCredentials
import net.corda.deployment.node.principals.ServicePrincipalCreator
import net.corda.deployment.node.storage.AzureFileShareCreator
import net.corda.deployments.node.config.AzureKeyVaultConfigParams

class KeyVaultSetup(
    val mngAzure: Azure,
    val resourceGroup: ResourceGroup,
    val shareCreator: AzureFileShareCreator,
    val namespace: String,
    val runId: String
) {

    private var keyVaultSecrets: KeyVaultSecrets? = null
    private var generatedConfig: String? = null
    private var vaultAndCredentials: KeyVaultAndCredentials? = null

    fun createKeyVaultWithServicePrincipal(): KeyVaultAndCredentials {
        val servicePrincipalCreator = ServicePrincipalCreator(azure = mngAzure, resourceGroup = resourceGroup, runSuffix = runId)
        val keyVaultCreator = KeyVaultCreator(azure = mngAzure, resourceGroup = resourceGroup, runSuffix = runId)
        val servicePrincipal = servicePrincipalCreator.createServicePrincipalAndCredentials()
        val vault = keyVaultCreator.createKeyVaultAndConfigureServicePrincipalAccess(servicePrincipal)
        return KeyVaultAndCredentials(keyVaultCredentials = servicePrincipal, vault = vault).also {
            vaultAndCredentials = it
        }
    }

    fun generateKeyVaultCryptoServiceConfig(): String {
        if (vaultAndCredentials == null) {
            throw IllegalStateException("must create vault and credentials before generating config")
        }

        val keyVaultParams = AzureKeyVaultConfigParams
            .builder()
            .withServicePrincipalCredentialsFilePath(AzureKeyVaultConfigParams.CREDENTIALS_P12_PATH)
            .withServicePrincipalCredentialsFilePassword(AzureKeyVaultConfigParams.KEY_VAULT_CERTIFICATES_PASSWORD_ENV_VAR_NAME.toEnvVar())
            .withKeyVaultClientId(AzureKeyVaultConfigParams.KEY_VAULT_CLIENT_ID_ENV_VAR_NAME.toEnvVar())
            .withKeyAlias(vaultAndCredentials?.keyVaultCredentials?.p12KeyAlias)
            .withKeyVaultURL(vaultAndCredentials?.vault?.vaultUri())
            .withKeyVaultProtectionMode(AzureKeyVaultConfigParams.KEY_PROTECTION_MODE_SOFTWARE)
            .build()

        return ConfigGenerators.generateConfigFromParams(keyVaultParams).also {
            this.generatedConfig = it
        }
    }

    fun createKeyVaultSecrets(api: () -> ApiClient): KeyVaultSecrets {

        if (this.generatedConfig == null) {
            throw IllegalStateException("must generate config before creating secrets")
        }

        val keyVaultCredentialsSecretName = "az-kv-password-secrets-$runId"
        val azKeyVaultCredentialsFilePasswordKey = "az-kv-password"
        val azKeyVaultCredentialsClientIdKey = "az-kv-client-id"

        SecretCreator.createStringSecret(
            keyVaultCredentialsSecretName,
            listOf(
                azKeyVaultCredentialsFilePasswordKey to vaultAndCredentials!!.keyVaultCredentials.p12FilePassword,
                azKeyVaultCredentialsClientIdKey to vaultAndCredentials!!.keyVaultCredentials.servicePrincipal.applicationId()
            ).toMap(),
            namespace,
            api
        )

        val credentialsAndConfigSecretName = "keyvault-auth-file-secrets-$runId"
        SecretCreator.createByteArraySecret(
            credentialsAndConfigSecretName,
            listOf(
                AzureKeyVaultConfigParams.CREDENTIALS_P12_FILENAME to vaultAndCredentials!!.keyVaultCredentials.p12Bytes,
                AzureKeyVaultConfigParams.CONFIG_FILENAME to generatedConfig!!.toByteArray(Charsets.UTF_8)
            ).toMap(),
            namespace,
            api
        )

        return KeyVaultSecrets(
            keyVaultCredentialsSecretName,
            azKeyVaultCredentialsFilePasswordKey,
            azKeyVaultCredentialsClientIdKey,
            credentialsAndConfigSecretName,
            AzureKeyVaultConfigParams.CREDENTIALS_P12_FILENAME,
            AzureKeyVaultConfigParams.CONFIG_FILENAME
        ).also {
            this.keyVaultSecrets = it
        }
    }


    data class KeyVaultAndCredentials(val keyVaultCredentials: PrincipalAndCredentials, val vault: Vault)


}

data class KeyVaultSecrets(
    val credentialPasswordsSecretName: String,
    val azKeyVaultCredentialsFilePasswordKey: String,
    val azKeyVaultCredentialsClientIdKey: String,
    val credentialAndConfigFilesSecretName: String,
    val p12FileNameKey: String,
    val configFileNameKey: String
)