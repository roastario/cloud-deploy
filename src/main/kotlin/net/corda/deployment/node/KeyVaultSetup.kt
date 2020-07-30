package net.corda.deployment.node

import com.microsoft.azure.management.keyvault.Vault
import com.microsoft.azure.management.resources.ResourceGroup
import io.kubernetes.client.openapi.ApiClient
import net.corda.deployment.node.config.ConfigGenerators
import net.corda.deployment.node.kubernetes.SecretCreator
import net.corda.deployment.node.principals.PrincipalAndCredentials
import net.corda.deployments.node.config.AzureKeyVaultConfigParams

class KeyVaultSetup(
    val keyVaultAndCredentials: KeyVaultAndCredentials,
    val resourceGroup: ResourceGroup,
    val namespace: String,
    val nodeId: String,
    val api: () -> ApiClient
) {

    private var keyVaultSecrets: KeyVaultSecrets? = null
    private var generatedConfig: String? = null

    fun generateKeyVaultCryptoServiceConfig(): String {
        val keyVaultParams = AzureKeyVaultConfigParams
            .builder()
            .withServicePrincipalCredentialsFilePath(AzureKeyVaultConfigParams.CREDENTIALS_P12_PATH)
            .withServicePrincipalCredentialsFilePassword(AzureKeyVaultConfigParams.KEY_VAULT_CERTIFICATES_PASSWORD_ENV_VAR_NAME.toEnvVar())
            .withKeyVaultClientId(AzureKeyVaultConfigParams.KEY_VAULT_CLIENT_ID_ENV_VAR_NAME.toEnvVar())
            .withKeyAlias(keyVaultAndCredentials?.keyVaultCredentials?.p12KeyAlias)
            .withKeyVaultURL(keyVaultAndCredentials?.vault?.vaultUri())
            .withKeyVaultProtectionMode(AzureKeyVaultConfigParams.KEY_PROTECTION_MODE_SOFTWARE)
            .build()

        return ConfigGenerators.generateConfigFromParams(keyVaultParams).also {
            this.generatedConfig = it
        }
    }

    fun createKeyVaultSecrets(): KeyVaultSecrets {
        if (this.generatedConfig == null) {
            throw IllegalStateException("must generate config before creating secrets")
        }
        val keyVaultCredentialsSecretName = "az-kv-password-secrets-$nodeId"
        val azKeyVaultCredentialsFilePasswordKey = "az-kv-password-$nodeId"
        val azKeyVaultCredentialsClientIdKey = "az-kv-client-id-$nodeId"
        SecretCreator.createStringSecret(
            keyVaultCredentialsSecretName,
            listOf(
                azKeyVaultCredentialsFilePasswordKey to keyVaultAndCredentials.keyVaultCredentials.p12FilePassword,
                azKeyVaultCredentialsClientIdKey to keyVaultAndCredentials.keyVaultCredentials.servicePrincipal.applicationId()
            ).toMap(),
            namespace,
            api
        )
        val credentialsAndConfigSecretName = "keyvault-auth-file-secrets-$nodeId"
        SecretCreator.createByteArraySecret(
            credentialsAndConfigSecretName,
            listOf(
                AzureKeyVaultConfigParams.CREDENTIALS_P12_FILENAME to keyVaultAndCredentials.keyVaultCredentials.p12Bytes,
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