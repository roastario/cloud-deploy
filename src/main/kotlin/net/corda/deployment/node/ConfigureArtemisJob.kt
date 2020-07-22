package net.corda.deployment.node

import io.kubernetes.client.openapi.models.V1Job
import io.kubernetes.client.openapi.models.V1VolumeMountBuilder
import net.corda.deployment.node.storage.AzureFilesDirectory
import net.corda.deployments.node.config.ArtemisConfigParams

fun configureArtemis(
    jobName: String,
    azureFilesSecretName: String,
    artemisSecretsName: String,
    artemisStorePassSecretKey: String,
    artemisTrustPassSecretKey: String,
    artemisClusterPassSecretKey: String,
    artemisStoresDirectory: AzureFilesDirectory,
    workingDirShare: AzureFilesDirectory
): V1Job {
    val workingDirMountName = "azureworkingdir"
    val storesDirMountName = "storesdir"
    val workingDir = ArtemisConfigParams.ARTEMIS_BROKER_BASE_DIR
    return baseSetupJobBuilder(jobName, listOf("configure-artemis"))
        .withVolumeMounts(
            V1VolumeMountBuilder()
                .withName(workingDirMountName)
                .withMountPath(workingDir).build(),
            V1VolumeMountBuilder()
                .withName(storesDirMountName)
                .withMountPath(ArtemisConfigParams.ARTEMIS_STORES_DIR).build()
        )
        .withEnv(
            licenceAcceptEnvVar(),
            keyValueEnvVar("WORKING_DIR", workingDir),
            keyValueEnvVar(
                ArtemisConfigParams.ARTEMIS_USER_X500_ENV_VAR_NAME,
                ArtemisConfigParams.ARTEMIS_CERTIFICATE_SUBJECT
            ),
            keyValueEnvVar(
                ArtemisConfigParams.ARTEMIS_ACCEPTOR_ADDRESS_ENV_VAR_NAME,
                ArtemisConfigParams.ARTEMIS_ACCEPTOR_ALL_LOCAL_ADDRESSES
            ),
            keyValueEnvVar(
                ArtemisConfigParams.ARTEMIS_ACCEPTOR_PORT_ENV_VAR_NAME,
                ArtemisConfigParams.ARTEMIS_ACCEPTOR_PORT.toString()
            ),
            keyValueEnvVar(
                ArtemisConfigParams.ARTEMIS_KEYSTORE_PATH_ENV_VAR_NAME,
                ArtemisConfigParams.ARTEMIS_SSL_KEYSTORE_PATH
            ),
            keyValueEnvVar(
                ArtemisConfigParams.ARTEMIS_TRUSTSTORE_PATH_ENV_VAR_NAME,
                ArtemisConfigParams.ARTEMIS_TRUSTSTORE_PATH
            ),
            keyValueEnvVar(
                ArtemisConfigParams.ARTEMIS_DATA_DIR_ENV_VAR_NAME,
                ArtemisConfigParams.ARTEMIS_DATA_DIR_PATH
            ),
            secretEnvVar(
                ArtemisConfigParams.ARTEMIS_SSL_KEYSTORE_PASSWORD_ENV_VAR_NAME,
                artemisSecretsName,
                artemisStorePassSecretKey
            ),
            secretEnvVar(
                ArtemisConfigParams.ARTEMIS_TRUSTSTORE_PASSWORD_ENV_VAR_NAME,
                artemisSecretsName,
                artemisTrustPassSecretKey
            ),
            secretEnvVar(
                ArtemisConfigParams.ARTEMIS_CLUSTER_PASSWORD_ENV_VAR_NAME,
                artemisSecretsName,
                artemisClusterPassSecretKey
            )
        )
        .endContainer()
        .withVolumes(
            azureFileMount(workingDirMountName, workingDirShare, azureFilesSecretName, false),
            azureFileMount(
                storesDirMountName,
                artemisStoresDirectory,
                azureFilesSecretName,
                true
            )
        )
        .withRestartPolicy("Never")
        .withNewSecurityContext()
        //artemis is 1001
        .withRunAsUser(1001)
        .withRunAsGroup(1001)
        .withFsGroup(1001)
        .withRunAsNonRoot(true)
        .endSecurityContext()
        .endSpec()
        .endTemplate()
        .endSpec()
        .build()
}