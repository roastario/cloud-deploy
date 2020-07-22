package net.corda.deployment.node

import io.kubernetes.client.openapi.models.V1Job
import io.kubernetes.client.openapi.models.V1VolumeMountBuilder
import net.corda.deployment.node.storage.AzureFilesDirectory
import net.corda.deployments.node.config.ArtemisConfigParams

fun generateArtemisStoresJob(
    jobName: String,
    azureFilesSecretName: String,
    artemisSecretsName: String,
    artemisStorePassSecretKey: String,
    artemisTrustPassSecretKey: String,
    workingDir: AzureFilesDirectory
): V1Job {
    return baseSetupJobBuilder(jobName, listOf("generate-artemis-keystores"))
        .withVolumeMounts(
            V1VolumeMountBuilder()
                .withName("azureworkingdir")
                .withMountPath("/tmp/artemisGeneration").build()
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
            secretEnvVar("ARTEMIS_STORE_PASS", artemisSecretsName, artemisStorePassSecretKey),
            secretEnvVar("ARTEMIS_TRUST_PASS", artemisSecretsName, artemisTrustPassSecretKey)
        )
        .endContainer()
        .withVolumes(
            azureFileMount("azureworkingdir", workingDir, azureFilesSecretName, false)
        )
        .withRestartPolicy("Never")
        .endSpec()
        .endTemplate()
        .endSpec()
        .build()
}