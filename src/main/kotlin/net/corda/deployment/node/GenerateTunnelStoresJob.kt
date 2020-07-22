package net.corda.deployment.node

import io.kubernetes.client.openapi.models.V1Job
import io.kubernetes.client.openapi.models.V1VolumeMountBuilder
import net.corda.deployment.node.storage.AzureFilesDirectory
import net.corda.deployments.node.config.BridgeConfigParams

fun generateTunnelStores(
    jobName: String,
    azureFilesSecretName: String,
    tunnelSecretName: String,
    tunnelKeyStorePasswordKey: String,
    tunnelTrustStorePasswordKey: String,
    tunnelEntryPasswordKey: String,
    workingDirShare: AzureFilesDirectory
): V1Job {
    val workingDirMountName = "azureworkingdir"
    val workingDir = "/tmp/tunnelGeneration"
    return baseSetupJobBuilder(jobName, listOf("generate-tunnel-keystores"))
        .withVolumeMounts(
            V1VolumeMountBuilder()
                .withName(workingDirMountName)
                .withMountPath(workingDir).build()
        )
        .withImagePullPolicy("IfNotPresent")
        .withEnv(
            licenceAcceptEnvVar(),
            keyValueEnvVar("WORKING_DIR", workingDir),
            keyValueEnvVar(
                BridgeConfigParams.BRIDGE_CERTIFICATE_ORGANISATION_ENV_VAR_NAME,
                BridgeConfigParams.BRIDGE_CERTIFICATE_ORGANISATION
            ),
            keyValueEnvVar(
                BridgeConfigParams.BRIDGE_CERTIFICATE_ORGANISATION_UNIT_ENV_VAR_NAME,
                BridgeConfigParams.BRIDGE_CERTIFICATE_ORGANISATION_UNIT
            ),
            keyValueEnvVar(
                BridgeConfigParams.BRIDGE_CERTIFICATE_LOCALITY_ENV_VAR_NAME,
                BridgeConfigParams.BRIDGE_CERTIFICATE_LOCALITY
            ),
            keyValueEnvVar(
                BridgeConfigParams.BRIDGE_CERTIFICATE_COUNTRY_ENV_VAR_NAME,
                BridgeConfigParams.BRIDGE_CERTIFICATE_COUNTRY
            ),
            secretEnvVar(
                BridgeConfigParams.BRIDGE_TUNNEL_KEYSTORE_PASSWORD_ENV_VAR_NAME,
                tunnelSecretName,
                tunnelKeyStorePasswordKey
            ),
            secretEnvVar(
                BridgeConfigParams.BRIDGE_TUNNEL_TRUST_PASSWORD_ENV_VAR_NAME,
                tunnelSecretName,
                tunnelTrustStorePasswordKey
            ),
            secretEnvVar(
                BridgeConfigParams.BRIDGE_TUNNEL_ENTRY_PASSWORD_ENV_VAR_NAME,
                tunnelSecretName,
                tunnelEntryPasswordKey
            )
        )
        .endContainer()
        .withVolumes(
            azureFileMount(workingDirMountName, workingDirShare, azureFilesSecretName, false)
        )
        .withRestartPolicy("Never")
        .endSpec()
        .endTemplate()
        .endSpec()
        .build()
}