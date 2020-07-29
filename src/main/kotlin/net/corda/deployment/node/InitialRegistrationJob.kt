package net.corda.deployment.node

import io.kubernetes.client.openapi.models.V1Job
import io.kubernetes.client.openapi.models.V1VolumeMountBuilder
import net.corda.deployment.node.storage.AzureFilesDirectory
import net.corda.deployments.node.config.AzureKeyVaultConfigParams
import net.corda.deployments.node.config.NodeConfigParams

fun initialRegistrationJob(
    jobName: String,
    nodeConfigDir: AzureFilesDirectory,
    keyVaultSecrets: KeyVaultSecrets,
    databaseSecrets: NodeDatabaseSecrets,
    artemisSecrets: ArtemisSecrets,
    nodeStoresSecrets: NodeStoresSecrets,
    initialRegistrationDir: AzureFilesDirectory,
    networkParamsDir: AzureFilesDirectory,
    trustRootConfig: TrustRootConfig
): V1Job {
    val hsmConfigDirMountName = "azurehsmcredentialsdir"
    val nodeConfigDirMountName = "azurecordaconfigdir"
    val certificatesOutputDir = "azurecordacertificatesdir"
    val networkFolderMountName = "networkdir"
    return baseSetupJobBuilder(jobName, listOf("perform-registration"))
        .withVolumeMounts(
            V1VolumeMountBuilder()
                .withName(hsmConfigDirMountName)
                .withMountPath(AzureKeyVaultConfigParams.CREDENTIALS_DIR).build(),
            V1VolumeMountBuilder()
                .withName(nodeConfigDirMountName)
                .withMountPath(NodeConfigParams.NODE_CONFIG_DIR).build(),
            V1VolumeMountBuilder()
                .withName(certificatesOutputDir)
                .withMountPath(NodeConfigParams.NODE_CERTIFICATES_DIR).build(),
            V1VolumeMountBuilder()
                .withName(networkFolderMountName)
                .withMountPath(NodeConfigParams.NODE_NETWORK_PARAMETERS_SETUP_DIR).build()
        )
        .withImagePullPolicy("IfNotPresent")
        .withEnv(
            listOfNotNull(
                trustRootConfig.trustRootSourceURL?.let { trustRootURL ->
                    keyValueEnvVar(
                        "TRUST_ROOT_DOWNLOAD_URL",
                        trustRootURL
                    )
                },
                keyValueEnvVar(
                    "TRUST_ROOT_PATH",
                    NodeConfigParams.NODE_NETWORK_TRUST_ROOT_PATH
                ),
                keyValueEnvVar("NETWORK_TRUSTSTORE_PASSWORD", trustRootConfig.trustRootPassword),
                keyValueEnvVar(
                    "BASE_DIR",
                    NodeConfigParams.NODE_BASE_DIR
                ),
                keyValueEnvVar(
                    "CONFIG_FILE_PATH",
                    NodeConfigParams.NODE_CONFIG_PATH
                ),
                keyValueEnvVar(
                    "CERTIFICATE_SAVE_FOLDER",
                    NodeConfigParams.NODE_CERTIFICATES_DIR
                ),
                keyValueEnvVar(
                    "NETWORK_PARAMETERS_SAVE_FOLDER",
                    NodeConfigParams.NODE_NETWORK_PARAMETERS_SETUP_DIR
                ),
                secretEnvVar(
                    AzureKeyVaultConfigParams.KEY_VAULT_CERTIFICATES_PASSWORD_ENV_VAR_NAME,
                    keyVaultSecrets.credentialPasswordsSecretName,
                    keyVaultSecrets.azKeyVaultCredentialsFilePasswordKey
                ),
                secretEnvVar(
                    AzureKeyVaultConfigParams.KEY_VAULT_CLIENT_ID_ENV_VAR_NAME,
                    keyVaultSecrets.credentialPasswordsSecretName,
                    keyVaultSecrets.azKeyVaultCredentialsClientIdKey
                ),
                secretEnvVar(
                    NodeConfigParams.NODE_DATASOURCE_URL_ENV_VAR_NAME,
                    databaseSecrets.secretName,
                    databaseSecrets.nodeDataSourceURLKey
                ),
                secretEnvVar(
                    NodeConfigParams.NODE_DATASOURCE_USERNAME_ENV_VAR_NAME,
                    databaseSecrets.secretName,
                    databaseSecrets.nodeDataSourceUsernameKey
                ),
                secretEnvVar(
                    NodeConfigParams.NODE_DATASOURCE_PASSWORD_ENV_VAR_NAME,
                    databaseSecrets.secretName,
                    databaseSecrets.nodeDatasourcePasswordKey
                ),
                secretEnvVar(
                    NodeConfigParams.NODE_ARTEMIS_TRUSTSTORE_PASSWORD_ENV_VAR_NAME,
                    artemisSecrets.secretName,
                    artemisSecrets.trustStorePasswordKey
                ),
                secretEnvVar(
                    NodeConfigParams.NODE_ARTEMIS_SSL_KEYSTORE_PASSWORD_ENV_VAR_NAME,
                    artemisSecrets.secretName,
                    artemisSecrets.keyStorePasswordKey
                ),
                secretEnvVar(
                    NodeConfigParams.NODE_SSL_KEYSTORE_PASSWORD_ENV_VAR_NAME,
                    nodeStoresSecrets.secretName,
                    nodeStoresSecrets.nodeKeyStorePasswordKey
                ),
                secretEnvVar(
                    NodeConfigParams.NODE_TRUSTSTORE_PASSWORD_ENV_VAR_NAME,
                    nodeStoresSecrets.secretName,
                    nodeStoresSecrets.sharedTrustStorePasswordKey
                )
            )
        )
        .endContainer()
        .withVolumes(
            secretVolumeWithAll(hsmConfigDirMountName, keyVaultSecrets.credentialAndConfigFilesSecretName),
            azureFileMount(
                nodeConfigDirMountName,
                nodeConfigDir,
                true
            ),
            azureFileMount(
                certificatesOutputDir,
                initialRegistrationDir,
                false
            ),
            azureFileMount(
                networkFolderMountName,
                networkParamsDir,
                false
            )
        )
        .withRestartPolicy("Never")
        .endSpec()
        .endTemplate()
        .endSpec()
        .build()
}

data class TrustRootConfig(
    val trustRootSourceURL: String? = null,
    val trustRootPassword: String
) {

}