package net.corda.deployment.node

import io.kubernetes.client.openapi.models.V1Job
import io.kubernetes.client.openapi.models.V1VolumeMountBuilder
import net.corda.deployment.node.storage.AzureFilesDirectory
import net.corda.deployments.node.config.ArtemisConfigParams

fun generateArtemisStoresJob(
    jobName: String,
    artemisSecrets: ArtemisSecrets,
    artemisShare: AzureFilesDirectory,
    nodeArtemisShare: AzureFilesDirectory,
    bridgeArtemisShare: AzureFilesDirectory
): V1Job {

    val nodeDirMountName = "nodestores"
    val nodeDirPath = ArtemisConfigParams.NODE_DIR_TO_COPY_STORES_TO
    val bridgeDirMountName = "bridgestores"
    val bridgeDirPath = ArtemisConfigParams.BRIDGE_DIR_TO_COPY_STORES_TO
    val artemisDirMountName = "artemisstores"
    val artemisDirPath = ArtemisConfigParams.ARTEMIS_DIR_TO_COPY_STORES_TO


    return baseSetupJobBuilder(jobName, listOf("generate-artemis-keystores"))
        .withVolumeMounts(
            V1VolumeMountBuilder()
                .withName(nodeDirMountName)
                .withMountPath(nodeDirPath).build(),
            V1VolumeMountBuilder()
                .withName(bridgeDirMountName)
                .withMountPath(bridgeDirPath).build(),
            V1VolumeMountBuilder()
                .withName(artemisDirMountName)
                .withMountPath(artemisDirPath).build()
        )
        .withImagePullPolicy("Always")
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
            keyValueEnvVar(
                ArtemisConfigParams.ARTEMIS_DIR_TO_COPY_STORES_TO_ENV_NAME,
                ArtemisConfigParams.ARTEMIS_DIR_TO_COPY_STORES_TO
            ),
            secretEnvVar("ARTEMIS_STORE_PASS", artemisSecrets.secretName, artemisSecrets.keyStorePasswordKey),
            secretEnvVar("ARTEMIS_TRUST_PASS", artemisSecrets.secretName, artemisSecrets.trustStorePasswordKey)
        )
        .endContainer()
        .withVolumes(
            azureFileMount(nodeDirMountName, nodeArtemisShare, false),
            azureFileMount(bridgeDirMountName, bridgeArtemisShare, false),
            azureFileMount(artemisDirMountName, artemisShare, false)
        )
        .withRestartPolicy("Never")
        .endSpec()
        .endTemplate()
        .endSpec()
        .build()
}