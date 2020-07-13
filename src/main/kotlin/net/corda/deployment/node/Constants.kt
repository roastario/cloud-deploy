package net.corda.deployment.node

class AzureKeyVault {
    companion object {
        val SERVICE_PRINCIPAL_CRED_FILE_PATH = "/etc/corda/keyVault.conf"
        val SERVICE_PRINCIPAL_CRED_FILE_PATH_CONFIG_SUB_KEY = "servicePrincipalAuthFilePath"
        val SERVICE_PRINCIPAL_CRED_FILE_PASSWORD_CONFIG_SUB_KEY = "servicePrincipalAuthFilePassword"

        val KEY_ALIAS_CONFIG_SUB_KEY = "keyAlias"
        val VAULT_URL_CONFIG_SUB_KEY = "keyVaultURL"
        val CLIENT_ID_CONFIG_SUB_KEY = "keyVaultClientId"
        val KEY_PROTECTION_MODE_HARDWARE = "HARDWARE"
        val KEY_PROTECTION_MODE_SOFTWARE = "SOFTWARE"
        val KEY_PROTECTION_MODE_CONFIG_SUB_KEY = "keyVaultProtectionMode"
    }
}

