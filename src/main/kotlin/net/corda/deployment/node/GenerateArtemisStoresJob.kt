package net.corda.deployment.node

import io.kubernetes.client.openapi.models.V1Job
import io.kubernetes.client.openapi.models.V1VolumeMountBuilder
import net.corda.deployment.node.storage.AzureFileShareCreator
import net.corda.deployment.node.storage.AzureFilesDirectory
import net.corda.deployments.node.config.ArtemisConfigParams

fun generateArtemisStoresJob(
    jobName: String,
    artemisSecrets: ArtemisSecrets,
    workingDir: AzureFilesDirectory,
    nodeArtemisShare: AzureFileShareCreator,
    bridgeArtemisShare: AzureFileShareCreator
): V1Job {

    val nodeDirMountName = "nodeStores"
    val nodeDirPath = ArtemisConfigParams.NODE_DIR_TO_COPY_STORES_TO
    val bridgeDirMountName = "bridgeStores"
    val bridgeDirPath = ArtemisConfigParams.BRIDGE_DIR_TO_COPY_STORES_TO

    return baseSetupJobBuilder(jobName, listOf("generate-artemis-keystores"))
        .withVolumeMounts(
            V1VolumeMountBuilder()
                .withName("azureworkingdir")
                .withMountPath("/tmp/artemisGeneration").build(),
            V1VolumeMountBuilder()
                .withName(nodeDirMountName)
                .withMountPath(nodeDirPath).build(),
            V1VolumeMountBuilder()
                .withName(bridgeDirMountName)
                .withMountPath(bridgeDirPath).build()
        )
        .withImagePullPolicy("IfNotPresent")
        .withEnv(
            licenceAcceptEnvVar(),
            keyValueEnvVar("WORKING_DIR", "/tmp/artemisGeneration"),
            keyValueEnvVar(
                ArtemisConfigParams.ARTEMIS_CERTIFICATE_ORGANISATION_ENV_VAR_NAME,
                ArtemisConfigParams.ARTEMIS_CERTIFICATE_ORGANISATION
            ),
            keyValueEnvVar(
                ArtemisConfigParams.ARTEMIS_CERTIFICATE_ORGANISATION_UNIT_ENV_VAR_NAME,
                ArtemisConfigParams.ARTEMIS_CERTIFICATE_ORGANISATION_UNIT
            ),
            keyValueEnvVar(
                ArtemisConfigParams.ARTEMIS_CERTIFICATE_LOCALITY_ENV_VAR_NAME,
                ArtemisConfigParams.ARTEMIS_CERTIFICATE_LOCALITY
            ),
            keyValueEnvVar(
                ArtemisConfigParams.ARTEMIS_CERTIFICATE_COUNTRY_ENV_VAR_NAME,
                ArtemisConfigParams.ARTEMIS_CERTIFICATE_COUNTRY
            ),
            keyValueEnvVar(
                ArtemisConfigParams.NODE_DIR_TO_COPY_STORES_TO_ENV_NAME,
                ArtemisConfigParams.NODE_DIR_TO_COPY_STORES_TO
            ),
            keyValueEnvVar(
                ArtemisConfigParams.BRIDGE_DIR_TO_COPY_STORES_TO_ENV_NAME,
                ArtemisConfigParams.BRIDGE_DIR_TO_COPY_STORES_TO
            ),
            secretEnvVar("ARTEMIS_STORE_PASS", artemisSecrets.secretName, artemisSecrets.keyStorePasswordKey),
            secretEnvVar("ARTEMIS_TRUST_PASS", artemisSecrets.secretName, artemisSecrets.trustStorePasswordKey)
        )
        .endContainer()
        .withVolumes(
            azureFileMount("azureworkingdir", workingDir, false)
        )
        .withRestartPolicy("Never")
        .endSpec()
        .endTemplate()
        .endSpec()
        .build()
}