package net.corda.deployment.node

import io.kubernetes.client.openapi.models.V1Job
import io.kubernetes.client.openapi.models.V1VolumeMountBuilder
import net.corda.deployment.node.storage.AzureFilesDirectory
import net.corda.deployments.node.config.BridgeConfigParams

fun generateTunnelStores(
    jobName: String,
    firewallTunnelSecrets: FirewallTunnelSecrets,
    floatShare: AzureFilesDirectory,
    bridgeShare: AzureFilesDirectory
): V1Job {
    val workingDir = "/tmp/tunnelGeneration"
    val floatResultsDirName = "floatstores"
    val bridgeResultsDirName = "bridgestores"

    return baseSetupJobBuilder(jobName, listOf("generate-tunnel-keystores"))
        .withVolumeMounts(
            V1VolumeMountBuilder()
                .withName(floatResultsDirName)
                .withMountPath(FirewallSetup.FLOAT_COPY_STORES_TO_DIR).build(),
            V1VolumeMountBuilder()
                .withName(bridgeResultsDirName)
                .withMountPath(FirewallSetup.BRIDGE_COPY_STORES_TO_DIR).build()
        )
        .withImagePullPolicy("Always")
        .withEnv(
            licenceAcceptEnvVar(),
            keyValueEnvVar("WORKING_DIR", workingDir),
            keyValueEnvVar(FirewallSetup.FLOAT_COPY_TO_DIR_ENV_NAME, FirewallSetup.FLOAT_COPY_STORES_TO_DIR),
            keyValueEnvVar(FirewallSetup.BRIDGE_COPY_TO_DIR_ENV_NAME, FirewallSetup.BRIDGE_COPY_STORES_TO_DIR),
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
                firewallTunnelSecrets.secretName,
                firewallTunnelSecrets.keystorePasswordKey
            ),
            secretEnvVar(
                BridgeConfigParams.BRIDGE_TUNNEL_TRUSTSTORE_PASSWORD_ENV_VAR_NAME,
                firewallTunnelSecrets.secretName,
                firewallTunnelSecrets.truststorePasswordKey
            ),
            secretEnvVar(
                BridgeConfigParams.BRIDGE_TUNNEL_ENTRY_PASSWORD_ENV_VAR_NAME,
                firewallTunnelSecrets.secretName,
                firewallTunnelSecrets.entryPasswordKey
            )
        )
        .endContainer()
        .withVolumes(
            floatShare.toK8sMount(floatResultsDirName, false),
            bridgeShare.toK8sMount(bridgeResultsDirName, false)
        )
        .withRestartPolicy("Never")
        .endSpec()
        .endTemplate()
        .endSpec()
        .build()
}