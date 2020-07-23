package net.corda.deployment.node

import io.kubernetes.client.openapi.models.V1Job
import io.kubernetes.client.openapi.models.V1VolumeMountBuilder
import net.corda.deployment.node.storage.AzureFilesDirectory
import net.corda.deployments.node.config.AzureKeyVaultConfigParams
import net.corda.deployments.node.config.NodeConfigParams

fun initialRegistrationJob(
    jobName: String,
    nodeConfigSecretsName: String,
    credentialsSecretName: String,
    p12FileSecretName: String,
    azKeyVaultCredentialsFilePasswordKey: String,
    azKeyVaultCredentialsClientIdKey: String,
    nodeDatasourceSecretName: String,
    nodeDatasourceURLSecretKey: String,
    nodeDatasourceUsernameSecretKey: String,
    nodeDatasourcePasswordSecretyKey: String,
    artemisSecrets: ArtemisSecrets,
    nodeStoresSecretName: String,
    nodeKeyStorePasswordSecretKey: String,
    nodeTrustStorePasswordSecretKey: String,
    certificatesShare: AzureFilesDirectory,
    networkParametersShare: AzureFilesDirectory
): V1Job {
    val p12FileFolderMountName = "azurehsmcredentialsdir"
    val configFilesFolderMountName = "azurecordaconfigdir"
    val certificatesFolderMountName = "azurecordacertificatesdir"
    val networkFolderMountName = "networkdir"
    return baseSetupJobBuilder(jobName, listOf("perform-registration"))
        .withVolumeMounts(
            V1VolumeMountBuilder()
                .withName(p12FileFolderMountName)
                .withMountPath(AzureKeyVaultConfigParams.CREDENTIALS_DIR).build(),
            V1VolumeMountBuilder()
                .withName(configFilesFolderMountName)
                .withMountPath(NodeConfigParams.NODE_CONFIG_DIR).build(),
            V1VolumeMountBuilder()
                .withName(certificatesFolderMountName)
                .withMountPath(NodeConfigParams.NODE_CERTIFICATES_DIR).build(),
            V1VolumeMountBuilder()
                .withName(networkFolderMountName)
                .withMountPath(NodeConfigParams.NODE_NETWORK_PARAMETERS_SETUP_DIR).build()
        )
        .withImagePullPolicy("IfNotPresent")
        .withEnv(
            keyValueEnvVar(
                "TRUST_ROOT_DOWNLOAD_URL",
                "http://networkservices:8080/truststore"
            ),
            keyValueEnvVar(
                "TRUST_ROOT_PATH",
                NodeConfigParams.NODE_NETWORK_TRUST_ROOT_PATH
            ),
            keyValueEnvVar("NETWORK_TRUSTSTORE_PASSWORD", "trustpass"),
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
                credentialsSecretName,
                azKeyVaultCredentialsFilePasswordKey
            ),
            secretEnvVar(
                AzureKeyVaultConfigParams.KEY_VAULT_CLIENT_ID_ENV_VAR_NAME,
                credentialsSecretName,
                azKeyVaultCredentialsClientIdKey
            ),
            secretEnvVar(
                NodeConfigParams.NODE_DATASOURCE_URL_ENV_VAR_NAME,
                nodeDatasourceSecretName,
                nodeDatasourceURLSecretKey
            ),
            secretEnvVar(
                NodeConfigParams.NODE_DATASOURCE_USERNAME_ENV_VAR_NAME,
                nodeDatasourceSecretName,
                nodeDatasourceUsernameSecretKey
            ),
            secretEnvVar(
                NodeConfigParams.NODE_DATASOURCE_PASSWORD_ENV_VAR_NAME,
                nodeDatasourceSecretName,
                nodeDatasourcePasswordSecretyKey
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
                nodeStoresSecretName,
                nodeKeyStorePasswordSecretKey
            ),
            secretEnvVar(
                NodeConfigParams.NODE_TRUSTSTORE_PASSWORD_ENV_VAR_NAME,
                nodeStoresSecretName,
                nodeTrustStorePasswordSecretKey
            )
        )
        .endContainer()
        .withVolumes(
            secretVolumeWithAll(p12FileFolderMountName, p12FileSecretName),
            secretVolumeWithAll(configFilesFolderMountName, nodeConfigSecretsName),
            azureFileMount(
                certificatesFolderMountName,
                certificatesShare,
                false
            ),
            azureFileMount(
                networkFolderMountName,
                networkParametersShare,
                false
            )
        )
        .withRestartPolicy("Never")
        .endSpec()
        .endTemplate()
        .endSpec()
        .build()
}